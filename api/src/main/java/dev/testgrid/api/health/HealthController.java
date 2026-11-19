package dev.testgrid.api.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness only. Readiness (database, broker) is added when those dependencies exist. */
@RestController
public class HealthController {

  @GetMapping("/healthz")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
