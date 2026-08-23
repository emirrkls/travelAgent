package com.emirrkls.phokarta.core.auth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds Bearer authorization header for non-auth paths`() {
        val session = testSessionManager(accessToken = "tok-abc")
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session))
            .build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/api/v1/me")).get().build(),
        ).execute().use { assertTrue(it.isSuccessful) }

        assertEquals("Bearer tok-abc", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `skips authorization header for auth endpoints`() {
        val session = testSessionManager(accessToken = "tok-abc")
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session))
            .build()
        server.enqueue(MockResponse().setBody("{}"))

        client.newCall(
            Request.Builder().url(server.url("/api/v1/auth/login")).post(
                ByteArray(0).toRequestBody(null),
            ).build(),
        ).execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}

class TokenAuthenticatorTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `single-flight refresh shares one refresh call across concurrent 401s`() {
        val session = testSessionManager(
            accessToken = "stale-access",
            refreshToken = "refresh-1",
        )
        val refreshCount = AtomicInteger(0)

        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when {
                    request.path?.endsWith("/api/v1/auth/refresh") == true -> {
                        refreshCount.incrementAndGet()
                        MockResponse()
                            .setBodyDelay(400, TimeUnit.MILLISECONDS)
                            .setBody(
                                """{"accessToken":"fresh-access","refreshToken":"refresh-2","tokenType":"Bearer","expiresIn":3600}""",
                            )
                            .addHeader("Content-Type", "application/json")
                    }
                    request.getHeader("Authorization") == "Bearer stale-access" ->
                        MockResponse().setResponseCode(401)
                    else ->
                        MockResponse().setBody("ok")
                }
            }
        }

        val authenticator = TokenAuthenticator(session, json, server.url("/").toString())
        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .build()

        val finished = CountDownLatch(2)
        repeat(2) {
            Thread {
                client.newCall(
                    Request.Builder()
                        .url(server.url("/api/v1/me"))
                        .header("Authorization", "Bearer stale-access")
                        .get()
                        .build(),
                ).execute().close()
                finished.countDown()
            }.start()
        }

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, refreshCount.get())
        assertEquals("fresh-access", session.accessToken())
        assertEquals("refresh-2", session.refreshToken())
    }
}
