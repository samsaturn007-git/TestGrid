package dev.testgrid.api.manifest;

import java.util.List;

/** Typed view of worker/manifest/suites.json, read only after JSON Schema validation passed. */
public record SuiteManifest(int schemaVersion, List<Suite> suites) {

  public record Suite(
      String suiteKey,
      String displayName,
      Boolean enabled,
      List<String> specFiles,
      List<String> allowedEngines,
      int timeoutMs,
      int playwrightRetries,
      String baseUrlRef,
      List<String> fixtureModes,
      List<Case> cases) {

    public boolean isEnabled() {
      return enabled == null || enabled;
    }
  }

  public record Case(
      String caseKey, String title, String tag, boolean critical, Integer expectedDurationMs) {}
}
