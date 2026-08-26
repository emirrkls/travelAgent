package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.ActivityFeedPage
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.model.BlockedUserPage
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.FriendPlaceSummary
import com.emirrkls.phokarta.core.model.NearbyPlace
import com.emirrkls.phokarta.core.model.OwnerSocialCounts
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.core.model.PolicyStatus
import com.emirrkls.phokarta.core.model.PublicReviewPage
import com.emirrkls.phokarta.core.model.PublicUserProfile
import com.emirrkls.phokarta.core.model.ReportReason
import com.emirrkls.phokarta.core.model.ReportTargetType
import com.emirrkls.phokarta.core.model.SavedFriendMetrics
import com.emirrkls.phokarta.core.model.SubmittedReport
import com.emirrkls.phokarta.core.model.User
import com.emirrkls.phokarta.core.model.UserPage
import com.emirrkls.phokarta.core.model.Visit
import kotlinx.coroutines.flow.Flow

data class PlacePage(
    val places: List<Place>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)

interface TravelRepository {
    val currentUser: User
    fun observePlaces(): Flow<List<Place>>
    fun observeVisits(): Flow<List<Visit>>
    fun observeVisitedPlaceIds(): Flow<Set<String>>
    fun observeSavedPlaceIds(): Flow<Set<String>>
    fun observeSavedFriendMetrics(): Flow<Map<String, SavedFriendMetrics>>
    fun observeCollections(): Flow<List<Collection>>
    suspend fun getPlace(id: String): Place?
    suspend fun getCollection(id: String): Collection?
    suspend fun loadActivityPage(
        scope: ActivityScope = ActivityScope.COMMUNITY,
        page: Int = 0,
        size: Int = 20,
    ): RepositoryResult<ActivityFeedPage>
    suspend fun listPlaces(
        category: com.emirrkls.phokarta.core.model.PlaceCategory? = null,
        city: String? = null,
        search: String? = null,
        minRating: Double? = null,
        sort: String = "averageScore,desc",
        page: Int = 0,
        size: Int = 20,
    ): RepositoryResult<PlacePage>
    suspend fun refreshCatalog(): RepositoryResult<PlacePage>
    suspend fun refreshBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: PlaceCategory? = null,
        minRating: Double? = null,
    ): RepositoryResult<List<Place>>
    suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 5_000.0,
        category: PlaceCategory? = null,
        minRating: Double? = null,
    ): RepositoryResult<List<NearbyPlace>>
    suspend fun refreshPlaceDetail(id: String): RepositoryResult<Place>
    suspend fun refreshPublicReviews(
        placeId: String,
        scope: ActivityScope = ActivityScope.COMMUNITY,
        page: Int = 0,
        size: Int = 20,
    ): RepositoryResult<PublicReviewPage>
    suspend fun loadFriendPlaceSummary(placeId: String): RepositoryResult<FriendPlaceSummary>
    suspend fun loadFriendMetrics(placeIds: List<String>): RepositoryResult<Map<String, SavedFriendMetrics>>
    suspend fun refreshOwnerVisits(page: Int = 0, size: Int = 50): RepositoryResult<List<Visit>>
    suspend fun refreshSaved(page: Int = 0, size: Int = 100): RepositoryResult<Set<String>>
    suspend fun refreshCollections(page: Int = 0, size: Int = 100): RepositoryResult<List<Collection>>
    suspend fun refreshCollectionDetail(id: String): RepositoryResult<Collection>
    suspend fun publishVisit(visit: Visit): RepositoryResult<Visit>
    suspend fun toggleSaved(placeId: String): RepositoryResult<Boolean>
    suspend fun saveCollection(collection: Collection): RepositoryResult<Collection>
    suspend fun addPlaceToCollection(collectionId: String, placeId: String): RepositoryResult<Collection>
    suspend fun removePlaceFromCollection(collectionId: String, placeId: String): RepositoryResult<Unit>
    suspend fun followUser(userId: String): RepositoryResult<Unit>
    suspend fun unfollowUser(userId: String): RepositoryResult<Unit>
    suspend fun searchUsers(query: String, page: Int = 0, size: Int = 20): RepositoryResult<UserPage>
    suspend fun loadPublicProfile(userId: String): RepositoryResult<PublicUserProfile>
    suspend fun loadFollowers(page: Int = 0, size: Int = 20): RepositoryResult<UserPage>
    suspend fun loadFollowing(page: Int = 0, size: Int = 20): RepositoryResult<UserPage>
    suspend fun loadFriends(page: Int = 0, size: Int = 20): RepositoryResult<UserPage>
    suspend fun loadOwnerSocialCounts(): RepositoryResult<OwnerSocialCounts>
    suspend fun blockUser(userId: String): RepositoryResult<Unit>
    suspend fun unblockUser(userId: String): RepositoryResult<Unit>
    suspend fun loadBlockedUsers(page: Int = 0, size: Int = 20): RepositoryResult<BlockedUserPage>
    suspend fun submitReport(
        targetType: ReportTargetType,
        targetId: String,
        reason: ReportReason,
        details: String?,
    ): RepositoryResult<SubmittedReport>
    suspend fun invalidateAfterBlock()
    suspend fun policyStatus(): RepositoryResult<PolicyStatus>
    suspend fun acceptPolicy(policyVersion: String): RepositoryResult<PolicyStatus>
}
