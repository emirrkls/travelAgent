package com.emirrkls.phokarta.core.network.source

import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.api.CollectionApi
import com.emirrkls.phokarta.core.network.api.MeApi
import com.emirrkls.phokarta.core.network.api.PlaceApi
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
import com.emirrkls.phokarta.core.network.model.FriendMetricsDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsRequestDto
import com.emirrkls.phokarta.core.network.model.NearbyPlaceDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
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
}

class RetrofitPlaceRemoteDataSource @Inject constructor(
    private val api: PlaceApi,
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
}

interface VisitRemoteDataSource {
    suspend fun create(request: CreateVisitDto): RemoteResult<VisitOwnerDto>
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
}

class RetrofitSocialRemoteDataSource @Inject constructor(
    private val userApi: UserApi,
    private val meApi: MeApi,
    private val reportApi: ReportApi,
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
}
