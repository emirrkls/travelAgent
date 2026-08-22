package com.emirrkls.phokarta.core.data

import com.emirrkls.phokarta.core.database.dao.CachedPlaceDao
import com.emirrkls.phokarta.core.database.entity.CachedPlaceEntity
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import javax.inject.Inject

interface PlaceCacheDataSource {
    suspend fun getAll(): List<Place>
    suspend fun upsert(places: List<Place>)
}

object NoOpPlaceCacheDataSource : PlaceCacheDataSource {
    override suspend fun getAll(): List<Place> = emptyList()
    override suspend fun upsert(places: List<Place>) = Unit
}

class RoomPlaceCacheDataSource @Inject constructor(
    private val cachedPlaceDao: CachedPlaceDao,
) : PlaceCacheDataSource {
    override suspend fun getAll(): List<Place> = cachedPlaceDao.getAll().map { it.toDomain() }

    override suspend fun upsert(places: List<Place>) {
        if (places.isNotEmpty()) {
            val now = System.currentTimeMillis()
            cachedPlaceDao.upsertAll(places.map { it.toEntity(now) })
        }
    }
}

private fun CachedPlaceEntity.toDomain() = Place(
    id = id,
    name = name,
    description = "",
    category = PlaceCategory.valueOf(category),
    subcategories = emptyList(),
    latitude = latitude,
    longitude = longitude,
    city = city,
    region = region,
    country = country,
    address = "",
    coverImage = coverImage,
    photos = emptyList(),
    priceLevel = priceLevel,
    communityScore = averageScore,
    friendsScore = null,
    similarUsersScore = null,
    ratingCount = ratingCount,
    ratingBreakdown = emptyMap(),
)

private fun Place.toEntity(updatedAtEpochMillis: Long) = CachedPlaceEntity(
    id = id,
    name = name,
    category = category.name,
    coverImage = coverImage,
    city = city,
    region = region,
    country = country,
    latitude = latitude,
    longitude = longitude,
    priceLevel = priceLevel,
    averageScore = communityScore,
    ratingCount = ratingCount,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
