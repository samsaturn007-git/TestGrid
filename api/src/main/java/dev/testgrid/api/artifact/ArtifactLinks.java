package dev.testgrid.api.artifact;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signed content links (PRD-06 FR-4, ADR-005): {@code sig = base64url(HMAC-SHA256(key,
 * "v1:<artifactId>:<expires>"))}, with {@code expires} in epoch seconds. Binding the artifact ID
 * means a signature can't be moved to another artifact; binding {@code expires} means it can't be
 * extended. Checking needs no database and is done before any lookup, so a caller without a valid
 * link learns nothing about which artifacts exist.
 */
@Component
public class ArtifactLinks {

  private static final Logger log = LoggerFactory.getLogger(ArtifactLinks.class);
  private static final String VERSION = "v1";
  private static final String HMAC = "HmacSHA256";
  private static final Pattern EXPIRES = Pattern.compile("^[0-9]{1,12}$");
  private static final Pattern SIGNATURE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  private final SecretKeySpec key;

  public ArtifactLinks(ArtifactProperties properties) {
    byte[] secret;
    if (properties.linkKey() != null) {
      secret = properties.linkKey().getBytes(StandardCharsets.UTF_8);
    } else {
      secret = new byte[32];
      new SecureRandom().nextBytes(secret);
      log.warn("TESTGRID_ARTIFACT_LINK_KEY is not set: using a random key; links end at restart");
    }
    this.key = new SecretKeySpec(secret, HMAC);
  }

  public String sign(UUID artifactId, long expiresEpochSeconds) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac(artifactId, expiresEpochSeconds));
  }

  /**
   * The link's expiry if {@code sig} is this server's signature for exactly this artifact and
   * expiry; empty for anything else (missing, malformed, forged, moved). The caller compares the
   * expiry with the database clock.
   */
  public OptionalLong verify(UUID artifactId, String expires, String sig) {
    if (expires == null
        || sig == null
        || !EXPIRES.matcher(expires).matches()
        || !SIGNATURE.matcher(sig).matches()) {
      return OptionalLong.empty();
    }
    long at = Long.parseLong(expires);
    byte[] presented = Base64.getUrlDecoder().decode(sig);
    // Constant time: how long the comparison takes says nothing about how much of it matched.
    return MessageDigest.isEqual(presented, mac(artifactId, at))
        ? OptionalLong.of(at)
        : OptionalLong.empty();
  }

  private byte[] mac(UUID artifactId, long expires) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return mac.doFinal(
          (VERSION + ":" + artifactId + ":" + expires).getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("every Java platform must provide HmacSHA256", e);
    }
  }
}
