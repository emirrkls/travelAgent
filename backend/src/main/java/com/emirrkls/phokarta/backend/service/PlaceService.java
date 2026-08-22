package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.NearbyPlaceResponse;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PlaceDetailResponse;
import com.emirrkls.phokarta.backend.api.dto.PlaceSummaryResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.api.mapper.PlaceMapper;
import com.emirrkls.phokarta.backend.api.mapper.VisitMapper;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PlaceService {
    private static final Set<String> SORTS = Set.of(
            "name,asc", "name,desc", "createdAt,asc", "createdAt,desc",
            "averageScore,asc", "averageScore,desc", "ratingCount,asc", "ratingCount,desc");
    private final PlaceRepository places;
    private final VisitRepository visits;
    private final VisitDimensionScoreRepository dimensionScores;
    private final PlaceMapper placeMapper;
    private final VisitMapper visitMapper;

    public PlaceService(PlaceRepository places, VisitRepository visits,
                        VisitDimensionScoreRepository dimensionScores,
                        PlaceMapper placeMapper, VisitMapper visitMapper) {
        this.places = places;
        this.visits = visits;
        this.dimensionScores = dimensionScores;
        this.placeMapper = placeMapper;
        this.visitMapper = visitMapper;
    }

    public PageResponse<PlaceSummaryResponse> list(
            PlaceCategory category, String city, String search, Double minRating,
            String sort, int page, int size) {
        String safeSort = normalizeSort(sort);
        Page<PlaceRepository.SummaryRow> result = places.search(
                category == null ? null : category.name(), blankToNull(city), blankToNull(search),
                minRating, safeSort, PageRequest.of(page, size));
        return PageResponse.from(result, placeMapper::toSummary);
    }

    public PlaceDetailResponse detail(UUID id) {
        Place place = require(id);
        VisitRepository.ScoreAggregate aggregate = visits.aggregate(id);
        long ratingCount = aggregate == null ? 0 : aggregate.getCount();
        Double averageScore = ratingCount == 0 ? null : aggregate.getAverage();
        var dimensions = dimensionScores.aggregateForPlace(id).stream()
                .map(row -> new PlaceDetailResponse.DimensionAggregateResponse(
                        row.getDimensionKey(), row.getAverage()))
                .toList();
        var recent = visits.findRecent(id, Visibility.PUBLIC, PageRequest.of(0, 5)).stream()
                .map(visitMapper::toPublic).toList();
        var point = place.getLocation();
        return new PlaceDetailResponse(place.getId(), place.getName(), place.getDescription(),
                place.getCategory(), place.getSubcategories(), point.getY(), point.getX(),
                place.getCity(), place.getRegion(), place.getCountry(), place.getAddress(),
                place.getCoverImage(), place.getPhotos(), place.getPriceLevel(), averageScore,
                ratingCount, dimensions, recent);
    }

    public List<NearbyPlaceResponse> nearby(double lat, double lon, double radius,
                                             PlaceCategory category, Double minRating, int limit) {
        validateCoordinate(lat, lon);
        List<PlaceRepository.DistanceRow> rows = places.findNearby(
                lat, lon, radius, category == null ? null : category.name(), minRating, limit);
        return rows.stream().map(row -> new NearbyPlaceResponse(
                placeMapper.toSummary(row), row.getDistanceMeters())).toList();
    }

    public List<PlaceSummaryResponse> bounds(double west, double south, double east, double north,
                                             PlaceCategory category, Double minRating, int limit) {
        validateCoordinate(south, west);
        validateCoordinate(north, east);
        if (south >= north) throw ApiException.validation("south must be less than north");
        if (west >= east) throw ApiException.validation(
                "west must be less than east; antimeridian-crossing bounds are not supported");
        return places.findInBounds(west, south, east, north,
                category == null ? null : category.name(), minRating, limit)
                .stream().map(placeMapper::toSummary).toList();
    }

    public Place require(UUID id) {
        return places.findById(id).orElseThrow(() -> ApiException.notFound("Place", id));
    }

    private String normalizeSort(String sort) {
        String value = blankToNull(sort);
        if (value == null) return "averageScore,desc";
        if (!SORTS.contains(value)) {
            throw ApiException.validation("sort must be one of: " + SORTS);
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateCoordinate(double lat, double lon) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90
                || !Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw ApiException.validation("latitude must be -90..90 and longitude -180..180");
        }
    }
}
