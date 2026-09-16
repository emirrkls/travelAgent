package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.model.Experience
import com.emirrkls.phokarta.core.model.ExperienceFeedLens
import com.emirrkls.phokarta.core.model.ExperiencePage
import com.emirrkls.phokarta.core.model.PlaceAggregateV2
import com.emirrkls.phokarta.core.model.RelationshipV2
import com.emirrkls.phokarta.core.network.RemoteResult
import com.emirrkls.phokarta.core.network.mapper.toCanonicalUuid
import com.emirrkls.phokarta.core.network.mapper.toDomain
import com.emirrkls.phokarta.core.network.mapper.toExperiencePage
import com.emirrkls.phokarta.core.network.source.PlaceRemoteDataSource
import com.emirrkls.phokarta.core.network.source.MediaRemoteDataSource
import com.emirrkls.phokarta.core.network.source.SocialRemoteDataSource
import com.emirrkls.phokarta.core.network.source.VisitRemoteDataSource
import javax.inject.Inject
import javax.inject.Singleton

interface ExperienceFeedGateway {
    suspend fun feed(
        lens: ExperienceFeedLens,
        cursor: String? = null,
        size: Int = 20,
        search: String? = null,
        primary: String? = null,
        vibe: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        radiusMeters: Double? = null,
    ): RepositoryResult<ExperiencePage>

    suspend fun follow(authorId: String): RepositoryResult<RelationshipV2>
    suspend fun unfollow(authorId: String): RepositoryResult<RelationshipV2>
    suspend fun cancelFollowRequest(authorId: String): RepositoryResult<RelationshipV2>
}

@Singleton
class ExperienceRepository @Inject constructor(
    private val visits: VisitRemoteDataSource,
    private val places: PlaceRemoteDataSource,
    private val social: SocialRemoteDataSource,
    private val media: MediaRemoteDataSource,
) : ExperienceFeedGateway {
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
    ): RepositoryResult<ExperiencePage> = map(
        request = {
            visits.experienceFeed(
                lens = lens.name,
                cursor = cursor,
                size = size,
                search = search?.trim()?.takeIf(String::isNotEmpty),
                primary = primary,
                vibe = vibe,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
            )
        },
        transform = { it.toExperiencePage() },
    )

    suspend fun detail(id: String): RepositoryResult<Experience> = map(
        request = { visits.experience(id.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    suspend fun forPlace(
        placeId: String,
        cursor: String? = null,
        size: Int = 20,
        primary: String? = null,
    ): RepositoryResult<ExperiencePage> = map(
        request = { visits.placeExperiences(placeId.toCanonicalUuid(), cursor, size, primary) },
        transform = { it.toExperiencePage() },
    )

    suspend fun forProfile(
        userId: String,
        cursor: String? = null,
        size: Int = 20,
    ): RepositoryResult<ExperiencePage> = map(
        request = { visits.profileExperiences(userId.toCanonicalUuid(), cursor, size) },
        transform = { it.toExperiencePage() },
    )

    suspend fun aggregate(placeId: String): RepositoryResult<PlaceAggregateV2> = map(
        request = { places.aggregateV2(placeId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    override suspend fun follow(authorId: String): RepositoryResult<RelationshipV2> = map(
        request = { social.followV2(authorId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    override suspend fun unfollow(authorId: String): RepositoryResult<RelationshipV2> = map(
        request = { social.unfollowV2(authorId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    override suspend fun cancelFollowRequest(authorId: String): RepositoryResult<RelationshipV2> = map(
        request = { social.cancelFollowRequest(authorId.toCanonicalUuid()) },
        transform = { it.toDomain() },
    )

    suspend fun renewMediaUrl(mediaId: String): RepositoryResult<Pair<String, String>> = map(
        request = { media.access(mediaId.toCanonicalUuid()) },
        transform = { it.url to it.expiresAt },
    )

    private suspend fun <Network, Domain> map(
        request: suspend () -> RemoteResult<Network>,
        transform: (Network) -> Domain,
    ): RepositoryResult<Domain> = when (val result = request()) {
        is RemoteResult.Success -> RepositoryResult.Success(transform(result.value))
        is RemoteResult.Failure -> RepositoryResult.Failure(result.error.toTravelError())
    }
}
