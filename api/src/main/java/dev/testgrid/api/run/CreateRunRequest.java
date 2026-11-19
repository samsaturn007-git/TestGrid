package dev.testgrid.api.run;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.ShardingMode;
import dev.testgrid.api.domain.Trigger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /api/v1/projects/{projectId}/runs} (contracts §4). Clients choose only enum
 * values and manifest keys; every free-text field has a pattern that excludes URLs, paths with
 * schemes and shell syntax (ADR-003).
 */
public record CreateRunRequest(
    @NotNull @Size(max = 64) @Pattern(regexp = KEY, message = MSG_KEY) String suiteKey,
    @NotNull @Size(min = 1, max = 3) List<@NotNull @Valid Target> targets,
    @NotNull Trigger trigger,
    @NotNull @Pattern(regexp = "^[A-Za-z0-9._:-]{1,200}$", message = MSG_IDEMPOTENCY)
        String idempotencyKey,
    @Valid Fixture fixture,
    @Valid Sharding sharding,
    @Valid Source source) {

  static final String KEY = "^[a-z0-9]+(-[a-z0-9]+)*$";
  static final String MSG_KEY = "must be a lowercase key such as checkout-smoke";
  static final String MSG_IDEMPOTENCY = "must be 1-200 characters from A-Z a-z 0-9 . _ : -";

  public record Target(@NotNull Executor executor, @NotNull Engine engine) {}

  public record Fixture(
      @NotNull FixtureMode mode, @NotNull @Min(0) @Max(Integer.MAX_VALUE) Integer seed) {}

  public record Sharding(@NotNull ShardingMode mode, @NotNull @Min(1) @Max(16) Integer shards) {

    static final Sharding NONE = new Sharding(ShardingMode.NONE, 1);
  }

  public record Source(
      @Pattern(regexp = "^[0-9a-f]{7,64}$", message = "must be 7-64 lowercase hex characters")
          String commitSha,
      // Git ref characters only: no ':' so no URL scheme, no '..', no spaces or shell syntax.
      @Size(max = 200)
          @Pattern(
              regexp = "^(?!.*\\.\\.)[A-Za-z0-9._/-]{1,200}$",
              message = "must be a branch name (A-Z a-z 0-9 . _ / -)")
          String branch,
      // "<system>:<id>", e.g. gh:123456/1. The id can't start with '/', so "https://..." fails.
      @Size(max = 200)
          @Pattern(
              regexp = "^[a-z]{1,16}:(?!/)[A-Za-z0-9._/-]{1,180}$",
              message = "must look like gh:<run id>/<attempt>")
          String ciRef) {}

  Sharding shardingOrDefault() {
    return sharding == null ? Sharding.NONE : sharding;
  }
}
