package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.Visibility;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateCollectionRequestValidationTest {

    @Test
    void coverImageMayBeBlankButMustBePresent() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            var blankCover = new CreateCollectionRequest(
                    "Mobile collection", "", Visibility.PRIVATE, "");
            assertThat(validator.validate(blankCover)).isEmpty();

            var missingCover = new CreateCollectionRequest(
                    "Mobile collection", "", Visibility.PRIVATE, null);
            assertThat(validator.validate(missingCover))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("coverImage"));
        }
    }
}
