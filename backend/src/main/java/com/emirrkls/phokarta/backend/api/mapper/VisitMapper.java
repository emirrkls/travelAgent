package com.emirrkls.phokarta.backend.api.mapper;

import com.emirrkls.phokarta.backend.api.dto.PublicActivityResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicVisitResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitMediaResponse;
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
        return toPublic(visit, List.of());
    }

    public PublicVisitResponse toPublic(Visit visit, List<VisitMediaResponse> media) {
        return new PublicVisitResponse(visit.getId(), visit.getPlace().getId(),
                visit.getPlace().getName(), visit.getUser().getId(), visit.getUser().getUsername(),
                visit.getUser().getDisplayName(), visit.getUser().getAvatarUrl(),
                visit.getVisitedAt(), visit.getOverallRating(), visit.getPublicReview(),
                visit.getPhotos(), media, visit.getVerificationStatus());
    }

    public PublicActivityResponse toActivity(Visit visit) {
        return new PublicActivityResponse(
                visit.getId(),
                new PublicActivityResponse.PublicAuthor(
                        visit.getUser().getId(),
                        visit.getUser().getUsername(),
                        visit.getUser().getDisplayName(),
                        visit.getUser().getAvatarUrl()),
                new PublicActivityResponse.ActivityPlace(
                        visit.getPlace().getId(),
                        visit.getPlace().getName(),
                        visit.getPlace().getCategory(),
                        visit.getPlace().getCity(),
                        visit.getPlace().getCoverImage()),
                visit.getOverallRating(),
                visit.getPublicReview(),
                visit.getVisitedAt());
    }

    public VisitOwnerResponse toOwner(
            Visit visit, List<VisitOwnerResponse.DimensionScoreResponse> dimensions,
            Double averageScore, long ratingCount) {
        return toOwner(visit, dimensions, averageScore, ratingCount, List.of());
    }

    public VisitOwnerResponse toOwner(
            Visit visit, List<VisitOwnerResponse.DimensionScoreResponse> dimensions,
            Double averageScore, long ratingCount, List<VisitMediaResponse> media) {
        return new VisitOwnerResponse(visit.getId(),
                placeMapper.toSummary(visit.getPlace(), averageScore, ratingCount),
                visit.getVisitedAt(), visit.getOverallRating(), dimensions,
                visit.getPublicReview(), visit.getPrivateMemory(), visit.getPhotos(), media,
                visit.getVisibility(), visit.getVerificationStatus());
    }
}
