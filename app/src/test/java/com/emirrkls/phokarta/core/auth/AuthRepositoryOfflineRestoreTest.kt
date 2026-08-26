package com.emirrkls.phokarta.core.auth

import com.emirrkls.phokarta.core.auth.NoOpLocalAccountPurger
import com.emirrkls.phokarta.core.network.api.AuthApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.model.AuthSessionDto
import com.emirrkls.phokarta.core.network.model.BlockedUserDto
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AuthRepositoryOfflineRestoreTest {
    @Test
    fun transportFailure_preservesStoredOwnerForOfflineData() = runTest {
        val session = testSessionManager()
        val repository = AuthRepository(
            authApi = OfflineAuthApi,
            meApi = OfflineUnusedMeApi,
            sessionManager = session,
            json = Json { ignoreUnknownKeys = true },
            localAccountPurger = NoOpLocalAccountPurger,
        )

        val restored = repository.restoreSession()

        assertTrue(restored is AuthState.Authenticated)
        assertEquals(
            "11111111-1111-1111-1111-111111111111",
            (restored as AuthState.Authenticated).user.id,
        )
        assertEquals("refresh-token", session.refreshToken())
    }
}

private object OfflineAuthApi : AuthApi {
    override suspend fun register(request: RegisterRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun login(request: LoginRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun refresh(request: RefreshRequestDto): Response<TokenPairDto> = throw IOException("offline")
    override suspend fun logout(request: LogoutRequestDto): Response<Unit> = error("unused")
}

private object OfflineUnusedMeApi : MeApi {
    override suspend fun profile(): Response<UserProfileDto> = error("unused")
    override suspend fun friendMetrics(request: FriendMetricsRequestDto): Response<List<FriendMetricsDto>> = error("unused")
    override suspend fun followers(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun following(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun friends(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun blockedUsers(page: Int, size: Int): Response<PageResponseDto<BlockedUserDto>> = error("unused")
    override suspend fun block(userId: String): Response<Unit> = error("unused")
    override suspend fun unblock(userId: String): Response<Unit> = error("unused")
    override suspend fun deleteAccount(request: com.emirrkls.phokarta.core.network.model.DeleteAccountRequestDto): Response<Unit> = error("unused")
    override suspend fun policyStatus(): Response<com.emirrkls.phokarta.core.network.model.PolicyStatusDto> = error("unused")
    override suspend fun acceptPolicy(request: com.emirrkls.phokarta.core.network.model.PolicyAcceptanceRequestDto): Response<com.emirrkls.phokarta.core.network.model.PolicyStatusDto> = error("unused")
}
