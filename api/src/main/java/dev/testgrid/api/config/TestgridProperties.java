package dev.testgrid.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code profile} comes from {@code TESTGRID_PROFILE}. Only {@code local} unlocks fixture modes
 * (PRD-02 FR-4); unset means a non-local deployment, the safe default.
 */
@ConfigurationProperties("testgrid")
public record TestgridProperties(String profile) {

  public static final String LOCAL = "local";

  public boolean isLocal() {
    return LOCAL.equals(profile);
  }
}
