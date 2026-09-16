package com.emirrkls.phokarta.core.network.source

import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.api.CollectionApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.api.PlaceApi
import com.emirrkls.phokarta.core.network.api.PrivacyV2Api
import com.emirrkls.phokarta.core.network.api.ReportApi
import com.emirrkls.phokarta.core.network.api.SavedPlaceApi
import com.emirrkls.phokarta.core.network.api.UserApi
import com.emirrkls.phokarta.core.network.api.VisitApi
import com.emirrkls.phokarta.core.network.model.BlockedUserDto
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.CollectionSummaryDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateReportDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.CreateExperienceV2Dto
import com.emirrkls.phokarta.core.network.model.ExperienceV2Dto
import com.emirrkls.phokarta.core.network.model.FriendMetricsDto
import com.emirrkls.phokarta.core.network.model.CapabilitiesV2Dto
import com.emirrkls.phokarta.core.network.model.FollowRequestV2Dto
import com.emirrkls.phokarta.core.network.model.PlaceAggregateV2Dto
import com.emirrkls.phokarta.core.network.model.ProfileV2Dto
import com.emirrkls.phokarta.core.network.model.ProfileVisibilityUpdateDto
import com.emirrkls.phokarta.core.network.model.RelationshipV2Dto
import com.emirrkls.phokarta.core.network.model.FriendMetricsRequestDto
import com.emirrkls.phokarta.core.network.model.NearbyPlaceDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PolicyAcceptanceRequestDto
import com.emirrkls.phokarta.core.network.model.PolicyStatusDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicActivityDto
import com.emirrkls.phokarta.core.network.model.PublicUserProfileDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.ReportReasonDto
import com.emirrkls.phokarta.core.network.model.ReportResponseDto
import com.emirrkls.phokarta.core.network.model.ReportTargetTypeDto
import com.emirrkls.phokarta.core.network.model.SavedPlaceDto
import com.emirrkls.phokarta.core.network.model.UserProfileDto
import com.emirrkls.phokarta.core.network.model.UserSummaryDto
import com.emirrkls.phokarta.core.network.model.VisitOwnerDto
import com.emirrkls.phokarta.core.network.safeApiCall
import com.emirrkls.phokarta.core.network.safeUnitApiCall
import javax.inject.Inject
import kotlinx.serialization.json.Json

interface PlaceRemoteDataSource {
    suspend fun list(
        category: PlaceCategoryDto? = null,
        city: String? = null,
        search: String? = null,
        minRating: Double? = null,
        sort: String = "averageScore,desc",
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<PlaceSummaryDto>>

    suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 5_000.0,
        category: PlaceCategoryDto? = null,
        minRating: Double? = null,
        limit: Int = 50,
    ): RemoteResult<List<NearbyPlaceDto>>

    suspend fun bounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: PlaceCategoryDto? = null,
        minRating: Double? = null,
        limit: Int = 50,
    ): RemoteResult<List<PlaceSummaryDto>>

    suspend fun detail(id: String): RemoteResult<PlaceDetailDto>
    suspend fun aggregateV2(id: String): RemoteResult<PlaceAggregateV2Dto> =
        throw UnsupportedOperationException("V2 aggregate source is not configured")
}

class RetrofitPlaceRemoteDataSource @Inject constructor(
    private val api: PlaceApi,
    private val privacyV2Api: PrivacyV2Api,
    private val json: Json,
) : PlaceRemoteDataSource {
    override suspend fun list(
        category: PlaceCategoryDto?,
        city: String?,
        search: String?,
        minRating: Double?,
        sort: String,
        page: Int,
        size: Int,
    ) = safeApiCall(json) { api.list(category, city, search, minRating, sort, page, size) }

    override suspend fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        category: PlaceCategoryDto?,
        minRating: Double?,
        limit: Int,
    ) = safeApiCall(json) {
        api.nearby(latitude, longitude, radiusMeters, category, minRating, limit)
    }

    override suspend fun bounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: PlaceCategoryDto?,
        minRating: Double?,
        limit: Int,
    ) = safeApiCall(json) {
        api.bounds(west, south, east, north, category, minRating, limit)
    }

    override suspend fun detail(id: String) = safeApiCall(json) { api.detail(id) }
    override suspend fun aggregateV2(id: String) = safeApiCall(json) { privacyV2Api.placeAggregate(id) }
}

interface VisitRemoteDataSource {
    suspend fun create(request: CreateVisitDto): RemoteResult<VisitOwnerDto>
    suspend fun createExperience(request: CreateExperienceV2Dto): RemoteResult<ExperienceV2Dto> =
        RemoteResult.Failure(com.emirrkls.phokarta.core.network.NetworkError.Unknown())
    suspend fun ownerVisits(
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<VisitOwnerDto>>

    suspend fun publicReviews(
        placeId: String,
        scope: String? = null,
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<PublicVisitDto>>

    suspend fun publicActivity(
        scope: String? = null,
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<PublicActivityDto>>

    suspend fun friendsSummary(placeId: String): RemoteResult<FriendPlaceSummaryDto>
}

class RetrofitVisitRemoteDataSource @Inject constructor(
    private val api: VisitApi,
    private val json: Json,
) : VisitRemoteDataSource {
    override suspend fun create(request: CreateVisitDto) =
        safeApiCall(json) { api.create(request) }

    override suspend fun createExperience(request: CreateExperienceV2Dto) =
        safeApiCall(json) { api.createExperience(request) }

    override suspend fun ownerVisits(page: Int, size: Int) =
        safeApiCall(json) { api.ownerVisits(page, size) }

    override suspend fun publicReviews(placeId: String, scope: String?, page: Int, size: Int) =
        safeApiCall(json) { api.publicReviews(placeId, scope, page, size) }

    override suspend fun publicActivity(scope: String?, page: Int, size: Int) =
        safeApiCall(json) { api.publicActivity(scope, page, size) }

    override suspend fun friendsSummary(placeId: String) =
        safeApiCall(json) { api.friendsSummary(placeId) }
}

interface SavedPlaceRemoteDataSource {
    suspend fun list(
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<SavedPlaceDto>>

    suspend fun save(placeId: String): RemoteResult<SavedPlaceDto>
    suspend fun remove(placeId: String): RemoteResult<Unit>
}

class RetrofitSavedPlaceRemoteDataSource @Inject constructor(
    private val api: SavedPlaceApi,
    private val json: Json,
) : SavedPlaceRemoteDataSource {
    override suspend fun list(page: Int, size: Int) =
        safeApiCall(json) { api.list(page, size) }

    override suspend fun save(placeId: String) =
        safeApiCall(json) { api.save(placeId) }

    override suspend fun remove(placeId: String) =
        safeUnitApiCall(json) { api.remove(placeId) }
}

interface CollectionRemoteDataSource {
    suspend fun list(
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<CollectionSummaryDto>>

    suspend fun create(
        request: CreateCollectionDto,
    ): RemoteResult<CollectionDetailDto>

    suspend fun detail(collectionId: String): RemoteResult<CollectionDetailDto>
    suspend fun addPlace(
        collectionId: String,
        placeId: String,
    ): RemoteResult<CollectionDetailDto>

    suspend fun removePlace(
        collectionId: String,
        placeId: String,
    ): RemoteResult<Unit>
}

class RetrofitCollectionRemoteDataSource @Inject constructor(
    private val api: CollectionApi,
    private val json: Json,
) : CollectionRemoteDataSource {
    override suspend fun list(page: Int, size: Int) =
        safeApiCall(json) { api.list(page, size) }

    override suspend fun create(request: CreateCollectionDto) =
        safeApiCall(json) { api.create(request) }

    override suspend fun detail(collectionId: String) =
        safeApiCall(json) { api.detail(collectionId) }

    override suspend fun addPlace(collectionId: String, placeId: String) =
        safeApiCall(json) { api.addPlace(collectionId, placeId) }

    override suspend fun removePlace(collectionId: String, placeId: String) =
        safeUnitApiCall(json) { api.removePlace(collectionId, placeId) }
}

interface SocialRemoteDataSource {
    suspend fun search(
        query: String,
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<UserSummaryDto>>

    suspend fun profile(userId: String): RemoteResult<PublicUserProfileDto>
    suspend fun follow(userId: String): RemoteResult<Unit>
    suspend fun unfollow(userId: String): RemoteResult<Unit>
    suspend fun followers(page: Int = 0, size: Int = 20): RemoteResult<PageResponseDto<UserSummaryDto>>
    suspend fun following(page: Int = 0, size: Int = 20): RemoteResult<PageResponseDto<UserSummaryDto>>
    suspend fun friends(page: Int = 0, size: Int = 20): RemoteResult<PageResponseDto<UserSummaryDto>>
    suspend fun meProfile(): RemoteResult<UserProfileDto>
    suspend fun friendMetrics(placeIds: List<String>): RemoteResult<List<FriendMetricsDto>>
    suspend fun block(userId: String): RemoteResult<Unit>
    suspend fun unblock(userId: String): RemoteResult<Unit>
    suspend fun blockedUsers(page: Int = 0, size: Int = 20): RemoteResult<PageResponseDto<BlockedUserDto>>
    suspend fun submitReport(
        targetType: ReportTargetTypeDto,
        targetId: String,
        reason: ReportReasonDto,
        details: String?,
    ): RemoteResult<ReportResponseDto>
    suspend fun policyStatus(): RemoteResult<PolicyStatusDto>
    suspend fun acceptPolicy(policyVersion: String): RemoteResult<PolicyStatusDto>
    suspend fun capabilitiesV2(): RemoteResult<CapabilitiesV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun profileV2(userId: String): RemoteResult<ProfileV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun followV2(userId: String): RemoteResult<RelationshipV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun unfollowV2(userId: String): RemoteResult<RelationshipV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun cancelFollowRequest(userId: String): RemoteResult<RelationshipV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun followRequests(
        page: Int = 0,
        size: Int = 20,
    ): RemoteResult<PageResponseDto<FollowRequestV2Dto>> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun approveFollowRequest(requestId: String): RemoteResult<RelationshipV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun rejectFollowRequest(requestId: String): RemoteResult<RelationshipV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
    suspend fun updateProfileVisibility(visibility: String): RemoteResult<ProfileV2Dto> =
        throw UnsupportedOperationException("V2 privacy source is not configured")
}

class RetrofitSocialRemoteDataSource @Inject constructor(
    private val userApi: UserApi,
    private val meApi: MeApi,
    private val reportApi: ReportApi,
    private val privacyV2Api: PrivacyV2Api,
    private val json: Json,
) : SocialRemoteDataSource {
    override suspend fun search(query: String, page: Int, size: Int) =
        safeApiCall(json) { userApi.search(query, page, size) }

    override suspend fun profile(userId: String) =
        safeApiCall(json) { userApi.profile(userId) }

    override suspend fun follow(userId: String) =
        safeUnitApiCall(json) { userApi.follow(userId) }

    override suspend fun unfollow(userId: String) =
        safeUnitApiCall(json) { userApi.unfollow(userId) }

    override suspend fun followers(page: Int, size: Int) =
        safeApiCall(json) { meApi.followers(page, size) }

    override suspend fun following(page: Int, size: Int) =
        safeApiCall(json) { meApi.following(page, size) }

    override suspend fun friends(page: Int, size: Int) =
        safeApiCall(json) { meApi.friends(page, size) }

    override suspend fun meProfile() =
        safeApiCall(json) { meApi.profile() }

    override suspend fun friendMetrics(placeIds: List<String>) =
        safeApiCall(json) { meApi.friendMetrics(FriendMetricsRequestDto(placeIds)) }

    override suspend fun block(userId: String) =
        safeUnitApiCall(json) { meApi.block(userId) }

    override suspend fun unblock(userId: String) =
        safeUnitApiCall(json) { meApi.unblock(userId) }

    override suspend fun blockedUsers(page: Int, size: Int) =
        safeApiCall(json) { meApi.blockedUsers(page, size) }

    override suspend fun submitReport(
        targetType: ReportTargetTypeDto,
        targetId: String,
        reason: ReportReasonDto,
        details: String?,
    ) = safeApiCall(json) {
        reportApi.submit(CreateReportDto(targetType, targetId, reason, details))
    }

    override suspend fun policyStatus() =
        safeApiCall(json) { meApi.policyStatus() }

    override suspend fun acceptPolicy(policyVersion: String) =
        safeApiCall(json) { meApi.acceptPolicy(PolicyAcceptanceRequestDto(policyVersion)) }

    override suspend fun capabilitiesV2() =
        safeApiCall(json) { privacyV2Api.capabilities() }

    override suspend fun profileV2(userId: String) =
        safeApiCall(json) { privacyV2Api.profile(userId) }

    override suspend fun followV2(userId: String) =
        safeApiCall(json) { privacyV2Api.follow(userId) }

    override suspend fun unfollowV2(userId: String) =
        safeApiCall(json) { privacyV2Api.unfollow(userId) }

    override suspend fun cancelFollowRequest(userId: String) =
        safeApiCall(json) { privacyV2Api.cancelFollowRequest(userId) }

    override suspend fun followRequests(page: Int, size: Int) =
        safeApiCall(json) { privacyV2Api.followRequests(page, size) }

    override suspend fun approveFollowRequest(requestId: String) =
        safeApiCall(json) { privacyV2Api.approveFollowRequest(requestId) }

    override suspend fun rejectFollowRequest(requestId: String) =
        safeApiCall(json) { privacyV2Api.rejectFollowRequest(requestId) }

    override suspend fun updateProfileVisibility(visibility: String) =
        safeApiCall(json) { privacyV2Api.updateProfileVisibility(ProfileVisibilityUpdateDto(visibility)) }
}
