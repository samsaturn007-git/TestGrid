package dev.testgrid.api.quality;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The rules of one policy version (contracts §12, without {@code version}: the server assigns it).
 * Every field is required, so a stored version reads the same however the defaults change later.
 */
public record QualityPolicy(
    @NotNull @Size(min = 1, max = 3)
        List<
                @NotNull
                @Pattern(
                    regexp = "local:(chromium|firefox|webkit)",
                    message = "must be local:chromium, local:firefox or local:webkit")
                String>
            requiredTargets,
    @NotNull Boolean failOnCriticalCase,
    @NotNull @Min(0) @Max(1000) Integer maxFailedCases,
    @NotNull Boolean failOnInfraError,
    @NotNull @DecimalMin("0") @DecimalMax("1") Double maxFlakyRate,
    @NotNull @Valid DurationRegression durationRegression,
    @NotNull @Valid QuarantineSettings quarantine) {

  /** Contracts §8 thresholds, set per policy: slower than base × maxRatio and base + minDeltaMs. */
  @Schema(name = "QualityPolicyDurationRegression")
  public record DurationRegression(
      @NotNull Boolean enabled,
      @NotNull @DecimalMin(value = "1", inclusive = false) @DecimalMax("10") Double maxRatio,
      @NotNull @Min(0) @Max(600_000) Integer minDeltaMs,
      @NotNull Boolean blocking) {}

  @Schema(name = "QualityPolicyQuarantine")
  public record QuarantineSettings(
      @NotNull Boolean honor,
      @NotNull @Min(0) @Max(50) Integer maxActive,
      @NotNull AutoMode autoMode) {}

  /** Contracts §12 {@code autoMode}. */
  public enum AutoMode {
    OFF,
    SUGGEST,
    AUTO
  }
}
