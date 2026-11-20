package dev.testgrid.api.problem;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Every error leaves the API as RFC 9457 Problem Details with a contracts §6 type. Messages say
 * which field was wrong, never how the server failed: no exception text or stack trace from an
 * unexpected error reaches the client (PRD-02 FR-8).
 */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

  public static ProblemDetail body(
      ProblemType type, String detail, String instance, List<FieldError> errors) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
    withTraceId(problem);
    problem.setType(type.uri());
    problem.setTitle(type.title());
    if (instance != null) {
      problem.setInstance(URI.create(instance));
    }
    if (!errors.isEmpty()) {
      problem.setProperty("errors", errors);
    }
    return problem;
  }

  @ExceptionHandler(ApiProblemException.class)
  ResponseEntity<ProblemDetail> apiProblem(ApiProblemException ex, HttpServletRequest request) {
    return respond(body(ex.type(), ex.getMessage(), request.getRequestURI(), ex.errors()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
    log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "The server could not complete the request.");
    problem.setTitle("Internal error");
    problem.setInstance(URI.create(request.getRequestURI()));
    withTraceId(problem);
    return respond(problem);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(e -> errors.add(new FieldError(e.getField(), e.getDefaultMessage())));
    ex.getBindingResult()
        .getGlobalErrors()
        .forEach(e -> errors.add(new FieldError(e.getObjectName(), e.getDefaultMessage())));
    errors.sort(Comparator.comparing(FieldError::field));
    return typed(ProblemType.VALIDATION_FAILED, "the request has invalid fields", request, errors);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Throwable cause = ex.getCause();
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof BodyTooLargeException tooLarge) {
        return typed(ProblemType.VALIDATION_FAILED, tooLarge.getMessage(), request, List.of());
      }
    }
    if (cause instanceof UnrecognizedPropertyException unknown) {
      return typed(
          ProblemType.VALIDATION_FAILED,
          "unknown field " + unknown.getPropertyName(),
          request,
          List.of(new FieldError(path(unknown), "unknown field")));
    }
    if (cause instanceof StreamReadException) {
      return typed(ProblemType.VALIDATION_FAILED, "malformed JSON", request, List.of());
    }
    if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
      return typed(
          ProblemType.VALIDATION_FAILED,
          "invalid value for " + path(jackson),
          request,
          List.of(new FieldError(path(jackson), "invalid value or type")));
    }
    return typed(ProblemType.VALIDATION_FAILED, "the request body is missing", request, List.of());
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String name = ex.getPropertyName() == null ? "parameter" : ex.getPropertyName();
    return typed(
        ProblemType.VALIDATION_FAILED,
        "invalid value for " + name,
        request,
        List.of(new FieldError(name, "invalid value or type")));
  }

  /**
   * Spring's built-in errors keep their status and get a contracts §6 type where one exists. The
   * rest (405, 406) have no catalogue entry and leave {@code type} out, which RFC 9457 reads as
   * {@code about:blank}.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, status, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      withTraceId(problem);
      if (status.value() == 415) {
        problem.setType(ProblemType.UNSUPPORTED_MEDIA_TYPE.uri());
        problem.setTitle(ProblemType.UNSUPPORTED_MEDIA_TYPE.title());
      } else if (status.value() == 404) {
        problem.setType(ProblemType.NOT_FOUND.uri());
        problem.setTitle(ProblemType.NOT_FOUND.title());
      } else if (status.value() == 400 && problem.getType().toString().equals("about:blank")) {
        problem.setType(ProblemType.VALIDATION_FAILED.uri());
        problem.setTitle(ProblemType.VALIDATION_FAILED.title());
      }
    }
    return response;
  }

  private static ResponseEntity<Object> typed(
      ProblemType type, String detail, WebRequest request, List<FieldError> errors) {
    String uri =
        request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    return ResponseEntity.status(type.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body(type, detail, uri, errors));
  }

  /** Contracts §1: every Problem body carries the request's trace ID. */
  private static void withTraceId(ProblemDetail problem) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      String traceId = RequestCorrelationFilter.traceId(attrs.getRequest());
      if (traceId != null) {
        problem.setProperty("traceId", traceId);
      }
    }
  }

  private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
    return ResponseEntity.status(problem.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /** Jackson's reference path as {@code targets[0].engine}. */
  private static String path(JacksonException ex) {
    StringBuilder out = new StringBuilder();
    for (JacksonException.Reference ref : ex.getPath()) {
      if (ref.getPropertyName() != null) {
        if (!out.isEmpty()) {
          out.append('.');
        }
        out.append(ref.getPropertyName());
      } else if (ref.getIndex() >= 0) {
        out.append('[').append(ref.getIndex()).append(']');
      }
    }
    return out.toString();
  }
}
