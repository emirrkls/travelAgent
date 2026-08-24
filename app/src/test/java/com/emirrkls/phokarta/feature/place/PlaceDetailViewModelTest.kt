package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.Visibility
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.createViewModel(
        placeId: String,
        repository: CollectionAwareRepository,
    ): PlaceDetailViewModel {
        val viewModel = PlaceDetailViewModel(SavedStateHandle(mapOf("placeId" to placeId)), repository)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }
        return viewModel
    }

    @Test
    fun `visits for place are newest first and distinct`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = CollectionAwareRepository().apply {
            visits.value = listOf(
                visit("v-old", placeId, LocalDate.of(2025, 7, 1), 9.3),
                visit("v-new", placeId, LocalDate.of(2026, 8, 1), 8.9),
                visit("v-other", "other-place", LocalDate.of(2026, 9, 1), 7.0),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        assertEquals(listOf("v-new", "v-old"), viewModel.uiState.value.visits.map { it.id })
    }

    @Test
    fun `create collection appears and can auto-add place`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = CollectionAwareRepository()
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.createCollection("Bodrum Summer", "Sea days", Visibility.PRIVATE, autoSelect = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCreatingCollection)
        assertEquals(1, repository.collections.value.size)
        assertEquals("Bodrum Summer", repository.collections.value.single().title)
        assertTrue(placeId in repository.collections.value.single().placeIds)
    }

    @Test
    fun `add place prevents duplicate membership`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = CollectionAwareRepository().apply {
            collections.value = listOf(
                Collection(
                    id = "c1",
                    userId = currentUser.id,
                    title = "Want to Go",
                    description = "",
                    placeIds = listOf(placeId),
                    visibility = Visibility.PRIVATE,
                    coverImage = "cover",
                ),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.toggleCollectionMembership("c1")
        advanceUntilIdle()
        assertFalse(placeId in repository.collections.value.single().placeIds)

        viewModel.toggleCollectionMembership("c1")
        advanceUntilIdle()
        assertTrue(placeId in repository.collections.value.single().placeIds)

        // Concurrent-style conflict while local state thinks place is absent
        repository.collections.value = listOf(
            repository.collections.value.single().copy(placeIds = emptyList()),
        )
        repository.duplicateAdd = true
        viewModel.toggleCollectionMembership("c1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.membershipErrorMessage == null)
        assertEquals(0, repository.collections.value.single().placeIds.count { it == placeId })
    }

    @Test
    fun `want to go rollback surfaces error`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = CollectionAwareRepository().apply { failToggleSaved = true }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.toggleSaved()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSaved)
        assertTrue(viewModel.uiState.value.saveErrorMessage != null)
    }

    @Test
    fun `create collection failure keeps list unchanged`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = CollectionAwareRepository().apply { failSaveCollection = true }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()

        viewModel.createCollection("Fail", "", Visibility.PUBLIC)
        advanceUntilIdle()

        assertTrue(repository.collections.value.isEmpty())
        assertTrue(viewModel.uiState.value.createCollectionError != null)
    }

    @Test
    fun `share text stays empty until prepared with resources`() = runTest(dispatcher) {
        val placeId = seedPlaceId()
        val repository = CollectionAwareRepository().apply {
            visits.value = listOf(
                visit("v1", placeId, LocalDate.of(2026, 8, 1), 9.0).copy(personalNote = "SECRET"),
            )
        }
        val viewModel = createViewModel(placeId, repository)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.shareText)
        assertTrue(repository.visits.value.any { it.personalNote == "SECRET" })
    }

    private fun seedPlaceId(): String =
        com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource.mockPlaces.first().id

    private fun visit(id: String, placeId: String, date: LocalDate, score: Double) = Visit(
        id = id,
        userId = "u1",
        placeId = placeId,
        visitedAt = date,
        overallRating = score,
        ratingDimensions = emptyMap(),
        review = "",
        personalNote = "",
    )

    private class CollectionAwareRepository : TestTravelRepository() {
        var failToggleSaved = false
        var failSaveCollection = false
        var duplicateAdd = false

        override suspend fun toggleSaved(placeId: String): RepositoryResult<Boolean> {
            if (failToggleSaved) return RepositoryResult.Failure(TravelError.Offline())
            return super.toggleSaved(placeId)
        }

        override suspend fun saveCollection(collection: Collection): RepositoryResult<Collection> {
            if (failSaveCollection) return RepositoryResult.Failure(TravelError.Server(503))
            val saved = collection.copy(id = "created-${collections.value.size}")
            collections.value = collections.value + saved
            return RepositoryResult.Success(saved)
        }

        override suspend fun addPlaceToCollection(collectionId: String, placeId: String): RepositoryResult<Collection> {
            val current = collections.value.firstOrNull { it.id == collectionId }
                ?: return RepositoryResult.Failure(TravelError.NotFound())
            if (placeId in current.placeIds || duplicateAdd) {
                return RepositoryResult.Failure(TravelError.Conflict("already"))
            }
            val updated = current.copy(placeIds = current.placeIds + placeId)
            collections.value = collections.value.map { if (it.id == collectionId) updated else it }
            return RepositoryResult.Success(updated)
        }

        override suspend fun removePlaceFromCollection(collectionId: String, placeId: String): RepositoryResult<Unit> {
            val current = collections.value.firstOrNull { it.id == collectionId }
                ?: return RepositoryResult.Failure(TravelError.NotFound())
            collections.value = collections.value.map {
                if (it.id == collectionId) it.copy(placeIds = it.placeIds - placeId) else it
            }
            return RepositoryResult.Success(Unit)
        }

        override suspend fun refreshCollectionDetail(id: String): RepositoryResult<Collection> =
            getCollection(id)?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Failure(TravelError.NotFound())
    }
}
