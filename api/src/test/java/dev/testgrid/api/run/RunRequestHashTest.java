package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.FixtureMode;
import dev.testgrid.api.domain.ShardingMode;
import dev.testgrid.api.domain.Trigger;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Contracts §4 canonical request hash. */
class RunRequestHashTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private static final CreateRunRequest.Target CHROMIUM =
      new CreateRunRequest.Target(Executor.LOCAL, Engine.CHROMIUM);
  private static final CreateRunRequest.Target FIREFOX =
      new CreateRunRequest.Target(Executor.LOCAL, Engine.FIREFOX);

  private static CreateRunRequest request(
      String key, List<CreateRunRequest.Target> targets, CreateRunRequest.Sharding sharding) {
    return new CreateRunRequest(
        "checkout-smoke",
        targets,
        Trigger.MANUAL,
        key,
        new CreateRunRequest.Fixture(FixtureMode.PASS, 42),
        sharding,
        null);
  }

  @Test
  void canonicalFormSortsKeysAndTargetsAndDropsTheIdempotencyKey() {
    assertThat(
            RunRequestHash.canonicalJson(request("k1", List.of(FIREFOX, CHROMIUM), null), mapper))
        .isEqualTo(
            "{\"fixture\":{\"mode\":\"PASS\",\"seed\":42},\"sharding\":{\"mode\":\"NONE\",\"shards\":1},"
                + "\"suiteKey\":\"checkout-smoke\",\"targets\":[{\"engine\":\"chromium\",\"executor\":\"LOCAL\"},"
                + "{\"engine\":\"firefox\",\"executor\":\"LOCAL\"}],\"trigger\":\"MANUAL\"}");
  }

  @Test
  void targetOrderIdempotencyKeyAndShardingDefaultDoNotChangeTheHash() {
    byte[] a = RunRequestHash.of(request("k1", List.of(CHROMIUM, FIREFOX), null), mapper);
    byte[] b =
        RunRequestHash.of(
            request(
                "k2",
                List.of(FIREFOX, CHROMIUM),
                new CreateRunRequest.Sharding(ShardingMode.NONE, 1)),
            mapper);
    assertThat(a).hasSize(32).isEqualTo(b);
  }

  @Test
  void aDifferentEngineChangesTheHash() {
    byte[] a = RunRequestHash.of(request("k", List.of(CHROMIUM), null), mapper);
    byte[] b = RunRequestHash.of(request("k", List.of(FIREFOX), null), mapper);
    assertThat(a).isNotEqualTo(b);
  }
}
