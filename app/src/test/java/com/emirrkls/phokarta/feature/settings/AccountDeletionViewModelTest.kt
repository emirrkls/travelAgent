package com.emirrkls.phokarta.feature.settings

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.auth.AuthRepository
import com.emirrkls.phokarta.core.auth.NoOpLocalAccountPurger
import com.emirrkls.phokarta.core.auth.testSessionManager
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
import com.emirrkls.phokarta.core.network.model.PolicyAcceptanceRequestDto
import com.emirrkls.phokarta.core.network.model.PolicyStatusDto
import com.emirrkls.phokarta.core.network.model.RefreshRequestDto
import com.emirrkls.phokarta.core.network.model.RegisterRequestDto
import com.emirrkls.phokarta.core.network.model.TokenPairDto
import com.emirrkls.phokarta.core.network.model.UserProfileDto
import com.emirrkls.phokarta.core.network.model.UserSummaryDto
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun doesNotRetainPasswordAfterFailedRequest() = runTest(dispatcher) {
        val captured = AtomicReference<String?>(null)
        val viewModel = AccountDeletionViewModel(
            AuthRepository(
                authApi = UnusedAuthApi,
                meApi = object : UnusedMeApi() {
                    override suspend fun deleteAccount(request: DeleteAccountRequestDto): Response<Unit> {
                        captured.set(request.currentPassword)
                        throw java.io.IOException("offline")
                    }
                },
                sessionManager = testSessionManager(),
                json = Json { ignoreUnknownKeys = true },
                localAccountPurger = NoOpLocalAccountPurger,
            ),
        )
        viewModel.openConfirmation()
        viewModel.updatePassword("SecurePass1")
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals("SecurePass1", captured.get())
        assertEquals("", viewModel.uiState.value.password)
        assertEquals(R.string.delete_account_offline, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.confirmOpen)
    }

    @Test
    fun successClearsEphemeralState() = runTest(dispatcher) {
        val viewModel = AccountDeletionViewModel(
            AuthRepository(
                authApi = UnusedAuthApi,
                meApi = object : UnusedMeApi() {
                    override suspend fun deleteAccount(request: DeleteAccountRequestDto): Response<Unit> =
                        Response.success(Unit)
                },
                sessionManager = testSessionManager(),
                json = Json { ignoreUnknownKeys = true },
                localAccountPurger = NoOpLocalAccountPurger,
            ),
        )
        viewModel.openConfirmation()
        viewModel.updatePassword("SecurePass1")
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.confirmOpen)
        assertEquals("", viewModel.uiState.value.password)
        assertNull(viewModel.uiState.value.error)
    }
}

private object UnusedAuthApi : AuthApi {
    override suspend fun register(request: RegisterRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun login(request: LoginRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun refresh(request: RefreshRequestDto): Response<TokenPairDto> = error("unused")
    override suspend fun logout(request: LogoutRequestDto): Response<Unit> = error("unused")
}

private open class UnusedMeApi : MeApi {
    override suspend fun profile(): Response<UserProfileDto> = error("unused")
    override suspend fun friendMetrics(request: FriendMetricsRequestDto): Response<List<FriendMetricsDto>> = error("unused")
    override suspend fun followers(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun following(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun friends(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun blockedUsers(page: Int, size: Int): Response<PageResponseDto<BlockedUserDto>> = error("unused")
    override suspend fun block(userId: String): Response<Unit> = error("unused")
    override suspend fun unblock(userId: String): Response<Unit> = error("unused")
    override suspend fun deleteAccount(request: DeleteAccountRequestDto): Response<Unit> = error("unused")
    override suspend fun policyStatus(): Response<PolicyStatusDto> = error("unused")
    override suspend fun acceptPolicy(request: PolicyAcceptanceRequestDto): Response<PolicyStatusDto> = error("unused")
}
