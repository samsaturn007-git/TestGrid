package dev.testgrid.api.internal;

import dev.testgrid.api.common.Hashing;
import dev.testgrid.api.problem.ProblemAdvice;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.problem.RequestCorrelationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-2. The internal port serves only {@code /internal/*} and the public port never does: a request
 * on the wrong port gets the same 404 as a path that doesn't exist, so the public surface doesn't
 * reveal the worker API. On the internal port every request needs {@code Authorization: Bearer
 * <TESTGRID_WORKER_TOKEN>}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InternalAccessFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(InternalAccessFilter.class);
  static final String INTERNAL_PREFIX = "/internal/";
  private static final String BEARER = "Bearer ";

  private final InternalServerConfig.InternalConnector internal;
  private final byte[] tokenHash;
  private final JsonMapper mapper;

  InternalAccessFilter(
      InternalServerConfig.InternalConnector internal,
      InternalApiProperties properties,
      JsonMapper mapper) {
    this.internal = internal;
    this.mapper = mapper;
    this.tokenHash =
        properties.enabled()
            ? Hashing.sha256(properties.workerToken().getBytes(StandardCharsets.UTF_8))
            : null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean internalPath = request.getRequestURI().startsWith(INTERNAL_PREFIX);
    boolean internalPort = tokenHash != null && request.getLocalPort() == internal.localPort();
    if (internalPath != internalPort) {
      reject(request, response, ProblemType.NOT_FOUND, "no such resource");
      return;
    }
    if (internalPort && !validToken(request.getHeader(HttpHeaders.AUTHORIZATION))) {
      // Never log the header itself: it may be a real token with one typo.
      log.warn(
          "rejected worker API call without a valid service token: {}", request.getRequestURI());
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      reject(request, response, ProblemType.UNAUTHORIZED, "a valid service token is required");
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * Hash both sides, then compare with {@link MessageDigest#isEqual}, which takes the same time
   * whatever the first differing byte is. Hashing first also hides the token's length.
   */
  private boolean validToken(String header) {
    if (header == null || !header.startsWith(BEARER)) {
      return false;
    }
    byte[] presented =
        Hashing.sha256(header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8));
    return MessageDigest.isEqual(presented, tokenHash);
  }

  private void reject(
      HttpServletRequest request, HttpServletResponse response, ProblemType type, String detail)
      throws IOException {
    ProblemDetail problem = ProblemAdvice.body(type, detail, request.getRequestURI(), List.of());
    problem.setProperty("traceId", RequestCorrelationFilter.traceId(request));
    response.setStatus(type.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
