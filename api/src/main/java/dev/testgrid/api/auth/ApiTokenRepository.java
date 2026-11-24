package dev.testgrid.api.auth;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code api_tokens}: rows hold the sha256 of a token, never the token. */
@Repository
public class ApiTokenRepository {

  public record Stored(
      UUID id, String name, byte[] hash, Set<String> scopes, boolean usable, String unusableWhy) {}

  private final JdbcClient jdbc;

  public ApiTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code usable} is decided on the database clock: not revoked, and not past {@code expires_at}.
   */
  public Optional<Stored> findByHash(byte[] hash) {
    return jdbc.sql(
            """
            SELECT id, name, token_hash, scopes,
                   CASE WHEN revoked_at IS NOT NULL THEN 'revoked'
                        WHEN expires_at IS NOT NULL AND expires_at <= now() THEN 'expired'
                   END AS unusable
            FROM api_tokens WHERE token_hash = :h
            """)
        .param("h", hash)
        .query((rs, i) -> {
          String unusable = rs.getString("unusable");
          String[] scopes = (String[]) rs.getArray("scopes").getArray();
          return new Stored(
              rs.getObject("id", UUID.class),
              rs.getString("name"),
              rs.getBytes("token_hash"),
              Set.of(scopes),
              unusable == null,
              unusable);
        })
        .optional();
  }

  /** At most one write a minute per token, so a polling CLI doesn't update the row every second. */
  public void touch(UUID id) {
    jdbc.sql(
            """
            UPDATE api_tokens SET last_used_at = now()
            WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
            """)
        .param("id", id)
        .update();
  }

  public record Change(UUID id, boolean changed) {}

  /**
   * Makes the named token's hash and scopes these, un-revoking it; {@code changed} is false when
   * they already were. A changed hash is a rotation: the old token stops working at once.
   */
  public Optional<Change> upsert(String name, byte[] hash, List<String> scopes) {
    return jdbc.sql(
            """
            INSERT INTO api_tokens (name, token_hash, scopes)
            VALUES (:n, :h, CAST(:s AS text[]))
            ON CONFLICT (name) DO UPDATE
              SET token_hash = EXCLUDED.token_hash, scopes = EXCLUDED.scopes, revoked_at = NULL,
                  version = api_tokens.version + 1
              WHERE api_tokens.token_hash <> EXCLUDED.token_hash
                 OR api_tokens.scopes <> EXCLUDED.scopes
                 OR api_tokens.revoked_at IS NOT NULL
            RETURNING id
            """)
        .param("n", name)
        .param("h", hash)
        .param("s", "{" + String.join(",", scopes) + "}")
        .query(UUID.class)
        .optional()
        .map(id -> new Change(id, true));
  }

  /** Revokes the named token if it is live; its id, or empty when there was nothing to revoke. */
  public Optional<UUID> revoke(String name) {
    return jdbc.sql(
            """
            UPDATE api_tokens SET revoked_at = now(), version = version + 1
            WHERE name = :n AND revoked_at IS NULL
            RETURNING id
            """)
        .param("n", name)
        .query(UUID.class)
        .optional();
  }
}
