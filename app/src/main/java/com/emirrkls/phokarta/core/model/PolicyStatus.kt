package com.emirrkls.phokarta.core.model

data class PolicyStatus(
    val requiredVersion: String,
    val acceptedVersion: String?,
    val accepted: Boolean,
)
