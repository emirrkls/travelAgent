package com.emirrkls.phokarta.core.network.api

import com.emirrkls.phokarta.core.network.model.AuthSessionDto
import com.emirrkls.phokarta.core.network.model.BlockedUserDto
import com.emirrkls.phokarta.core.network.model.CollectionDetailDto
import com.emirrkls.phokarta.core.network.model.CollectionSummaryDto
import com.emirrkls.phokarta.core.network.model.CreateCollectionDto
import com.emirrkls.phokarta.core.network.model.CreateReportDto
import com.emirrkls.phokarta.core.network.model.CreateVisitDto
import com.emirrkls.phokarta.core.network.model.DeleteAccountRequestDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsDto
import com.emirrkls.phokarta.core.network.model.FriendMetricsRequestDto
import com.emirrkls.phokarta.core.network.model.FriendPlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.LoginRequestDto
import com.emirrkls.phokarta.core.network.model.LogoutRequestDto
import com.emirrkls.phokarta.core.network.model.NearbyPlaceDto
import com.emirrkls.phokarta.core.network.model.PageResponseDto
import com.emirrkls.phokarta.core.network.model.PlaceCategoryDto
import com.emirrkls.phokarta.core.network.model.PlaceDetailDto
import com.emirrkls.phokarta.core.network.model.PlaceSummaryDto
import com.emirrkls.phokarta.core.network.model.PublicActivityDto
import com.emirrkls.phokarta.core.network.model.PublicUserProfileDto
import com.emirrkls.phokarta.core.network.model.PublicVisitDto
import com.emirrkls.phokarta.core.network.model.RefreshRequestDto
import com.emirrkls.phokarta.core.network.model.RegisterRequestDto
import com.emirrkls.phokarta.core.network.model.ReportResponseDto
import com.emirrkls.phokarta.core.network.model.SavedPlaceDto
import com.emirrkls.phokarta.core.network.model.TokenPairDto
import com.emirrkls.phokarta.core.network.model.UserProfileDto
import com.emirrkls.phokarta.core.network.model.UserSummaryDto
import com.emirrkls.phokarta.core.network.model.VisitOwnerDto
import com.emirrkls.phokarta.core.network.model.MediaAccessDto
import com.emirrkls.phokarta.core.network.model.MediaStateDto
import com.emirrkls.phokarta.core.network.model.MediaUploadIntentRequestDto
import com.emirrkls.phokarta.core.network.model.MediaUploadIntentResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface AuthApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequestDto): Response<AuthSessionDto>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequestDto): Response<AuthSessionDto>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequestDto): Response<TokenPairDto>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body request: LogoutRequestDto): Response<Unit>
}

interface MeApi {
    @GET("api/v1/me")
    suspend fun profile(): Response<UserProfileDto>

    @POST("api/v1/me/places/friend-metrics")
    suspend fun friendMetrics(
        @Body request: FriendMetricsRequestDto,
    ): Response<List<FriendMetricsDto>>

    @GET("api/v1/me/followers")
    suspend fun followers(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<UserSummaryDto>>

    @GET("api/v1/me/following")
    suspend fun following(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<UserSummaryDto>>

    @GET("api/v1/me/friends")
    suspend fun friends(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<UserSummaryDto>>

    @GET("api/v1/me/blocks")
    suspend fun blockedUsers(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<BlockedUserDto>>

    @PUT("api/v1/me/blocks/{userId}")
    suspend fun block(@Path("userId") userId: String): Response<Unit>

    @DELETE("api/v1/me/blocks/{userId}")
    suspend fun unblock(@Path("userId") userId: String): Response<Unit>

    @HTTP(method = "DELETE", path = "api/v1/me", hasBody = true)
    suspend fun deleteAccount(@Body request: DeleteAccountRequestDto): Response<Unit>
}

interface UserApi {
    @GET("api/v1/users/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<UserSummaryDto>>

    @GET("api/v1/users/{userId}")
    suspend fun profile(@Path("userId") userId: String): Response<PublicUserProfileDto>

    @POST("api/v1/users/{userId}/follow")
    suspend fun follow(@Path("userId") userId: String): Response<Unit>

    @DELETE("api/v1/users/{userId}/follow")
    suspend fun unfollow(@Path("userId") userId: String): Response<Unit>
}

interface PlaceApi {
    @GET("api/v1/places")
    suspend fun list(
        @Query("category") category: PlaceCategoryDto? = null,
        @Query("city") city: String? = null,
        @Query("search") search: String? = null,
        @Query("minRating") minRating: Double? = null,
        @Query("sort") sort: String = "averageScore,desc",
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<PlaceSummaryDto>>

    @GET("api/v1/places/nearby")
    suspend fun nearby(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("radiusMeters") radiusMeters: Double = 5_000.0,
        @Query("category") category: PlaceCategoryDto? = null,
        @Query("minRating") minRating: Double? = null,
        @Query("limit") limit: Int = 50,
    ): Response<List<NearbyPlaceDto>>

    @GET("api/v1/places/bounds")
    suspend fun bounds(
        @Query("west") west: Double,
        @Query("south") south: Double,
        @Query("east") east: Double,
        @Query("north") north: Double,
        @Query("category") category: PlaceCategoryDto? = null,
        @Query("minRating") minRating: Double? = null,
        @Query("limit") limit: Int = 50,
    ): Response<List<PlaceSummaryDto>>

    @GET("api/v1/places/{id}")
    suspend fun detail(@Path("id") id: String): Response<PlaceDetailDto>
}

interface VisitApi {
    @POST("api/v1/visits")
    suspend fun create(@Body request: CreateVisitDto): Response<VisitOwnerDto>

    @GET("api/v1/me/visits")
    suspend fun ownerVisits(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<VisitOwnerDto>>

    @GET("api/v1/places/{placeId}/reviews")
    suspend fun publicReviews(
        @Path("placeId") placeId: String,
        @Query("scope") scope: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<PublicVisitDto>>

    @GET("api/v1/activity")
    suspend fun publicActivity(
        @Query("scope") scope: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<PublicActivityDto>>

    @GET("api/v1/places/{placeId}/friends-summary")
    suspend fun friendsSummary(
        @Path("placeId") placeId: String,
    ): Response<FriendPlaceSummaryDto>
}

interface MediaApi {
    @POST("api/v1/me/media/upload-intents")
    suspend fun createUploadIntent(
        @Body request: MediaUploadIntentRequestDto,
    ): Response<MediaUploadIntentResponseDto>

    @POST("api/v1/me/media/{id}/confirm")
    suspend fun confirm(@Path("id") mediaId: String): Response<MediaStateDto>

    @GET("api/v1/media/{id}/access")
    suspend fun access(@Path("id") mediaId: String): Response<MediaAccessDto>
}

interface SavedPlaceApi {
    @GET("api/v1/me/saved-places")
    suspend fun list(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<SavedPlaceDto>>

    @POST("api/v1/me/saved-places/{placeId}")
    suspend fun save(
        @Path("placeId") placeId: String,
    ): Response<SavedPlaceDto>

    @DELETE("api/v1/me/saved-places/{placeId}")
    suspend fun remove(
        @Path("placeId") placeId: String,
    ): Response<Unit>
}

interface CollectionApi {
    @GET("api/v1/me/collections")
    suspend fun list(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
    ): Response<PageResponseDto<CollectionSummaryDto>>

    @POST("api/v1/me/collections")
    suspend fun create(
        @Body request: CreateCollectionDto,
    ): Response<CollectionDetailDto>

    @GET("api/v1/collections/{collectionId}")
    suspend fun detail(
        @Path("collectionId") collectionId: String,
    ): Response<CollectionDetailDto>

    @POST("api/v1/collections/{collectionId}/places/{placeId}")
    suspend fun addPlace(
        @Path("collectionId") collectionId: String,
        @Path("placeId") placeId: String,
    ): Response<CollectionDetailDto>

    @DELETE("api/v1/collections/{collectionId}/places/{placeId}")
    suspend fun removePlace(
        @Path("collectionId") collectionId: String,
        @Path("placeId") placeId: String,
    ): Response<Unit>
}

interface ReportApi {
    @POST("api/v1/reports")
    suspend fun submit(@Body request: CreateReportDto): Response<ReportResponseDto>
}
