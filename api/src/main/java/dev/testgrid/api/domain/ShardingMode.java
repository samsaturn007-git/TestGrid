package dev.testgrid.api.domain;

/** Contracts §2.1 {@code ShardingMode}. Only {@code NONE} until PRD-11. */
public enum ShardingMode {
  NONE,
  PLAYWRIGHT_NATIVE,
  DURATION_BALANCED
}
