package com.emirrkls.phokarta.backend.api.mapper;

import com.emirrkls.phokarta.backend.api.dto.PlaceSummaryResponse;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;

@Component
public class PlaceMapper {
    public PlaceSummaryResponse toSummary(Place place, Double averageScore, long ratingCount) {
        Point point = place.getLocation();
        return new PlaceSummaryResponse(place.getId(), place.getName(), place.getCategory(),
                place.getCoverImage(), place.getCity(), place.getRegion(), place.getCountry(),
                point.getY(), point.getX(), place.getPriceLevel(), averageScore, ratingCount);
    }

    public PlaceSummaryResponse toSummary(
            com.emirrkls.phokarta.backend.repository.PlaceRepository.SummaryRow row) {
        return new PlaceSummaryResponse(row.getId(), row.getName(),
                com.emirrkls.phokarta.backend.domain.model.PlaceCategory.valueOf(row.getCategory()),
                row.getCoverImage(), row.getCity(), row.getRegion(), row.getCountry(),
                row.getLatitude(), row.getLongitude(), row.getPriceLevel(),
                row.getAverageScore(), row.getRatingCount());
    }
}
