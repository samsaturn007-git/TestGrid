package dev.testgrid.api.auth;

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
import java.util.Optional;
import java.util.regex.Pattern;
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
 * PRD-08 FR-5 (CONFLICT_REGISTER N33). On {@code /api/v1}, a request that carries {@code
 * Authorization} must carry a valid token with the scope its endpoint needs: a malformed, unknown,
 * revoked or expired token is {@code 401}, a missing scope {@code 403}. A bad token therefore never
 * falls back to an anonymous call. Requests without the header pass through: until PRD-12 the
 * dashboard has no login, and only endpoints that ask for a principal ({@code trigger: CI} runs)
 * refuse them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class ApiTokenFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);
  private static final String PREFIX = "/api/v1/";
  private static final Pattern BEARER = Pattern.compile("Bearer ([\\x21-\\x7e]{1,512})");
  private static final Pattern RUN_WRITES =
      Pattern.compile("/api/v1/(projects/[^/]+/runs|runs/[^/]+/(cancel|rerun))");
  private static final Pattern QUALITY_READS =
      Pattern.compile("/api/v1/(runs/[^/]+/quality|projects/[^/]+/(quality-policy|quarantines))");

  private final ApiTokenRepository tokens;
  private final JsonMapper mapper;

  ApiTokenFilter(ApiTokenRepository tokens, JsonMapper mapper) {
    this.tokens = tokens;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null) {
      chain.doFilter(request, response);
      return;
    }
    var match = BEARER.matcher(header);
    if (!match.matches()) {
      reject(request, response, ProblemType.UNAUTHORIZED, "Authorization must be Bearer <token>");
      return;
    }
    // Looked up by its sha256: the stored hash is all the database ever sees, and the lookup can't
    // leak anything an attacker could steer, because they can't choose the hash of their guess.
    byte[] presented = Hashing.sha256(match.group(1).getBytes(StandardCharsets.UTF_8));
    Optional<ApiTokenRepository.Stored> stored = tokens.findByHash(presented);
    if (stored.isEmpty() || !MessageDigest.isEqual(presented, stored.get().hash())) {
      // Never log the header: it may be a real token with one typo.
      log.warn(
          "rejected an unknown API token on {} {}", request.getMethod(), request.getRequestURI());
      reject(request, response, ProblemType.UNAUTHORIZED, "the token is not valid");
      return;
    }
    ApiTokenRepository.Stored token = stored.get();
    if (!token.usable()) {
      log.warn("rejected token {}: {}", token.name(), token.unusableWhy());
      reject(request, response, ProblemType.UNAUTHORIZED, "the token is " + token.unusableWhy());
      return;
    }
    Optional<ApiScope> needed = requiredScope(request.getMethod(), request.getRequestURI());
    if (needed.isEmpty() || !token.scopes().contains(needed.get().wire())) {
      log.warn(
          "token {} lacks the scope for {} {}",
          token.name(),
          request.getMethod(),
          request.getRequestURI());
      reject(
          request,
          response,
          ProblemType.FORBIDDEN,
          needed
              .map(s -> "token " + token.name() + " lacks " + s.wire())
              .orElse("no token scope grants " + request.getMethod() + " on this endpoint yet"));
      return;
    }
    tokens.touch(token.id());
    request.setAttribute(
        ApiPrincipal.ATTRIBUTE, new ApiPrincipal(token.id(), token.name(), token.scopes()));
    chain.doFilter(request, response);
  }

  /**
   * The scope a token needs here. Empty for writes no PRD-08 scope covers (the policy, quarantines,
   * failure overrides): a CI token must not be able to loosen its own gate.
   */
  static Optional<ApiScope> requiredScope(String method, String path) {
    if (method.equals("GET") || method.equals("HEAD")) {
      return Optional.of(
          QUALITY_READS.matcher(path).matches() ? ApiScope.QUALITY_READ : ApiScope.RUNS_READ);
    }
    if (method.equals("POST") && RUN_WRITES.matcher(path).matches()) {
      return Optional.of(ApiScope.RUNS_WRITE);
    }
    return Optional.empty();
  }

  private void reject(
      HttpServletRequest request, HttpServletResponse response, ProblemType type, String detail)
      throws IOException {
    ProblemDetail problem = ProblemAdvice.body(type, detail, request.getRequestURI(), List.of());
    problem.setProperty("traceId", RequestCorrelationFilter.traceId(request));
    if (type == ProblemType.UNAUTHORIZED) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
    response.setStatus(type.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
