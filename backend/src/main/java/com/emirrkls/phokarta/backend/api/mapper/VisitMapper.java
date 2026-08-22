package com.emirrkls.phokarta.backend.api.mapper;

import com.emirrkls.phokarta.backend.api.dto.PublicVisitResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VisitMapper {
    private final PlaceMapper placeMapper;

    public VisitMapper(PlaceMapper placeMapper) {
        this.placeMapper = placeMapper;
    }

    public PublicVisitResponse toPublic(Visit visit) {
        return new PublicVisitResponse(visit.getId(), visit.getPlace().getId(),
                visit.getPlace().getName(), visit.getUser().getId(), visit.getUser().getUsername(),
                visit.getUser().getDisplayName(), visit.getUser().getAvatarUrl(),
                visit.getVisitedAt(), visit.getOverallRating(), visit.getPublicReview(),
                visit.getPhotos(), visit.getVerificationStatus());
    }

    public VisitOwnerResponse toOwner(
            Visit visit, List<VisitOwnerResponse.DimensionScoreResponse> dimensions,
            Double averageScore, long ratingCount) {
        return new VisitOwnerResponse(visit.getId(),
                placeMapper.toSummary(visit.getPlace(), averageScore, ratingCount),
                visit.getVisitedAt(), visit.getOverallRating(), dimensions,
                visit.getPublicReview(), visit.getPrivateMemory(), visit.getPhotos(),
                visit.getVisibility(), visit.getVerificationStatus());
    }
}
