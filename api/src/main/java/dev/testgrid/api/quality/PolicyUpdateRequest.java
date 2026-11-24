package dev.testgrid.api.quality;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/projects/{projectId}/quality-policy}: the new rules and why they
 * change. A policy change can loosen the gate, so like a quarantine it needs a reason and is
 * audited (SECURITY_AND_TEST_STRATEGY principle 8).
 */
public record PolicyUpdateRequest(
    @NotBlank @Size(max = 500) String reason, @NotNull @Valid QualityPolicy policy) {}
