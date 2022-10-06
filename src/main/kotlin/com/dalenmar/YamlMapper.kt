package com.dalenmar

import org.yaml.snakeyaml.Yaml
import java.io.File

// TODO: Properties with dots in name ???
// TODO: Multiple profile in one YAML resolving
// TODO: Fix split lists having wrong indexes (jmx-objects[3])
// TODO: Make new yaml file with remaining properties

private const val DEFAULT_APPLICATION = "application"
private const val DEFAULT_BOOTSTRAP = "bootstrap"
private const val DEFAULT_PROFILE = "default"
private const val DEFAULT_LABEL = "master"

private val DEFAULT_DDL = """
    create table config_server_table (
        p_key       varchar(128) not null,
        p_value     varchar(256),
        application varchar(64)  not null,
        profile     varchar(64)  not null,
        label       varchar(64)  not null,
        constraint config_server_pk primary key (p_key, application, profile, label)
    );
    """.trimIndent()


fun convertYamlToDml(
    searchDir: String = "configs",
    tableName: String = "config_server_table",
    conflictResolve: ConflictResolve,
    propertyPairFirst: Boolean = false,
    propertyBlacklist: List<String>? = null,
    propertyWhitelist: List<String>? = null
) {
    val profilePropertyMap = mutableMapOf<ProfiledPropertyKey, List<Any?>>()
    val applicationProfileCountMap = mutableMapOf<String, Int>()
    val sharedPropertyMap = mutableMapOf<ProfiledApplication, List<Property>>()
    val applicationPropertyMap = mutableMapOf<ProfiledApplication, List<Property>>()

    File("src/main/resources/$searchDir")
        .walk()
        .filterNot { it.isDirectory }
        .filterNot { it.name.startsWith(DEFAULT_BOOTSTRAP) }
        // .filter { it.name.startsWith(DEFAULT_APPLICATION) }
        .filter {
            it.name.lowercase().run {
                endsWith(".yml") || endsWith(".yaml")
            }
        }
        .forEach {
            val application = if (it.parentFile.name == searchDir) DEFAULT_APPLICATION else it.parentFile.name
            val properties = Yaml().load<Map<String, Any>>(it.inputStream())
            val profile = it.name
                .split(".")[0].split("-")
                .let { if (it.size > 1) it.last() else DEFAULT_PROFILE }

            val propertyList = properties.toPlainMap().entries
                .map { (key, value) -> Property(key, value) }

            if (application == DEFAULT_APPLICATION) {
                sharedPropertyMap += ProfiledApplication(application, profile) to propertyList
            } else {
                applicationPropertyMap += ProfiledApplication(application, profile) to propertyList
                applicationProfileCountMap.merge(application, 1, Int::plus)
            }

            propertyList.forEach { (key, value) ->
                profilePropertyMap.compute(
                    ProfiledPropertyKey(
                        profile,
                        key
                    )
                ) { profiledPropertyKey: ProfiledPropertyKey, list: List<Any?>? ->
                    if (list == null) mutableListOf(value) else list + value
                }
            }
        }

    profilePropertyMap
        .filterValues { it.size == applicationProfileCountMap.size }
        .filterValues { it.distinct().size <= 1 }
        .forEach { (profiledPropertyKey, value) ->
            sharedPropertyMap.compute(
                ProfiledApplication(
                    DEFAULT_APPLICATION,
                    profiledPropertyKey.profile
                )
            ) { profiledApplication: ProfiledApplication, propertyList: List<Property>? ->
                val prop = Property(profiledPropertyKey.key, value[0])
                if (propertyList == null) mutableListOf(prop) else propertyList + prop
            }
        }

    println("-- APPLICATIONS TABLE DDL")
    println(DEFAULT_DDL.replace("config_server_table", tableName))
    println()

    when (conflictResolve) {
        ConflictResolve.QUERY_TRUNCATE -> println("truncate $tableName;\n")
        else -> {}
    }

    yieldDml(
        tableName,
        propertyPairFirst,
        conflictResolve,
        sharedPropertyMap,
        null,
        propertyBlacklist,
        propertyWhitelist
    )

    yieldDml(
        tableName,
        propertyPairFirst,
        conflictResolve,
        applicationPropertyMap,
        sharedPropertyMap,
        propertyBlacklist,
        propertyWhitelist
    )
}

private fun yieldDml(
    tableName: String = "config_server_table",
    propertyPairFirst: Boolean = false,
    conflictResolve: ConflictResolve,
    targetPropertyMap: Map<ProfiledApplication, List<Property>>,
    filterPropertyMap: Map<ProfiledApplication, List<Property>>?,
    propertyBlacklist: List<String>? = null,
    propertyWhitelist: List<String>? = null
) {
    targetPropertyMap.forEach { (profApp: ProfiledApplication, propertyList: List<Property>) ->
        val stringBuilder = StringBuilder()
        // TODO: Insert on each property feature
        if (propertyPairFirst)
            stringBuilder.append("insert into $tableName (p_key, p_value, application, profile, label)\n")
        else
            stringBuilder.append("insert into $tableName (application, profile, label, p_key, p_value)\n")

        val filteredPropertyList = propertyList
            .filter {
                filterPropertyMap == null ||
                        filterPropertyMap[ProfiledApplication(DEFAULT_APPLICATION, profApp.profile)]
                            ?.contains(Property(it.key, it.value)) != true
            }

        var lastIndex: Int = -1

        filteredPropertyList
            .filter { property ->
                var pass = propertyWhitelist == null
                propertyWhitelist?.forEach {
                    if (it.endsWith("*")) {
                        if (property.key.startsWith(it.substringBefore("*")))
                            pass = true
                    } else {
                        if (property.key == it)
                            pass = true
                    }
                }
                pass
            }
            .filter { property ->
                var pass = true
                propertyBlacklist?.forEach {
                    if (it.endsWith("*")) {
                        if (property.key.startsWith(it.substringBefore("*")))
                            pass = false
                    } else {
                        if (property.key == it)
                            pass = false
                    }
                }
                pass
            }
            .takeIf { it.isNotEmpty() }
            ?.also { lastIndex = it.lastIndex }
            ?.forEachIndexed { index, (key, value) ->
                // TODO: SQL string escaping (key & value)
                val prefix = if (index == 0) "values " else "       "
                val isIndexLast = (index == lastIndex || lastIndex == 0)
                val postfix: String = when (conflictResolve) {
                    ConflictResolve.CLAUSE_DO_NOTHING -> if (isIndexLast) "\non conflict do nothing;" else ",\n"
                    ConflictResolve.CLAUSE_DO_UPDATE -> if (isIndexLast) "\non conflict do update;" else ",\n"
                    else -> if (isIndexLast) ";" else ",\n"
                }

                if (propertyPairFirst)
                    stringBuilder.append("$prefix('$key', '$value', '${profApp.application}', '${profApp.profile}', '$DEFAULT_LABEL')$postfix")
                else
                    stringBuilder.append("$prefix('${profApp.application}', '${profApp.profile}', '$DEFAULT_LABEL', '$key', '$value')$postfix")
            }
            ?.also {
                when (profApp.application) {
                    DEFAULT_APPLICATION -> println("-- ALL APPLICATIONS | PROFILE: ${profApp.profile}")
                    else -> println("-- APPLICATION: ${profApp.application} | PROFILE: ${profApp.profile}")
                }
                println("$stringBuilder\n")
            }
    }
}

private fun Any?.toPlainMap(
    resultMap: MutableMap<String, Any?> = mutableMapOf(),
    prefix: String = ""
): Map<String, Any?> {
    val delimiter = if (prefix == "") "" else "."
    when (this) {
        is Map<*, *> -> {
            for ((key, value) in this) {
                when (value) {
                    is Map<*, *> -> value.toPlainMap(resultMap, "$prefix$delimiter$key")
                    is List<*> -> value.toPlainMap(resultMap, "$prefix$delimiter$key")
                    else -> resultMap += "$prefix$delimiter$key" to value
                }
            }
        }
        is List<*> -> {
            this.forEachIndexed { index, value ->
                value.toPlainMap(resultMap, "$prefix[$index]")
            }
        }
        else -> resultMap += prefix to this
    }
    return resultMap
}


