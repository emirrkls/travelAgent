package com.emirrkls.phokarta.backend.domain.service;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RatingDimensionRegistry {

    public static final double MIN_SCORE = 0.0;
    public static final double MAX_SCORE = 10.0;

    private static final Map<PlaceCategory, List<String>> DIMENSIONS = createDimensions();

    public Set<PlaceCategory> supportedCategories() {
        return Set.copyOf(DIMENSIONS.keySet());
    }

    public List<String> dimensionsFor(PlaceCategory category) {
        Objects.requireNonNull(category, "category is required");
        List<String> dimensions = DIMENSIONS.get(category);
        if (dimensions == null) {
            throw new IllegalArgumentException("Unsupported place category: " + category);
        }
        return dimensions;
    }

    public boolean isDimensionAllowed(PlaceCategory category, String dimensionKey) {
        return dimensionKey != null && dimensionsFor(category).contains(dimensionKey);
    }

    public void validateScore(double score) {
        if (!Double.isFinite(score) || score < MIN_SCORE || score > MAX_SCORE) {
            throw new IllegalArgumentException("Rating score must be finite and between 0.0 and 10.0");
        }
    }

    public void validateDimensionScore(PlaceCategory category, String dimensionKey, double score) {
        if (!isDimensionAllowed(category, dimensionKey)) {
            throw new IllegalArgumentException(
                    "Dimension '%s' is not valid for category %s".formatted(dimensionKey, category));
        }
        validateScore(score);
    }

    public void validateScores(PlaceCategory category, Map<String, Double> scores) {
        Objects.requireNonNull(scores, "scores are required");
        scores.forEach((key, value) -> {
            if (value == null) {
                throw new IllegalArgumentException("Score for dimension '" + key + "' is required");
            }
            validateDimensionScore(category, key, value);
        });
    }

    private static Map<PlaceCategory, List<String>> createDimensions() {
        EnumMap<PlaceCategory, List<String>> dimensions = new EnumMap<>(PlaceCategory.class);
        dimensions.put(PlaceCategory.BEACH,
                List.of("SEA", "ATMOSPHERE", "SERVICE", "CLEANLINESS", "VALUE", "CROWD"));
        dimensions.put(PlaceCategory.RESTAURANT,
                List.of("FOOD", "SERVICE", "ATMOSPHERE", "VALUE", "PRESENTATION"));
        dimensions.put(PlaceCategory.CAFE,
                List.of("FOOD", "SERVICE", "ATMOSPHERE", "VALUE", "PRESENTATION"));
        dimensions.put(PlaceCategory.HOTEL,
                List.of("CLEANLINESS", "LOCATION", "ROOM", "SERVICE", "BREAKFAST", "VALUE"));
        dimensions.put(PlaceCategory.BAR,
                List.of("DRINKS", "MUSIC", "ATMOSPHERE", "SERVICE", "VALUE"));
        dimensions.put(PlaceCategory.NIGHTLIFE,
                List.of("DRINKS", "MUSIC", "ATMOSPHERE", "SERVICE", "VALUE"));
        dimensions.put(PlaceCategory.ATTRACTION,
                List.of("EXPERIENCE", "ACCESS", "ATMOSPHERE", "VALUE"));
        dimensions.put(PlaceCategory.ACTIVITY,
                List.of("EXPERIENCE", "SAFETY", "GUIDE", "VALUE"));
        dimensions.put(PlaceCategory.NATURE,
                List.of("SCENERY", "ACCESS", "CLEANLINESS", "TRANQUILITY"));
        return Map.copyOf(dimensions);
    }
}
