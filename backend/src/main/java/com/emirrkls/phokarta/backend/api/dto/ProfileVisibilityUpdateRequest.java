package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;
import jakarta.validation.constraints.NotNull;

public record ProfileVisibilityUpdateRequest(@NotNull ProfileVisibility visibility) {}
