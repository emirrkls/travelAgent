package com.emirrkls.phokarta.core.network

interface DemoUserProvider {
    val userId: String
}

class DefaultDemoUserProvider : DemoUserProvider {
    // TEMPORARY v0.6: Replace with the authenticated user's server-issued UUID.
    override val userId: String = "11111111-1111-1111-1111-111111111111"
}
