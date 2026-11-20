package dev.testgrid.api.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A response field that is always present but may be {@code null}. The OpenAPI spec (and so the
 * generated TypeScript type) shows it as {@code T | null} instead of optional.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.RECORD_COMPONENT,
  ElementType.FIELD,
  ElementType.METHOD,
  ElementType.PARAMETER
})
public @interface ApiNullable {}
