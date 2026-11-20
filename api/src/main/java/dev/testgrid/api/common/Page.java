package dev.testgrid.api.common;

import java.util.List;

/** Contracts §1 list envelope. {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, @ApiNullable String nextCursor) {}
