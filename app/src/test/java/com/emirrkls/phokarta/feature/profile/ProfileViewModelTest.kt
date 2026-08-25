package com.emirrkls.phokarta.feature.profile

import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.auth.AuthRepository
import com.emirrkls.phokarta.core.auth.NoOpLocalAccountPurger
import com.emirrkls.phokarta.core.auth.testSessionManager
import com.emirrkls.phokarta.core.model.OwnerSocialCounts
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.network.api.AuthApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.model.AuthSessionDto
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
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authRepository = AuthRepository(
        authApi = UnusedAuthApi,
        meApi = UnusedMeApi,
        sessionManager = testSessionManager(),
        json = Json { ignoreUnknownKeys = true; explicitNulls = true },
        localAccountPurger = NoOpLocalAccountPurger,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsLiveOwnerSocialCountsInsteadOfDemoOverlay() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.ownerSocialCounts = OwnerSocialCounts(
            followerCount = 7,
            followingCount = 3,
            friendCount = 2,
        )
        // Demo overlay still has unrelated mock counts on User.
        assertTrue(repository.currentUser.followersCount != 7)

        val viewModel = ProfileViewModel(repository, authRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        advanceUntilIdle()

        assertEquals(7, viewModel.uiState.value.followerCount)
        assertEquals(3, viewModel.uiState.value.followingCount)
        assertEquals(2, viewModel.uiState.value.friendCount)
    }

    @Test
    fun refreshSocialCountsAfterFollowUnfollow() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.ownerSocialCounts = OwnerSocialCounts(1, 0, 0)
        val viewModel = ProfileViewModel(repository, authRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.followerCount)
        assertEquals(0, viewModel.uiState.value.followingCount)
        assertEquals(0, viewModel.uiState.value.friendCount)

        repository.ownerSocialCounts = OwnerSocialCounts(1, 1, 1)
        viewModel.refreshSocialCounts()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.followerCount)
        assertEquals(1, viewModel.uiState.value.followingCount)
        assertEquals(1, viewModel.uiState.value.friendCount)

        repository.ownerSocialCounts = OwnerSocialCounts(1, 0, 0)
        viewModel.refreshSocialCounts()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.followingCount)
        assertEquals(0, viewModel.uiState.value.friendCount)
        assertEquals(1, viewModel.uiState.value.followerCount)
    }

    @Test
    fun privateOwnerVisitsRemainAvailableOnProfile() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        val place = repository.places.value.first()
        repository.visits.value = listOf(
            Visit(
                id = "44444444-4444-4444-4444-444444444401",
                userId = repository.currentUser.id,
                placeId = place.id,
                visitedAt = LocalDate.of(2026, 8, 1),
                overallRating = 9.0,
                ratingDimensions = emptyMap(),
                review = "public",
                personalNote = "SECRET_OWNER_MEMORY",
            ),
        )
        repository.ownerSocialCounts = OwnerSocialCounts(0, 0, 0)
        val viewModel = ProfileViewModel(repository, authRepository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.visitedPlaces.size)
        assertEquals(
            "SECRET_OWNER_MEMORY",
            viewModel.uiState.value.visitedPlaces.first().visit.personalNote,
        )
    }
}

private object UnusedAuthApi : AuthApi {
    override suspend fun register(request: RegisterRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun login(request: LoginRequestDto): Response<AuthSessionDto> = error("unused")
    override suspend fun refresh(request: RefreshRequestDto): Response<TokenPairDto> = error("unused")
    override suspend fun logout(request: LogoutRequestDto): Response<Unit> = Response.success(Unit)
}

private object UnusedMeApi : MeApi {
    override suspend fun profile(): Response<UserProfileDto> = error("unused")
    override suspend fun friendMetrics(request: FriendMetricsRequestDto): Response<List<FriendMetricsDto>> = error("unused")
    override suspend fun followers(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun following(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun friends(page: Int, size: Int): Response<PageResponseDto<UserSummaryDto>> = error("unused")
    override suspend fun deleteAccount(request: com.emirrkls.phokarta.core.network.model.DeleteAccountRequestDto): Response<Unit> = error("unused")
}
