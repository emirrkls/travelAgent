package com.emirrkls.phokarta.core

import com.emirrkls.phokarta.core.data.DefaultTravelRepository
import com.emirrkls.phokarta.core.data.LocalUserStateDataSource
import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.Visibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DefaultTravelRepositoryTest {
    @Test
    fun `repository keeps catalog static while published user visits flow from local storage`() = runBlocking {
        val local = FakeLocalUserStateDataSource()
        val repository = DefaultTravelRepository(MockPlaceCatalogDataSource(), local)
        val visit = Visit(
            id = "new-visit",
            userId = repository.currentUser.id,
            placeId = "p1",
            visitedAt = LocalDate.of(2026, 8, 22),
            overallRating = 9.3,
            ratingDimensions = mapOf("Sea" to 9.6),
            review = "A clear-water morning.",
            personalNote = "Return in September.",
        )

        repository.publishVisit(visit)

        assertEquals(listOf(visit), repository.observeVisits().first())
        assertEquals("Sarnıç Cove", repository.getPlace("p1")?.name)
    }

    @Test
    fun `collection membership changes share the repository local source`() = runBlocking {
        val local = FakeLocalUserStateDataSource()
        val repository = DefaultTravelRepository(MockPlaceCatalogDataSource(), local)
        val collection = Collection(
            id = "collection-1",
            userId = repository.currentUser.id,
            title = "Weekend",
            description = "",
            placeIds = emptyList(),
            visibility = Visibility.PRIVATE,
            coverImage = "",
        )
        repository.saveCollection(collection)

        repository.addPlaceToCollection(collection.id, "p1")
        repository.addPlaceToCollection(collection.id, "p2")
        repository.addPlaceToCollection(collection.id, "p1")

        assertEquals(setOf("p1", "p2"), repository.getCollection(collection.id)?.placeIds?.toSet())
    }
}

private class FakeLocalUserStateDataSource : LocalUserStateDataSource {
    private val visits = MutableStateFlow<List<Visit>>(emptyList())
    private val saved = MutableStateFlow<Set<String>>(emptySet())
    private val collections = MutableStateFlow<List<Collection>>(emptyList())

    override fun observeVisits(): Flow<List<Visit>> = visits
    override fun observeSavedPlaceIds(): Flow<Set<String>> = saved
    override fun observeCollections(): Flow<List<Collection>> = collections
    override suspend fun getCollection(id: String): Collection? = collections.value.firstOrNull { it.id == id }
    override suspend fun publishVisit(visit: Visit) { visits.value = listOf(visit) + visits.value }
    override suspend fun toggleSaved(placeId: String) {
        saved.value = if (placeId in saved.value) saved.value - placeId else saved.value + placeId
    }
    override suspend fun saveCollection(collection: Collection) {
        collections.value = collections.value.filterNot { it.id == collection.id } + collection
    }
    override suspend fun addPlaceToCollection(collectionId: String, placeId: String) {
        collections.value = collections.value.map { collection ->
            if (collection.id == collectionId) collection.copy(placeIds = (collection.placeIds + placeId).distinct()) else collection
        }
    }
    override suspend fun removePlaceFromCollection(collectionId: String, placeId: String) {
        collections.value = collections.value.map { collection ->
            if (collection.id == collectionId) collection.copy(placeIds = collection.placeIds - placeId) else collection
        }
    }
}
