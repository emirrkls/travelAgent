package com.emirrkls.phokarta.core.model

enum class ActivityScope {
    COMMUNITY,
    FRIENDS,
    ;

    val queryParam: String
        get() = when (this) {
            COMMUNITY -> "community"
            FRIENDS -> "friends"
        }

    companion object {
        fun fromQueryParam(value: String?): ActivityScope =
            when (value?.lowercase()) {
                "friends" -> FRIENDS
                else -> COMMUNITY
            }
    }
}
