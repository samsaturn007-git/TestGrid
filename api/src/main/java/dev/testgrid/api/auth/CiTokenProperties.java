package dev.testgrid.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code testgrid.auth.ci-token} ({@code TESTGRID_CI_TOKEN}): the CI token's raw value, or empty
 * for no CI token. 32–256 visible ASCII characters, so a guess is hopeless and nothing can hide in
 * it.
 */
@ConfigurationProperties("testgrid.auth")
public record CiTokenProperties(@DefaultValue("") String ciToken) {

  public CiTokenProperties {
    if (!ciToken.isEmpty() && !ciToken.matches("[\\x21-\\x7e]{32,256}")) {
      throw new IllegalArgumentException(
          "TESTGRID_CI_TOKEN must be 32-256 visible ASCII characters (no spaces)");
    }
  }

  public boolean enabled() {
    return !ciToken.isEmpty();
  }

  @Override
  public String toString() {
    return "CiTokenProperties[ciToken=" + (enabled() ? "<set>" : "<unset>") + "]";
  }
}
