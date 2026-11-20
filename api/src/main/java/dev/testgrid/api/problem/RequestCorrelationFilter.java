package dev.testgrid.api.problem;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Contracts §1 correlation. A valid W3C {@code traceparent} continues the caller's trace; otherwise
 * the API starts one. Every response gets {@code X-Request-Id} (a client value is kept only if it
 * is short and plain), and both IDs go to the log MDC and the Problem body's {@code traceId}. Runs
 * first so even the body-size refusal carries them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

  public static final String TRACEPARENT = "traceparent";
  public static final String REQUEST_ID = "X-Request-Id";
  static final String TRACE_ID_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".traceId";

  // version 00, 32-hex trace id, 16-hex parent id, 2-hex flags (W3C Trace Context level 1).
  private static final Pattern TRACEPARENT_FORMAT =
      Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
  private static final Pattern REQUEST_ID_FORMAT = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = traceIdFrom(request.getHeader(TRACEPARENT));
    String requestId = request.getHeader(REQUEST_ID);
    if (requestId == null || !REQUEST_ID_FORMAT.matcher(requestId).matches()) {
      requestId = UUID.randomUUID().toString();
    }
    request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
    response.setHeader(REQUEST_ID, requestId);
    MDC.put("traceId", traceId);
    MDC.put("requestId", requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("traceId");
      MDC.remove("requestId");
    }
  }

  public static String traceId(HttpServletRequest request) {
    Object value = request.getAttribute(TRACE_ID_ATTRIBUTE);
    return value instanceof String s ? s : null;
  }

  static String traceIdFrom(String traceparent) {
    if (traceparent != null) {
      var m = TRACEPARENT_FORMAT.matcher(traceparent.trim());
      // All-zero trace or parent IDs are invalid per the spec; start a new trace instead.
      if (m.matches() && !m.group(1).matches("0+") && !m.group(2).matches("0+")) {
        return m.group(1);
      }
    }
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
