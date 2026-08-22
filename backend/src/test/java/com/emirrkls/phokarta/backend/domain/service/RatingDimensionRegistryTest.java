package com.emirrkls.phokarta.backend.domain.service;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RatingDimensionRegistryTest {

    private final RatingDimensionRegistry registry = new RatingDimensionRegistry();

    @Test
    void acceptsAnyBeachScoreSubset() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("SEA", 9.5);
        scores.put("ATMOSPHERE", 8.5);

        registry.validateScores(PlaceCategory.BEACH, scores);

        assertThat(registry.dimensionsFor(PlaceCategory.BEACH))
                .containsExactlyElementsOf(List.of(
                        "SEA", "ATMOSPHERE", "SERVICE", "CLEANLINESS", "VALUE", "CROWD"));
    }

    @Test
    void rejectsDimensionNotRegisteredForBeach() {
        assertThatThrownBy(() ->
                registry.validateDimensionScore(PlaceCategory.BEACH, "BREAKFAST", 8.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BREAKFAST")
                .hasMessageContaining("BEACH");
    }

    @Test
    void rejectsScoresOutsideAllowedRange() {
        assertThatThrownBy(() -> registry.validateScore(10.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.0 and 10.0");
        assertThatThrownBy(() -> registry.validateScore(-0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.validateScore(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
