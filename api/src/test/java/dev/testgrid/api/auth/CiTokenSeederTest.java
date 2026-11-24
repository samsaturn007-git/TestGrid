package dev.testgrid.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.audit.AuditRepository;
import dev.testgrid.api.common.Hashing;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** CONFLICT_REGISTER N33: the CI token is stored as a hash, rotated and revoked from the env. */
@SpringBootTest(properties = "testgrid.auth.ci-token=" + CiTokenSeederTest.TOKEN)
@Import(PostgresTestcontainer.class)
class CiTokenSeederTest {

  static final String TOKEN = "test-only-seeded-ci-token-0123456789abcdef";
  private static final String ROTATED = "test-only-rotated-ci-token-0123456789abcdef";

  @Autowired JdbcClient jdbc;
  @Autowired ApiTokenRepository tokens;
  @Autowired AuditRepository audit;
  @Autowired TransactionTemplate tx;

  private CiTokenSeeder seeder(String token) {
    return new CiTokenSeeder(new CiTokenProperties(token), tokens, audit, tx);
  }

  private Map<String, Object> ciRow() {
    return jdbc.sql(
            "SELECT token_hash, array_to_string(scopes, ' ') AS scopes, revoked_at FROM api_tokens"
                + " WHERE name = 'ci'")
        .query()
        .singleRow();
  }

  private long audits(String action) {
    return jdbc.sql("SELECT count(*) FROM audit_events WHERE action = :a")
        .param("a", action)
        .query(Long.class)
        .single();
  }

  @BeforeEach
  void startFromTheConfiguredToken() {
    seeder(TOKEN).afterPropertiesSet();
  }

  @Test
  void startupStoresOnlyTheHashWithTheCiScopes() {
    Map<String, Object> row = ciRow();
    assertThat((byte[]) row.get("token_hash"))
        .isEqualTo(Hashing.sha256(TOKEN.getBytes(StandardCharsets.UTF_8)));
    assertThat(row.get("scopes")).isEqualTo("runs:write runs:read quality:read");
    assertThat(row.get("revoked_at")).isNull();
    String everything =
        jdbc.sql("SELECT string_agg(t::text, '|') FROM api_tokens t").query(String.class).single();
    assertThat(everything).doesNotContain(TOKEN);
  }

  @Test
  void anUnchangedTokenWritesNothing() {
    long before = audits("API_TOKEN_CONFIGURED");
    seeder(TOKEN).afterPropertiesSet();
    assertThat(audits("API_TOKEN_CONFIGURED")).isEqualTo(before);
  }

  @Test
  void aNewValueRotatesAndAnUnsetValueRevokes() {
    long configured = audits("API_TOKEN_CONFIGURED");
    seeder(ROTATED).afterPropertiesSet();
    assertThat((byte[]) ciRow().get("token_hash"))
        .isEqualTo(Hashing.sha256(ROTATED.getBytes(StandardCharsets.UTF_8)));
    assertThat(tokens.findByHash(Hashing.sha256(TOKEN.getBytes(StandardCharsets.UTF_8)))).isEmpty();
    assertThat(audits("API_TOKEN_CONFIGURED")).isEqualTo(configured + 1);

    long revoked = audits("API_TOKEN_REVOKED");
    seeder("").afterPropertiesSet();
    assertThat(ciRow().get("revoked_at")).isNotNull();
    assertThat(audits("API_TOKEN_REVOKED")).isEqualTo(revoked + 1);
    seeder("").afterPropertiesSet();
    assertThat(audits("API_TOKEN_REVOKED")).isEqualTo(revoked + 1);
  }

  @Test
  void aWeakOrMalformedTokenStopsStartup() {
    for (String bad : List.of("short", "has a space in it 0123456789abcdef", "x".repeat(257))) {
      assertThatThrownBy(() -> new CiTokenProperties(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(bad);
    }
    assertThat(new CiTokenProperties(TOKEN).toString()).doesNotContain(TOKEN);
  }

  @Test
  void requiredScopesFollowTheEndpoint() {
    assertThat(ApiTokenFilter.requiredScope("POST", "/api/v1/projects/p/runs"))
        .contains(ApiScope.RUNS_WRITE);
    assertThat(ApiTokenFilter.requiredScope("POST", "/api/v1/runs/r/rerun"))
        .contains(ApiScope.RUNS_WRITE);
    assertThat(ApiTokenFilter.requiredScope("GET", "/api/v1/runs/r/quality"))
        .contains(ApiScope.QUALITY_READ);
    assertThat(ApiTokenFilter.requiredScope("GET", "/api/v1/runs/r")).contains(ApiScope.RUNS_READ);
    assertThat(ApiTokenFilter.requiredScope("PUT", "/api/v1/projects/p/quality-policy")).isEmpty();
    assertThat(ApiTokenFilter.requiredScope("POST", "/api/v1/projects/p/quarantines")).isEmpty();
    assertThat(ApiTokenFilter.requiredScope("DELETE", "/api/v1/quarantines/q")).isEmpty();
    assertThat(ApiTokenFilter.requiredScope("PUT", "/api/v1/failure-groups/g/override")).isEmpty();
  }
}
