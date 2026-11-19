package dev.testgrid.api.problem;

/** One entry of a Problem body's {@code errors[]} (contracts §1). */
public record FieldError(String field, String message) {}
