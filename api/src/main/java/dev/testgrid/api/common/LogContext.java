package dev.testgrid.api.common;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.MDC;

/**
 * FR-8 log correlation: puts IDs on every log line inside a {@code try} block and removes exactly
 * those keys afterwards, so a pooled request thread never carries them into the next request.
 */
public final class LogContext implements AutoCloseable {

  private final List<String> keys = new ArrayList<>();

  public static LogContext open() {
    return new LogContext();
  }

  public LogContext put(String key, Object value) {
    if (value != null) {
      MDC.put(key, value.toString());
      keys.add(key);
    }
    return this;
  }

  @Override
  public void close() {
    keys.forEach(MDC::remove);
  }
}
