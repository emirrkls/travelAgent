package com.emirrkls.phokarta

import com.emirrkls.phokarta.core.data.MockPlaceCatalogDataSource
import com.emirrkls.phokarta.core.data.PlacePage
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.core.model.ActivityFeedPage
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.FriendPlaceSummary
import com.emirrkls.phokarta.core.model.NearbyPlace
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.PublicReviewPage
import com.emirrkls.phokarta.core.model.PublicUserProfile
import com.emirrkls.phokarta.core.model.OwnerSocialCounts
import com.emirrkls.phokarta.core.model.RelationshipState
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.UserPage
import com.emirrkls.phokarta.core.model.UserSummary
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.VisitStateLogic
import com.emirrkls.phokarta.core.data.TravelError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

open class TestTravelRepository : TravelRepository {
    val places = MutableStateFlow(MockPlaceCatalogDataSource.mockPlaces.take(4))
    val visits = MutableStateFlow<List<Visit>>(emptyList())
    val saved = MutableStateFlow<Set<String>>(emptySet())
    val savedFriendMetrics = MutableStateFlow<Map<String, SavedFriendMetrics>>(emptyMap())
    val collections = MutableStateFlow<List<Collection>>(emptyList())
    val publicReviewsByPlace = MutableStateFlow<Map<String, List<PublicReview>>>(emptyMap())
    val friendReviewsByPlace = MutableStateFlow<Map<String, List<PublicReview>>>(emptyMap())
    val activityItems = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val friendsActivityItems = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val friendSummariesByPlace = MutableStateFlow<Map<String, FriendPlaceSummary>>(emptyMap())
    var publicReviewsError: TravelError? = null
    var friendReviewsError: TravelError? = null
    var activityError: TravelError? = null
    var friendsActivityError: TravelError? = null
    var activityLoadMoreError: TravelError? = null
    var friendsActivityLoadMoreError: TravelError? = null
    var friendSummaryError: TravelError? = null
    val requestedActivityPages = mutableListOf<Int>()
    val requestedActivityScopes = mutableListOf<ActivityScope>()
    val requestedReviewScopes = mutableListOf<ActivityScope>()
    override val currentUser: User = MockPlaceCatalogDataSource().currentUser

    override fun observePlaces(): Flow<List<Place>> = places
    override fun observeVisits(): Flow<List<Visit>> = visits
    override fun observeVisitedPlaceIds(): Flow<Set<String>> = visits.map(VisitStateLogic::visitedPlaceIds)
    override fun observeSavedPlaceIds(): Flow<Set<String>> = saved
    override fun observeSavedFriendMetrics(): Flow<Map<String, SavedFriendMetrics>> = savedFriendMetrics
    override fun observeCollections(): Flow<List<Collection>> = collections
    override suspend fun getPlace(id: String) = places.value.firstOrNull { it.id == id }
    override suspend fun getCollection(id: String) = collections.value.firstOrNull { it.id == id }
    override suspend fun loadActivityPage(
        scope: ActivityScope,
        page: Int,
        size: Int,
    ): RepositoryResult<ActivityFeedPage> {
        requestedActivityPages += page
        requestedActivityScopes += scope
        val pageError = if (scope == ActivityScope.FRIENDS) {
            if (page == 0) friendsActivityError else friendsActivityLoadMoreError
        } else {
            if (page == 0) activityError else activityLoadMoreError
        }
        pageError?.let { return RepositoryResult.Failure(it) }
        val all = if (scope == ActivityScope.FRIENDS) friendsActivityItems.value else activityItems.value
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        val slice = all.subList(from, to)
        val totalPages = if (all.isEmpty()) 0 else ((all.size + size - 1) / size)
        return RepositoryResult.Success(
            ActivityFeedPage(
                items = slice,
                page = page,
                totalPages = totalPages,
                totalElements = all.size.toLong(),
                hasNext = to < all.size,
            ),
        )
    }
    override suspend fun listPlaces(category: PlaceCategory?, city: String?, search: String?, minRating: Double?, sort: String, page: Int, size: Int): RepositoryResult<PlacePage> =
        RepositoryResult.Success(PlacePage(places.value, page, 1, places.value.size.toLong(), false))
    override suspend fun refreshCatalog() = listPlaces(size = 100)
    override suspend fun refreshBounds(west: Double, south: Double, east: Double, north: Double, category: PlaceCategory?, minRating: Double?): RepositoryResult<List<Place>> =
        RepositoryResult.Success(places.value)
    override suspend fun nearby(latitude: Double, longitude: Double, radiusMeters: Double, category: PlaceCategory?, minRating: Double?): RepositoryResult<List<NearbyPlace>> =
        RepositoryResult.Success(emptyList())
    override suspend fun refreshPlaceDetail(id: String): RepositoryResult<Place> =
        getPlace(id)?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(TravelError.NotFound())
    override suspend fun refreshPublicReviews(
        placeId: String,
        scope: ActivityScope,
        page: Int,
        size: Int,
    ): RepositoryResult<PublicReviewPage> {
        requestedReviewScopes += scope
        val error = if (scope == ActivityScope.FRIENDS) friendReviewsError else publicReviewsError
        error?.let { return RepositoryResult.Failure(it) }
        val all = if (scope == ActivityScope.FRIENDS) {
            friendReviewsByPlace.value[placeId].orEmpty()
        } else {
            publicReviewsByPlace.value[placeId].orEmpty()
        }
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        val slice = all.subList(from, to)
        val totalPages = if (all.isEmpty()) 0 else ((all.size + size - 1) / size)
        return RepositoryResult.Success(
            PublicReviewPage(
                reviews = slice,
                page = page,
                totalPages = totalPages,
                totalElements = all.size.toLong(),
                hasNext = to < all.size,
            ),
        )
    }

    override suspend fun loadFriendPlaceSummary(placeId: String): RepositoryResult<FriendPlaceSummary> {
        friendSummaryError?.let { return RepositoryResult.Failure(it) }
        return friendSummariesByPlace.value[placeId]?.let { RepositoryResult.Success(it) }
            ?: RepositoryResult.Success(FriendPlaceSummary(null, 0, emptyList()))
    }
    override suspend fun refreshOwnerVisits(page: Int, size: Int): RepositoryResult<List<Visit>> = RepositoryResult.Success(visits.value)
    override suspend fun refreshSaved(page: Int, size: Int): RepositoryResult<Set<String>> = RepositoryResult.Success(saved.value)
    override suspend fun refreshCollections(page: Int, size: Int): RepositoryResult<List<Collection>> = RepositoryResult.Success(collections.value)
    override suspend fun refreshCollectionDetail(id: String): RepositoryResult<Collection> =
        getCollection(id)?.let { RepositoryResult.Success(it) } ?: RepositoryResult.Failure(TravelError.NotFound())
    override suspend fun publishVisit(visit: Visit): RepositoryResult<Visit> {
        visits.value = visits.value + visit
        return RepositoryResult.Success(visit)
    }
    override suspend fun toggleSaved(placeId: String): RepositoryResult<Boolean> {
        val value = placeId !in saved.value
        saved.value = if (value) saved.value + placeId else saved.value - placeId
        return RepositoryResult.Success(value)
    }
    override suspend fun saveCollection(collection: Collection): RepositoryResult<Collection> = RepositoryResult.Success(collection)
    override suspend fun addPlaceToCollection(collectionId: String, placeId: String): RepositoryResult<Collection> =
        RepositoryResult.Failure(TravelError.NotFound())
    override suspend fun removePlaceFromCollection(collectionId: String, placeId: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

    val publicProfiles = mutableMapOf<String, PublicUserProfile>()
    val searchableUsers = mutableListOf<UserSummary>()
    val followers = mutableListOf<UserSummary>()
    val following = mutableListOf<UserSummary>()
    val friends = mutableListOf<UserSummary>()
    var followError: TravelError? = null
    var searchError: TravelError? = null
    var profileError: TravelError? = null
    var socialListError: TravelError? = null
    val followCalls = mutableListOf<String>()
    val unfollowCalls = mutableListOf<String>()

    override suspend fun followUser(userId: String): RepositoryResult<Unit> {
        followError?.let { return RepositoryResult.Failure(it) }
        followCalls += userId
        publicProfiles[userId]?.let { profile ->
            val rel = profile.relationship ?: RelationshipState(false, false)
            publicProfiles[userId] = profile.copy(
                relationship = RelationshipState(
                    isFollowing = true,
                    followsYou = rel.followsYou,
                ),
                followerCount = profile.followerCount + if (!rel.isFollowing) 1 else 0,
            )
        }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun unfollowUser(userId: String): RepositoryResult<Unit> {
        followError?.let { return RepositoryResult.Failure(it) }
        unfollowCalls += userId
        publicProfiles[userId]?.let { profile ->
            val rel = profile.relationship ?: RelationshipState(false, false)
            publicProfiles[userId] = profile.copy(
                relationship = RelationshipState(
                    isFollowing = false,
                    followsYou = rel.followsYou,
                ),
                followerCount = (profile.followerCount - if (rel.isFollowing) 1 else 0).coerceAtLeast(0),
            )
        }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun searchUsers(query: String, page: Int, size: Int): RepositoryResult<UserPage> {
        searchError?.let { return RepositoryResult.Failure(it) }
        val matched = searchableUsers.filter {
            it.id != currentUser.id &&
                (it.username.contains(query, true) || it.displayName.contains(query, true))
        }
        return pageUsers(matched, page, size)
    }

    override suspend fun loadPublicProfile(userId: String): RepositoryResult<PublicUserProfile> {
        profileError?.let { return RepositoryResult.Failure(it) }
        return publicProfiles[userId]?.let { RepositoryResult.Success(it) }
            ?: RepositoryResult.Failure(TravelError.NotFound())
    }

    override suspend fun loadFollowers(page: Int, size: Int): RepositoryResult<UserPage> =
        socialListError?.let { RepositoryResult.Failure(it) } ?: pageUsers(followers, page, size)

    override suspend fun loadFollowing(page: Int, size: Int): RepositoryResult<UserPage> =
        socialListError?.let { RepositoryResult.Failure(it) } ?: pageUsers(following, page, size)

    override suspend fun loadFriends(page: Int, size: Int): RepositoryResult<UserPage> =
        socialListError?.let { RepositoryResult.Failure(it) } ?: pageUsers(friends, page, size)

    var ownerSocialCounts = OwnerSocialCounts(0, 0, 0)
    var ownerSocialCountsError: TravelError? = null

    override suspend fun loadOwnerSocialCounts(): RepositoryResult<OwnerSocialCounts> {
        ownerSocialCountsError?.let { return RepositoryResult.Failure(it) }
        return RepositoryResult.Success(ownerSocialCounts)
    }

    private fun pageUsers(all: List<UserSummary>, page: Int, size: Int): RepositoryResult<UserPage> {
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        val totalPages = if (all.isEmpty()) 0 else ((all.size + size - 1) / size)
        return RepositoryResult.Success(
            UserPage(
                items = all.subList(from, to),
                page = page,
                totalPages = totalPages,
                totalElements = all.size.toLong(),
                hasNext = to < all.size,
            ),
        )
    }
}
