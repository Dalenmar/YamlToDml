package com.dalenmar

fun main(args: Array<String>) {
    val propertyBlacklist = listOf(
        "do-not-include-settings.*",
        "custom-settings.setting-do-not-include"
    )

    convertYamlToDml(
        "configs",
        "example_config_table",
        ConflictResolve.CLAUSE_DO_NOTHING,
        false,
        propertyBlacklist
    )
}