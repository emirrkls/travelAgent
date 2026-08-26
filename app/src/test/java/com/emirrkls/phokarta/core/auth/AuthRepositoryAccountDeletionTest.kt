package com.emirrkls.phokarta.core.auth

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.network.api.AuthApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.model.AuthSessionDto
import com.emirrkls.phokarta.core.network.model.BlockedUserDto
import com.emirrkls.phokarta.core.network.model.DeleteAccountRequestDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsRequestDto
import com.emirrkls.phokarta.core.network.model.LoginRequestDto
import com.emirrkls.phokarta.core.network.model.LogoutRequestDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.RefreshRequestDto
import com.emirrkls.phokarta.core.network.model.RegisterRequestDto
import com.emirrkls.phokarta.core.network.model.TokenPairDto
import com.emirrkls.phokarta.core.network.model.UserProfileDto
import com.emirrkls.phokarta.core.network.model.UserSummaryDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AuthRepositoryAccountDeletionTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    @Test
    fun successPurgesLocalDataAndClearsSession() = runTest {
        val session = testSessionManager()
        val purger = RecordingPurger()
        val repository = AuthRepository(
            authApi = UnusedAuthApi(),
            meApi = DeleteMeApi { Response.success(Unit) },
            sessionManager = session,
            json = json,
            localAccountPurger = purger,
        )

        val result = repository.deleteAccount("SecurePass1")

        assertEquals(AuthResult.Success, result)
        assertEquals(listOf("11111111-1111-1111-1111-111111111111"), purger.purged)
        assertTrue(session.state.value is AuthState.LoggedOut)
        assertNull(session.accessToken())
    }

    @Test
    fun backendFailureKeepsSessionAndSkipsPurge() = runTest {
        val session = testSessionManager()
        val purger = RecordingPurger()
        val repository = AuthRepository(
            authApi = UnusedAuthApi(),
            meApi = DeleteMeApi { throw IOException("offline") },
            sessionManager = session,
            json = json,
            localAccountPurger = purger,
        )

        val result = repository.deleteAccount("SecurePass1")

        assertEquals(AuthResult.Error(R.string.delete_account_offline), result)
        assertTrue(purger.purged.isEmpty())
        assertTrue(session.state.value is AuthState.Authenticated)
        assertEquals("access-token", session.accessToken())
    }

    @Test
    fun wrongPasswordKeepsSession() = runTest {
        val session = testSessionManager()
        val purger = RecordingPurger()
        val body = """
            {"timestamp":"2026-01-01T00:00:00Z","status":400,"code":"INVALID_CURRENT_PASSWORD",
            "message":"Current password is incorrect","path":"/api/v1/me","fieldErrors":{}}
        """.trimIndent().toResponseBody("application/json".toMediaType())
        val repository = AuthRepository(
            authApi = UnusedAuthApi(),
            meApi = DeleteMeApi { Response.error(400, body) },
            sessionManager = session,
            json = json,
            localAccountPurger = purger,
        )

        val result = repository.deleteAccount("wrong-password")

        assertEquals(AuthResult.Error(R.string.delete_account_wrong_password), result)
        assertTrue(purger.purged.isEmpty())
        assertTrue(session.state.value is AuthState.Authenticated)
    }

    @Test
    fun lostAckUnauthorizedPurgesAndLogsOut() = runTest {
        val session = testSessionManager()
        val purger = RecordingPurger()
        val body = """
            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"UNAUTHORIZED",
            "message":"Authentication required","path":"/api/v1/me","fieldErrors":{}}
        """.trimIndent().toResponseBody("application/json".toMediaType())
        val repository = AuthRepository(
            authApi = UnusedAuthApi(),
            meApi = DeleteMeApi { Response.error(401, body) },
            sessionManager = session,
            json = json,
            localAccountPurger = purger,
        )

        val result = repository.deleteAccount("SecurePass1")

        assertEquals(AuthResult.Success, result)
        assertEquals(listOf("11111111-1111-1111-1111-111111111111"), purger.purged)
        assertTrue(session.state.value is AuthState.LoggedOut)
    }

    @Test
    fun restoreSessionTerminalRefreshPurgesLocalAccount() = runTest {
        val session = testSessionManager()
        val purger = RecordingPurger()
        val body = """
            {"timestamp":"2026-01-01T00:00:00Z","status":401,"code":"INVALID_REFRESH_TOKEN",
            "message":"Refresh token is invalid or expired","path":"/api/v1/auth/refresh",
            "fieldErrors":{}}
        """.trimIndent().toResponseBody("application/json".toMediaType())
        val repository = AuthRepository(
            authApi = object : UnusedAuthApi() {
                override suspend fun refresh(request: RefreshRequestDto): Response<TokenPairDto> =
                    Response.error(401, body)
            },
            meApi = UnusedMeApi,
            sessionManager = session,
            json = json,
            localAccountPurger = purger,
        )

        val restored = repository.restoreSession()

        assertTrue(restored is AuthState.LoggedOut)
        assertEquals(listOf("11111111-1111-1111-1111-111111111111"), purger.purged)
    }
}

private class RecordingPurger : LocalAccountPurger {
    val purged = mutableListOf<String>()
    override suspend fun purge(userId: String) {
        purged += userId
    }
    override fun purgeBlocking(userId: String) {
        purged += userId
    }
}

private class DeleteMeApi(
    private val handler: suspend () -> Response<Unit>,
) : MeApi {
    override suspend fun profile(): Response<UserProfileDto> = error("unused")
    override suspend fun friendMetrics(request: FriendMetricsRequestDto): Response<List<FriendMetricsDto>> = error("unused")
    override suspend fun followers(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun following(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun friends(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun blockedUsers(page: Int, size: Int): Response<PageResponseDto<BlockedUserDto>> = error("unused")
    override suspend fun block(userId: String): Response<Unit> = error("unused")
    override suspend fun unblock(userId: String): Response<Unit> = error("unused")
    override suspend fun deleteAccount(request: DeleteAccountRequestDto): Response<Unit> = handler()
}

private open class UnusedAuthApi : AuthApi {
    override suspend fun register(request: RegisterRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun login(request: LoginRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun refresh(request: RefreshRequestDto): Response<TokenPairDto> = error("unused")
    override suspend fun logout(request: LogoutRequestDto): Response<Unit> = error("unused")
}

private object UnusedMeApi : MeApi {
    override suspend fun profile(): Response<UserProfileDto> = error("unused")
    override suspend fun friendMetrics(request: FriendMetricsRequestDto): Response<List<FriendMetricsDto>> = error("unused")
    override suspend fun followers(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun following(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun friends(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun blockedUsers(page: Int, size: Int): Response<PageResponseDto<BlockedUserDto>> = error("unused")
    override suspend fun block(userId: String): Response<Unit> = error("unused")
    override suspend fun unblock(userId: String): Response<Unit> = error("unused")
    override suspend fun deleteAccount(request: DeleteAccountRequestDto): Response<Unit> = error("unused")
}
