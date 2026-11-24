package dev.testgrid.api.domain;

/** Contracts §2.1 {@code QualityOutcome}; §2.4 maps it to the CLI exit code. */
public enum QualityOutcome {
  PASS,
  FAIL,
  INDETERMINATE
}
