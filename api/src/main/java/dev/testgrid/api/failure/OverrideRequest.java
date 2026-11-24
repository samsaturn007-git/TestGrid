package dev.testgrid.api.failure;

import dev.testgrid.api.domain.FailureCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code PUT /api/v1/failure-groups/{id}/override} (PRD-07 FR-3). */
public record OverrideRequest(
    @NotNull FailureCategory category, @NotBlank @Size(max = 500) String reason) {}
