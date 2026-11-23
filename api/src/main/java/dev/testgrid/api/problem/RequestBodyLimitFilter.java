package dev.testgrid.api.problem;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caps JSON bodies at 64 KB (contracts §1). A declared Content-Length over the cap is refused
 * before reading; a chunked body is cut off as soon as it passes the cap, so the API never buffers
 * more than the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

  public static final long MAX_BODY_BYTES = 64 * 1024;

  // PRD-06 (CONFLICT_REGISTER N21): the artifact upload streams with its own per-kind limits. The
  // internal access filter runs first, so this path is only reached on the internal port with a
  // valid service token; every other request keeps the 64 KB cap.
  private static final Pattern ARTIFACT_UPLOAD =
      Pattern.compile("^/internal/v1/attempts/[^/]+/artifacts$");

  private final JsonMapper mapper;

  public RequestBodyLimitFilter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return "POST".equals(request.getMethod())
        && ARTIFACT_UPLOAD.matcher(request.getRequestURI()).matches();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      response.setStatus(ProblemType.VALIDATION_FAILED.status().value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      ProblemDetail problem =
          ProblemAdvice.body(
              ProblemType.VALIDATION_FAILED,
              new BodyTooLargeException(MAX_BODY_BYTES).getMessage(),
              request.getRequestURI(),
              List.of());
      // Runs before Spring's request context exists, so the trace ID is added here directly.
      problem.setProperty("traceId", RequestCorrelationFilter.traceId(request));
      mapper.writeValue(response.getOutputStream(), problem);
      return;
    }
    chain.doFilter(new LimitedRequest(request), response);
  }

  private static final class LimitedRequest extends HttpServletRequestWrapper {

    private ServletInputStream limited;

    LimitedRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (limited == null) {
        limited = new LimitedInputStream(super.getInputStream());
      }
      return limited;
    }
  }

  private static final class LimitedInputStream extends ServletInputStream {

    private final ServletInputStream in;
    private long count;

    LimitedInputStream(ServletInputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = in.read(buf, off, len);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws BodyTooLargeException {
      count += n;
      if (count > MAX_BODY_BYTES) {
        throw new BodyTooLargeException(MAX_BODY_BYTES);
      }
    }

    @Override
    public boolean isFinished() {
      return in.isFinished();
    }

    @Override
    public boolean isReady() {
      return in.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      in.setReadListener(listener);
    }
  }
}
