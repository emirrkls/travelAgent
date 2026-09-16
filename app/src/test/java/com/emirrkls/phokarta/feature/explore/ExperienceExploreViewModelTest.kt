package com.emirrkls.phokarta.feature.explore

import com.emirrkls.phokarta.core.data.ExperienceFeedGateway
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.model.ExperienceAuthor
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.ExperienceFamily
import com.emirrkls.phokarta.core.model.ExperienceFeedLens
import com.emirrkls.phokarta.core.model.ExperiencePage
import com.emirrkls.phokarta.core.model.ExperiencePlace
import com.emirrkls.phokarta.core.model.ExperiencePrimary
import com.emirrkls.phokarta.core.model.ExperienceSummary
import com.emirrkls.phokarta.core.model.ExperienceVisibility
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.core.model.RelationshipV2
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperienceExploreViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `defaults to For You and exposes exactly four lenses`() = runTest(dispatcher) {
        val gateway = FakeExperienceFeedGateway().apply { enqueue(page(item("for-you"))) }
        val viewModel = ExploreViewModel(gateway)
        advanceUntilIdle()

        assertEquals(ExperienceFeedLens.FOR_YOU, viewModel.uiState.value.lens)
        assertEquals(
            listOf(
                ExperienceFeedLens.FOR_YOU,
                ExperienceFeedLens.FOLLOWING,
                ExperienceFeedLens.NEARBY,
                ExperienceFeedLens.POPULAR,
            ),
            ExperienceFeedLens.entries,
        )
        assertEquals(listOf(ExperienceFeedLens.FOR_YOU), gateway.requests.map { it.lens })
    }

    @Test
    fun `lens switch replaces the feed and Nearby waits for a coarse location`() = runTest(dispatcher) {
        val gateway = FakeExperienceFeedGateway().apply {
            enqueue(page(item("for-you")))
            enqueue(page(item("popular")))
            enqueue(page(item("nearby")))
        }
        val viewModel = ExploreViewModel(gateway)
        advanceUntilIdle()

        viewModel.selectLens(ExperienceFeedLens.POPULAR)
        advanceUntilIdle()
        assertEquals(listOf("popular"), viewModel.uiState.value.items.map { it.id })

        viewModel.selectLens(ExperienceFeedLens.NEARBY)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.needsNearbyLocation)
        assertEquals(2, gateway.requests.size)

        viewModel.setNearbyLocation(41.0, 29.0)
        advanceUntilIdle()
        assertEquals(listOf("nearby"), viewModel.uiState.value.items.map { it.id })
        assertEquals(25_000.0, gateway.requests.last().radiusMeters)
    }

    @Test
    fun `cursor pagination appends without duplicate cards`() = runTest(dispatcher) {
        val gateway = FakeExperienceFeedGateway().apply {
            enqueue(page(item("one"), item("two"), cursor = "next", hasMore = true))
            enqueue(page(item("two"), item("three")))
        }
        val viewModel = ExploreViewModel(gateway)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("one", "two", "three"), viewModel.uiState.value.items.map { it.id })
        assertEquals(listOf(null, "next"), gateway.requests.map { it.cursor })
        assertFalse(viewModel.uiState.value.hasMore)
    }

    @Test
    fun `discovery chips map only to canonical primary or vibe filters`() = runTest(dispatcher) {
        val gateway = FakeExperienceFeedGateway().apply {
            enqueue(page(item("initial")))
            enqueue(page(item("calm")))
            enqueue(page(item("sunset")))
        }
        val viewModel = ExploreViewModel(gateway)
        advanceUntilIdle()

        viewModel.selectDiscoveryFilter(ExperienceDiscoveryFilter.CALM)
        advanceUntilIdle()
        assertEquals("CALM", gateway.requests.last().vibe)
        assertEquals(null, gateway.requests.last().primary)

        viewModel.selectDiscoveryFilter(ExperienceDiscoveryFilter.SUNSET)
        advanceUntilIdle()
        assertEquals("GUN_BATIMI", gateway.requests.last().primary)
        assertEquals(null, gateway.requests.last().vibe)
    }

    @Test
    fun `late response from an older lens cannot overwrite current state`() = runTest(dispatcher) {
        val slow = CompletableDeferred<RepositoryResult<ExperiencePage>>()
        val gateway = FakeExperienceFeedGateway().apply {
            enqueueDeferred(slow)
            enqueue(page(item("popular")))
        }
        val viewModel = ExploreViewModel(gateway)
        runCurrent()

        viewModel.selectLens(ExperienceFeedLens.POPULAR)
        advanceUntilIdle()
        assertEquals(listOf("popular"), viewModel.uiState.value.items.map { it.id })

        slow.complete(RepositoryResult.Success(page(item("stale"))))
        advanceUntilIdle()

        assertEquals(ExperienceFeedLens.POPULAR, viewModel.uiState.value.lens)
        assertEquals(listOf("popular"), viewModel.uiState.value.items.map { it.id })
    }

    private fun page(
        vararg items: ExperienceSummary,
        cursor: String? = null,
        hasMore: Boolean = false,
    ) = ExperiencePage(items.toList(), cursor, hasMore)

    private fun item(id: String) = ExperienceSummary(
        id = id,
        classification = ExperienceClassification.NATIVE_V2,
        author = ExperienceAuthor(
            id = "author-$id",
            username = "author",
            displayName = "Author",
            avatarUrl = null,
            relationship = relationship(RelationshipActionState.FOLLOWING),
        ),
        place = ExperiencePlace(
            id = "place-$id",
            name = "Place",
            categoryCode = "BEACH",
            city = "İzmir",
            region = "Aegean",
            country = "Türkiye",
            coverImage = "",
        ),
        experiencedAt = LocalDate.of(2026, 9, 16),
        title = "Experience $id",
        titleSource = null,
        primaryExperience = ExperiencePrimary(
            PrimaryExperienceCode.GUN_BATIMI,
            true,
            ExperienceFamily.SCENERY_AND_MOMENT,
            null,
        ),
        feeling = OverallFeelingCode.GUZELDI,
        storyPreview = "Story",
        tipPreview = null,
        companion = null,
        timeOfDay = null,
        vibes = emptyList(),
        practicalSignals = emptyList(),
        mediaPreview = null,
        mediaCount = 0,
        visibility = ExperienceVisibility.PUBLIC,
    )

    private fun relationship(state: RelationshipActionState) = RelationshipV2(
        state = state,
        followsYou = false,
        canFollow = state == RelationshipActionState.NONE,
        canCancelRequest = state == RelationshipActionState.REQUEST_PENDING,
    )
}

private class FakeExperienceFeedGateway : ExperienceFeedGateway {
    data class Request(
        val lens: ExperienceFeedLens,
        val cursor: String?,
        val radiusMeters: Double?,
        val primary: String?,
        val vibe: String?,
    )

    val requests = mutableListOf<Request>()
    private val responses = ArrayDeque<suspend () -> RepositoryResult<ExperiencePage>>()

    fun enqueue(page: ExperiencePage) {
        responses.addLast { RepositoryResult.Success(page) }
    }

    fun enqueueDeferred(value: CompletableDeferred<RepositoryResult<ExperiencePage>>) {
        responses.addLast { value.await() }
    }

    override suspend fun feed(
        lens: ExperienceFeedLens,
        cursor: String?,
        size: Int,
        search: String?,
        primary: String?,
        vibe: String?,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Double?,
    ): RepositoryResult<ExperiencePage> {
        requests += Request(lens, cursor, radiusMeters, primary, vibe)
        return responses.removeFirst().invoke()
    }

    override suspend fun follow(authorId: String) =
        RepositoryResult.Success(relationship(RelationshipActionState.FOLLOWING))

    override suspend fun unfollow(authorId: String) =
        RepositoryResult.Success(relationship(RelationshipActionState.NONE))

    override suspend fun cancelFollowRequest(authorId: String) =
        RepositoryResult.Success(relationship(RelationshipActionState.NONE))

    private fun relationship(state: RelationshipActionState) = RelationshipV2(
        state = state,
        followsYou = false,
        canFollow = state == RelationshipActionState.NONE,
        canCancelRequest = state == RelationshipActionState.REQUEST_PENDING,
    )
}
