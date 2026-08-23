package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.model.ActivityFeedPage
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.NearbyPlace
import com.emirrkls.phokarta.core.model.OwnerSocialCounts
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.PublicReviewPage
import com.emirrkls.phokarta.core.model.PublicUserProfile
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.UserPage
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.VisitStateLogic
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.mapper.toActivityEvent
import com.emirrkls.phokarta.core.network.mapper.toCreateDto
import com.emirrkls.phokarta.core.network.mapper.toCanonicalUuid
import com.emirrkls.phokarta.core.network.mapper.toDomain
import com.emirrkls.phokarta.core.network.mapper.toEpochMillisSafely
import com.emirrkls.phokarta.core.network.mapper.toPublicReview
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.source.CollectionRemoteDataSource
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SavedPlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SocialRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PAGINATION_PAGES = 1_000

@Singleton
class DefaultTravelRepository @Inject constructor(
    private val activityDemo: MockPlaceCatalogDataSource,
    private val localUserState: LocalUserStateDataSource,
    private val placesRemote: PlaceRemoteDataSource,
    private val visitsRemote: VisitRemoteDataSource,
    private val savedRemote: SavedPlaceRemoteDataSource,
    private val collectionsRemote: CollectionRemoteDataSource,
    private val socialRemote: SocialRemoteDataSource,
    private val sessionManager: SessionManager,
    private val activityFeedInvalidator: ActivityFeedInvalidator,
    private val placeCache: PlaceCacheDataSource = NoOpPlaceCacheDataSource,
) : TravelRepository {
    private val remotePlaces = MutableStateFlow<List<Place>>(emptyList())

    override val currentUser: User
        get() {
            val auth = sessionManager.state.value as? AuthState.Authenticated
            return if (auth != null) {
                activityDemo.currentUser.copy(
                    id = auth.user.id,
                    username = auth.user.username,
                    displayName = auth.user.displayName,
                    avatarUrl = auth.user.avatarUrl.ifBlank { activityDemo.currentUser.avatarUrl },
                    bio = auth.user.bio.ifBlank { activityDemo.currentUser.bio },
                )
            } else {
                activityDemo.currentUser
            }
        }

    private fun requireUserId(): String =
        sessionManager.currentUserId()
            ?: error("Authenticated user required")

    override fun observePlaces(): Flow<List<Place>> = remotePlaces
    override fun observeVisits(): Flow<List<Visit>> = localUserState.observeVisits()
    override fun observeVisitedPlaceIds(): Flow<Set<String>> =
        observeVisits().map(VisitStateLogic::visitedPlaceIds)
    override fun observeSavedPlaceIds(): Flow<Set<String>> = localUserState.observeSavedPlaceIds()
    override fun observeCollections(): Flow<List<Collection>> = localUserState.observeCollections()
    override suspend fun getPlace(id: String): Place? =
        (refreshPlaceDetail(id) as? RepositoryResult.Success)?.value
            ?: remotePlaces.value.firstOrNull { it.id == id }

    override suspend fun getCollection(id: String): Collection? =
        (refreshCollectionDetail(id) as? RepositoryResult.Success)?.value
            ?: localUserState.getCollection(id)

    override suspend fun loadActivityPage(page: Int, size: Int): RepositoryResult<ActivityFeedPage> =
        try {
            when (val result = visitsRemote.publicActivity(page, size)) {
                is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
                is RemoteResult.Success -> mapOrValidation {
                    val response = result.value
                    RepositoryResult.Success(
                        ActivityFeedPage(
                            items = response.content.map { it.toActivityEvent() },
                            page = response.page,
                            totalPages = response.totalPages,
                            totalElements = response.totalElements,
                            hasNext = response.hasNext,
                        ),
                    )
                }
            }
        } catch (error: IllegalArgumentException) {
            RepositoryResult.Failure(TravelError.Validation(error.message))
        }

    override suspend fun listPlaces(
        category: PlaceCategory?,
        city: String?,
        search: String?,
        minRating: Double?,
        sort: String,
        page: Int,
        size: Int,
    ): RepositoryResult<PlacePage> = when (
        val result = placesRemote.list(
            category = category?.let { PlaceCategoryDto.valueOf(it.name) },
            city = city,
            search = search,
            minRating = minRating,
            sort = sort,
            page = page,
            size = size,
        )
    ) {
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
        is RemoteResult.Success -> mapOrValidation {
            val places = result.value.content.map { it.toDomain() }
            mergePlaces(places)
            RepositoryResult.Success(
                PlacePage(
                    places = places,
                    page = result.value.page,
                    totalPages = result.value.totalPages,
                    totalElements = result.value.totalElements,
                    hasNext = result.value.hasNext,
                ),
            )
        }
    }

    override suspend fun refreshCatalog(): RepositoryResult<PlacePage> {
        if (remotePlaces.value.isEmpty()) {
            mergePlaces(placeCache.getAll(), persist = false)
        }
        val summaries = mutableListOf<com.emirrkls.phokarta.core.network.model.PlaceSummaryDto>()
        var nextPage = 0
        var pagesFetched = 0
        var totalPages = 0
        var totalElements = 0L
        do {
            val response = when (val result = placesRemote.list(page = nextPage, size = 100)) {
                is RemoteResult.Failure -> return RepositoryResult.Failure(result.error.toTravelError())
                is RemoteResult.Success -> result.value
            }
            if (pagesFetched == 0) {
                totalPages = response.totalPages
                totalElements = response.totalElements
            }
            summaries += response.content
            pagesFetched++
            if (response.hasNext && pagesFetched >= MAX_PAGINATION_PAGES) {
                return RepositoryResult.Failure(TravelError.Validation("Catalog pagination exceeded safe limit"))
            }
            nextPage++
        } while (response.hasNext)

        return mapOrValidation {
            val places = summaries.map { it.toDomain() }.distinctBy { it.id }
            mergePlaces(places)
            RepositoryResult.Success(
                PlacePage(places, page = 0, totalPages, totalElements, hasNext = false),
            )
        }
    }

    override suspend fun refreshBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: PlaceCategory?,
        minRating: Double?,
    ): RepositoryResult<List<Place>> = when (
        val result = placesRemote.bounds(
            west,
            south,
            east,
            north,
            category?.let { PlaceCategoryDto.valueOf(it.name) },
            minRating,
        )
    ) {
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
        is RemoteResult.Success -> mapOrValidation {
            val places = result.value.map { it.toDomain() }
            mergePlaces(places)
            RepositoryResult.Success(places)
        }
    }

    override suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        category: PlaceCategory?,
        minRating: Double?,
    ): RepositoryResult<List<NearbyPlace>> = when (
        val result = placesRemote.nearby(
            latitude,
            longitude,
            radiusMeters,
            category?.let { PlaceCategoryDto.valueOf(it.name) },
            minRating,
        )
    ) {
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
        is RemoteResult.Success -> mapOrValidation {
            val nearby = result.value.map { NearbyPlace(it.place.toDomain(), it.distanceMeters) }
            mergePlaces(nearby.map { it.place })
            RepositoryResult.Success(nearby)
        }
    }

    override suspend fun refreshPlaceDetail(id: String): RepositoryResult<Place> =
        try {
        when (val result = placesRemote.detail(id.toCanonicalUuid())) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> mapOrValidation {
                val place = result.value.toDomain()
                mergePlaces(listOf(place))
                RepositoryResult.Success(place)
            }
        }
        } catch (error: IllegalArgumentException) {
            RepositoryResult.Failure(TravelError.Validation(error.message))
        }

    override suspend fun refreshPublicReviews(
        placeId: String,
        page: Int,
        size: Int,
    ): RepositoryResult<PublicReviewPage> = try {
        when (val result = visitsRemote.publicReviews(placeId.toCanonicalUuid(), page, size)) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> mapOrValidation {
                val response = result.value
                RepositoryResult.Success(
                    PublicReviewPage(
                        reviews = response.content.map { it.toPublicReview() },
                        page = response.page,
                        totalPages = response.totalPages,
                        totalElements = response.totalElements,
                        hasNext = response.hasNext,
                    ),
                )
            }
        }
    } catch (error: IllegalArgumentException) {
        RepositoryResult.Failure(TravelError.Validation(error.message))
    }

    override suspend fun refreshOwnerVisits(page: Int, size: Int): RepositoryResult<List<Visit>> {
        val visitDtos = mutableListOf<com.emirrkls.phokarta.core.network.model.VisitOwnerDto>()
        var nextPage = page
        var pagesFetched = 0
        do {
            val response = when (val result = visitsRemote.ownerVisits(nextPage, size)) {
                is RemoteResult.Failure -> return RepositoryResult.Failure(result.error.toTravelError())
                is RemoteResult.Success -> result.value
            }
            visitDtos += response.content
            pagesFetched++
            if (response.hasNext && pagesFetched >= MAX_PAGINATION_PAGES) {
                return RepositoryResult.Failure(TravelError.Validation("Visit pagination exceeded safe limit"))
            }
            nextPage++
        } while (response.hasNext)

        return mapOrValidation {
            val visits = visitDtos.map { it.toDomain(requireUserId()) }.distinctBy { it.id }
            val places = visitDtos.map { it.place.toDomain() }.distinctBy { it.id }
            mergePlaces(places)
            localUserState.upsertVisits(visits)
            RepositoryResult.Success(visits)
        }
    }

    override suspend fun refreshSaved(page: Int, size: Int): RepositoryResult<Set<String>> {
        val savedDtos = mutableListOf<com.emirrkls.phokarta.core.network.model.SavedPlaceDto>()
        var nextPage = page
        do {
            val response = when (val result = savedRemote.list(nextPage, size)) {
                is RemoteResult.Failure -> return RepositoryResult.Failure(result.error.toTravelError())
                is RemoteResult.Success -> result.value
            }
            savedDtos += response.content
            nextPage++
        } while (response.hasNext)
        return mapOrValidation {
            val places = savedDtos.map { it.place.toDomain() }
            val entries = savedDtos.map { dto ->
                dto.place.toDomain().id to dto.savedAt.toEpochMillisSafely()
            }
            val ids = entries.mapTo(linkedSetOf()) { it.first }
            mergePlaces(places)
            localUserState.replaceSavedPlaces(entries)
            RepositoryResult.Success(ids)
        }
    }

    override suspend fun refreshCollections(page: Int, size: Int): RepositoryResult<List<Collection>> {
        val summaries = mutableListOf<com.emirrkls.phokarta.core.network.model.CollectionSummaryDto>()
        var nextPage = page
        do {
            val response = when (val result = collectionsRemote.list(nextPage, size)) {
                is RemoteResult.Failure -> return RepositoryResult.Failure(result.error.toTravelError())
                is RemoteResult.Success -> result.value
            }
            summaries += response.content
            nextPage++
        } while (response.hasNext)
        val details = summaries.map { summary ->
            when (val result = collectionsRemote.detail(summary.id)) {
                is RemoteResult.Failure -> return RepositoryResult.Failure(result.error.toTravelError())
                is RemoteResult.Success -> result.value
            }
        }
        return mapOrValidation {
            val collections = details.map { it.toDomain() }
            mergePlaces(details.flatMap { detail -> detail.places.map { it.place.toDomain() } })
            localUserState.replaceCollections(collections)
            RepositoryResult.Success(collections)
        }
    }

    override suspend fun refreshCollectionDetail(id: String): RepositoryResult<Collection> {
        val canonicalId = try {
            id.toCanonicalUuid()
        } catch (error: IllegalArgumentException) {
            return RepositoryResult.Failure(TravelError.Validation(error.message))
        }
        return when (val result = collectionsRemote.detail(canonicalId)) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> persistCollectionDetail(result.value)
        }
    }

    override suspend fun publishVisit(visit: Visit): RepositoryResult<Visit> {
        val request = try {
            visit.copy(userId = requireUserId()).toCreateDto()
        } catch (error: IllegalArgumentException) {
            return RepositoryResult.Failure(TravelError.Validation(error.message))
        }
        return when (val result = visitsRemote.create(request)) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> mapOrValidation {
                val canonical = result.value.toDomain(requireUserId())
                mergePlaces(listOf(result.value.place.toDomain()))
                localUserState.upsertVisit(canonical)
                if (canonical.visibility == com.emirrkls.phokarta.core.model.Visibility.PUBLIC) {
                    activityFeedInvalidator.markDirty()
                }
                RepositoryResult.Success(canonical)
            }
        }
    }

    override suspend fun toggleSaved(placeId: String): RepositoryResult<Boolean> {
        val canonicalPlaceId = try {
            placeId.toCanonicalUuid()
        } catch (error: IllegalArgumentException) {
            return RepositoryResult.Failure(TravelError.Validation(error.message))
        }
        val wasSaved = localUserState.isSaved(canonicalPlaceId)
        val target = !wasSaved
        localUserState.setSaved(canonicalPlaceId, target)
        if (target) {
            return when (val result = savedRemote.save(canonicalPlaceId)) {
                is RemoteResult.Failure -> {
                    localUserState.setSaved(canonicalPlaceId, wasSaved)
                    RepositoryResult.Failure(result.error.toTravelError())
                }
                is RemoteResult.Success -> try {
                    mergePlaces(listOf(result.value.place.toDomain()))
                    RepositoryResult.Success(true)
                } catch (error: IllegalArgumentException) {
                    localUserState.setSaved(canonicalPlaceId, wasSaved)
                    RepositoryResult.Failure(TravelError.Validation(error.message))
                }
            }
        }
        return when (val result = savedRemote.remove(canonicalPlaceId)) {
            is RemoteResult.Failure -> {
                localUserState.setSaved(canonicalPlaceId, wasSaved)
                RepositoryResult.Failure(result.error.toTravelError())
            }
            is RemoteResult.Success -> RepositoryResult.Success(false)
        }
    }

    override suspend fun saveCollection(collection: Collection): RepositoryResult<Collection> {
        val request = try {
            collection.copy(userId = requireUserId()).toCreateDto()
        } catch (error: IllegalArgumentException) {
            return RepositoryResult.Failure(TravelError.Validation(error.message))
        }
        return when (val result = collectionsRemote.create(request)) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> persistCollectionDetail(result.value)
        }
    }

    override suspend fun addPlaceToCollection(
        collectionId: String,
        placeId: String,
    ): RepositoryResult<Collection> {
        val ids = try {
            collectionId.toCanonicalUuid() to placeId.toCanonicalUuid()
        } catch (error: IllegalArgumentException) {
            return RepositoryResult.Failure(TravelError.Validation(error.message))
        }
        return when (val result = collectionsRemote.addPlace(ids.first, ids.second)) {
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
        is RemoteResult.Success -> persistCollectionDetail(result.value)
        }
    }

    override suspend fun removePlaceFromCollection(
        collectionId: String,
        placeId: String,
    ): RepositoryResult<Unit> {
        val ids = try {
            collectionId.toCanonicalUuid() to placeId.toCanonicalUuid()
        } catch (error: IllegalArgumentException) {
            return RepositoryResult.Failure(TravelError.Validation(error.message))
        }
        return when (val result = collectionsRemote.removePlace(ids.first, ids.second)) {
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
        is RemoteResult.Success -> {
            localUserState.removePlaceFromCollection(ids.first, ids.second)
            RepositoryResult.Success(Unit)
        }
        }
    }

    override suspend fun followUser(userId: String): RepositoryResult<Unit> = mapOrValidation {
        val id = userId.toCanonicalUuid()
        when (val result = socialRemote.follow(id)) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> RepositoryResult.Success(Unit)
        }
    }

    override suspend fun unfollowUser(userId: String): RepositoryResult<Unit> = mapOrValidation {
        val id = userId.toCanonicalUuid()
        when (val result = socialRemote.unfollow(id)) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> RepositoryResult.Success(Unit)
        }
    }

    override suspend fun searchUsers(query: String, page: Int, size: Int): RepositoryResult<UserPage> =
        when (val result = socialRemote.search(query, page, size)) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> mapOrValidation {
                val selfId = currentUser.id
                RepositoryResult.Success(
                    UserPage(
                        items = result.value.content.map { it.toDomain() }.filter { it.id != selfId },
                        page = result.value.page,
                        totalPages = result.value.totalPages,
                        totalElements = result.value.totalElements,
                        hasNext = result.value.hasNext,
                    ),
                )
            }
        }

    override suspend fun loadPublicProfile(userId: String): RepositoryResult<PublicUserProfile> =
        mapOrValidation {
            val id = userId.toCanonicalUuid()
            when (val result = socialRemote.profile(id)) {
                is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
                is RemoteResult.Success -> RepositoryResult.Success(result.value.toDomain())
            }
        }

    override suspend fun loadFollowers(page: Int, size: Int): RepositoryResult<UserPage> =
        loadSocialPage(page, size) { socialRemote.followers(it, size) }

    override suspend fun loadFollowing(page: Int, size: Int): RepositoryResult<UserPage> =
        loadSocialPage(page, size) { socialRemote.following(it, size) }

    override suspend fun loadFriends(page: Int, size: Int): RepositoryResult<UserPage> =
        loadSocialPage(page, size) { socialRemote.friends(it, size) }

    override suspend fun loadOwnerSocialCounts(): RepositoryResult<OwnerSocialCounts> =
        when (val result = socialRemote.meProfile()) {
            is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
            is RemoteResult.Success -> RepositoryResult.Success(
                OwnerSocialCounts(
                    followerCount = result.value.followerCount,
                    followingCount = result.value.followingCount,
                    friendCount = result.value.friendCount,
                ),
            )
        }

    private suspend fun loadSocialPage(
        page: Int,
        size: Int,
        request: suspend (Int) -> RemoteResult<com.emirrkls.phokarta.core.network.model.PageResponseDto<com.emirrkls.phokarta.core.network.model.UserSummaryDto>>,
    ): RepositoryResult<UserPage> = when (val result = request(page)) {
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
        is RemoteResult.Success -> mapOrValidation {
            RepositoryResult.Success(
                UserPage(
                    items = result.value.content.map { it.toDomain() },
                    page = result.value.page,
                    totalPages = result.value.totalPages,
                    totalElements = result.value.totalElements,
                    hasNext = result.value.hasNext,
                ),
            )
        }
    }

    private suspend fun persistCollectionDetail(
        detail: com.emirrkls.phokarta.core.network.model.CollectionDetailDto,
    ): RepositoryResult<Collection> = mapOrValidation {
        val collection = detail.toDomain()
        mergePlaces(detail.places.map { it.place.toDomain() })
        localUserState.upsertCollection(collection)
        RepositoryResult.Success(collection)
    }

    private suspend fun mergePlaces(incoming: List<Place>, persist: Boolean = true) {
        if (incoming.isEmpty()) return
        if (persist) placeCache.upsert(incoming)
        remotePlaces.update { current ->
            val existing = current.associateBy { it.id }
            (current + incoming.map { summary ->
                existing[summary.id]?.let { detail -> detail.preserveRichFieldsFrom(summary) } ?: summary
            }).associateBy { it.id }.values.toList()
        }
    }

    private suspend fun <T> mapOrValidation(block: suspend () -> RepositoryResult<T>): RepositoryResult<T> =
        try {
            block()
        } catch (error: IllegalArgumentException) {
            RepositoryResult.Failure(TravelError.Validation(error.message))
        }
}

private fun Place.preserveRichFieldsFrom(incoming: Place): Place = incoming.copy(
    description = incoming.description.ifEmpty { description },
    photos = incoming.photos.ifEmpty { photos },
    subcategories = incoming.subcategories.ifEmpty { subcategories },
    address = incoming.address.ifEmpty { address },
    ratingBreakdown = incoming.ratingBreakdown.ifEmpty { ratingBreakdown },
)
