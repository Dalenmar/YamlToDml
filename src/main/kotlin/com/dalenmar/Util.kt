package com.dalenmar

enum class ConflictResolve {
    CLAUSE_DO_NOTHING,
    CLAUSE_DO_UPDATE,
    QUERY_TRUNCATE,
    NONE
}

data class ProfiledPropertyKey(val profile: String, val key: String)

data class Property(val key: String, val value: Any?)

data class ProfiledApplication(val application: String, val profile: String)