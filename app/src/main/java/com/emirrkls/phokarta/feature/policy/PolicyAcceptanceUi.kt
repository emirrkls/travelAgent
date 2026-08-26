package com.emirrkls.phokarta.feature.policy

data class PolicyAcceptanceUi(
    val visible: Boolean = false,
    val requiredVersion: String = "",
    val checked: Boolean = false,
    val accepting: Boolean = false,
    val error: Int? = null,
)
