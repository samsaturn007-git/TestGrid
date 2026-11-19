package dev.testgrid.api.domain;

/** Contracts §2.1 {@code FixtureMode} (demo-shop behaviour, local profile only). */
public enum FixtureMode {
  PASS,
  PRODUCT_DEFECT,
  AUTOMATION_FLAKE,
  SELECTOR_DRIFT,
  TIMEOUT
}
