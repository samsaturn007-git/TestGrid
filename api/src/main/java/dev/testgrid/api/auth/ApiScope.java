package dev.testgrid.api.auth;

/** Contracts §3 {@code api_tokens.scopes} as PRD-08 defines them; PRD-12 adds more. */
public enum ApiScope {
  RUNS_WRITE("runs:write"),
  RUNS_READ("runs:read"),
  QUALITY_READ("quality:read");

  private final String wire;

  ApiScope(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }
}
