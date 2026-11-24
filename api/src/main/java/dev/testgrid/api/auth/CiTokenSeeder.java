package dev.testgrid.api.auth;

import dev.testgrid.api.audit.AuditRepository;
import dev.testgrid.api.common.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores the CI token from {@code TESTGRID_CI_TOKEN} as the {@code ci} row (CONFLICT_REGISTER N33)
 * while the context starts, before the web server accepts requests. A new value rotates it; an
 * unset value revokes it, so a token that was removed from the environment stops working.
 */
@Component
class CiTokenSeeder implements InitializingBean {

  static final String NAME = "ci";
  static final List<String> SCOPES = Arrays.stream(ApiScope.values()).map(ApiScope::wire).toList();
  private static final Logger log = LoggerFactory.getLogger(CiTokenSeeder.class);

  private final CiTokenProperties properties;
  private final ApiTokenRepository tokens;
  private final AuditRepository audit;
  private final TransactionTemplate tx;

  CiTokenSeeder(
      CiTokenProperties properties,
      ApiTokenRepository tokens,
      AuditRepository audit,
      TransactionTemplate tx) {
    this.properties = properties;
    this.tokens = tokens;
    this.audit = audit;
    this.tx = tx;
  }

  @Override
  public void afterPropertiesSet() {
    tx.executeWithoutResult(status -> {
      if (properties.enabled()) {
        byte[] hash = Hashing.sha256(properties.ciToken().getBytes(StandardCharsets.UTF_8));
        tokens
            .upsert(NAME, hash, SCOPES)
            .ifPresentOrElse(
                change -> {
                  audit.record(
                      AuditRepository.SYSTEM_ACTOR,
                      "API_TOKEN_CONFIGURED",
                      "api_token",
                      change.id(),
                      Map.of("name", NAME, "scopes", SCOPES));
                  log.info("CI token configured from TESTGRID_CI_TOKEN scopes={}", SCOPES);
                },
                () -> log.info("CI token unchanged scopes={}", SCOPES));
      } else {
        tokens
            .revoke(NAME)
            .ifPresentOrElse(
                id -> {
                  audit.record(
                      AuditRepository.SYSTEM_ACTOR,
                      "API_TOKEN_REVOKED",
                      "api_token",
                      id,
                      Map.of("name", NAME, "why", "TESTGRID_CI_TOKEN is not set"));
                  log.info("CI token revoked: TESTGRID_CI_TOKEN is not set");
                },
                () -> log.info("no CI token: TESTGRID_CI_TOKEN is not set"));
      }
    });
  }
}
