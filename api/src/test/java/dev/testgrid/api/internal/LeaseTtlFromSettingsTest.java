package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * PRD-04 T1 / CONFLICT_REGISTER N15: claims, starts and heartbeats take the lease TTL from the
 * {@code scheduler_settings} row, not from application properties.
 */
class LeaseTtlFromSettingsTest extends WorkerApiTestBase {

  @AfterEach
  void restoreTheDefaultTtl() {
    jdbc.sql("UPDATE scheduler_settings SET lease_ttl_s = 30").update();
  }

  @Test
  void theClaimAndEveryLeaseExtensionUseTheRowsTtl() {
    jdbc.sql("UPDATE scheduler_settings SET lease_ttl_s = 45").update();
    createRun("checkout-smoke", "PASS", "chromium");
    Claim claim = claim("chromium");

    assertThat(JsonPath.<Integer>read(claim.body(), "$.leaseTtlSeconds")).isEqualTo(45);
    assertThat(
            count(
                "SELECT count(*) FROM attempts"
                    + " WHERE extract(epoch FROM leased_until - claimed_at) = 45"))
        .isEqualTo(1);

    jdbc.sql("UPDATE scheduler_settings SET lease_ttl_s = 60").update();
    assertThat(start(claim).statusCode()).isEqualTo(200);
    assertThat(lease(claim, "heartbeat").statusCode()).isEqualTo(200);
    assertThat(
            count(
                "SELECT count(*) FROM attempts"
                    + " WHERE leased_until > now() + interval '55 seconds'"))
        .isEqualTo(1);
  }
}
