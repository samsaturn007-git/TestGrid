package dev.testgrid.api.dispatch;

import dev.testgrid.api.config.TestgridProperties;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Test-only failure points (PRD-04 Verification: {@code TG_FAULT=…}). An armed point fires once:
 * the code at that point throws {@link InjectedFault}, so the surrounding transaction rolls back
 * exactly as it would if the process had died there. {@code TG_FAULT} is refused outside the local
 * profile, so a stray variable can't break a shared deployment.
 */
@Component
public class FaultInjector {

  private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

  /**
   * The API-side points. {@code after_claim} and {@code after_complete_before_ack} are worker-side.
   */
  public enum Point {
    AFTER_PUBLISH_BEFORE_MARK;

    String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Thrown at an armed point; nothing should catch it below the transaction boundary. */
  public static final class InjectedFault extends RuntimeException {
    InjectedFault(Point point) {
      super("injected fault " + point.wireName());
    }
  }

  static final Set<String> WORKER_POINTS = Set.of("after_claim", "after_complete_before_ack");

  private final AtomicReference<Point> armed = new AtomicReference<>();

  public FaultInjector(@Value("${testgrid.fault:}") String fault, TestgridProperties properties) {
    if (fault != null && !fault.isBlank()) {
      if (!properties.isLocal()) {
        throw new IllegalStateException("TG_FAULT is only honoured with TESTGRID_PROFILE=local");
      }
      String value = fault.trim();
      if (WORKER_POINTS.contains(value)) {
        log.info("TG_FAULT={} is a worker-side point; nothing to arm in the API", value);
      } else {
        Point point = parse(value);
        armed.set(point);
        log.warn("fault point armed from TG_FAULT: {}", point.wireName());
      }
    }
  }

  public void arm(Point point) {
    armed.set(point);
  }

  public void disarm() {
    armed.set(null);
  }

  /** Throws {@link InjectedFault} if {@code point} is armed, and disarms it. */
  public void trip(Point point) {
    if (armed.compareAndSet(point, null)) {
      log.warn("fault point fired: {}", point.wireName());
      throw new InjectedFault(point);
    }
  }

  static Point parse(String value) {
    for (Point point : Point.values()) {
      if (point.wireName().equals(value)) {
        return point;
      }
    }
    throw new IllegalStateException(
        "TG_FAULT="
            + value
            + " is not a fault point (after_publish_before_mark, after_claim,"
            + " after_complete_before_ack)");
  }
}
