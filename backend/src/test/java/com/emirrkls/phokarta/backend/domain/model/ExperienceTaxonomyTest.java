package com.emirrkls.phokarta.backend.domain.model;

import org.junit.jupiter.api.Test;

import static com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.DimensionStateCode;
import static com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import static org.assertj.core.api.Assertions.assertThat;

class ExperienceTaxonomyTest {

    @Test
    void legacyRatingBoundariesMapToLockedFeelings() {
        assertThat(OverallFeelingCode.fromLegacyRating(10.0)).isEqualTo(OverallFeelingCode.BAYILDIM);
        assertThat(OverallFeelingCode.fromLegacyRating(9.0)).isEqualTo(OverallFeelingCode.BAYILDIM);
        assertThat(OverallFeelingCode.fromLegacyRating(8.9)).isEqualTo(OverallFeelingCode.GUZELDI);
        assertThat(OverallFeelingCode.fromLegacyRating(7.0)).isEqualTo(OverallFeelingCode.GUZELDI);
        assertThat(OverallFeelingCode.fromLegacyRating(6.9)).isEqualTo(OverallFeelingCode.EH_ISTE);
        assertThat(OverallFeelingCode.fromLegacyRating(5.0)).isEqualTo(OverallFeelingCode.EH_ISTE);
        assertThat(OverallFeelingCode.fromLegacyRating(4.9)).isEqualTo(OverallFeelingCode.BEKLENTIMI_KARSILAMADI);
        assertThat(OverallFeelingCode.fromLegacyRating(3.0)).isEqualTo(OverallFeelingCode.BEKLENTIMI_KARSILAMADI);
        assertThat(OverallFeelingCode.fromLegacyRating(2.9)).isEqualTo(OverallFeelingCode.BIR_DAHA_TERCIH_ETMEM);
        assertThat(OverallFeelingCode.fromLegacyRating(0.0)).isEqualTo(OverallFeelingCode.BIR_DAHA_TERCIH_ETMEM);
    }

    @Test
    void dimensionStatesRetainLockedCompatibilityScores() {
        assertThat(DimensionStateCode.VERY_GOOD.compatibilityScore()).isEqualTo(10);
        assertThat(DimensionStateCode.GOOD.compatibilityScore()).isEqualTo(8);
        assertThat(DimensionStateCode.MEDIUM.compatibilityScore()).isEqualTo(6);
        assertThat(DimensionStateCode.WEAK.compatibilityScore()).isEqualTo(4);
        assertThat(DimensionStateCode.VERY_WEAK.compatibilityScore()).isEqualTo(2);
    }

    @Test
    void canonicalCatalogIsCompleteAndOtherIsNotGivenAFakeFamily() {
        assertThat(ExperienceTaxonomy.VERSION).isEqualTo(1);
        assertThat(ExperienceTaxonomy.PrimaryExperienceCode.values()).hasSize(55);
        assertThat(ExperienceTaxonomy.familyOf(ExperienceTaxonomy.PrimaryExperienceCode.KAHVALTI))
                .isEqualTo(ExperienceTaxonomy.ExperienceFamily.FOOD_AND_DRINK);
        assertThat(ExperienceTaxonomy.familyOf(ExperienceTaxonomy.PrimaryExperienceCode.OTHER)).isNull();
        assertThat(ExperienceTaxonomy.PracticalSignalCode.values()).hasSize(16);
    }
}
