package dev.testgrid.api.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/** Contracts §2.1 {@code Engine}. Lowercase on the wire and in the database. */
public enum Engine {
  @JsonProperty("chromium")
  CHROMIUM,
  @JsonProperty("firefox")
  FIREFOX,
  @JsonProperty("webkit")
  WEBKIT;

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Engine fromWireName(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}
