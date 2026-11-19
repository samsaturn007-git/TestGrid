package dev.testgrid.api.problem;

import java.util.List;

/** A request the API refuses on purpose; rendered as Problem Details by {@link ProblemAdvice}. */
public class ApiProblemException extends RuntimeException {

  private final ProblemType type;
  private final transient List<FieldError> errors;

  public ApiProblemException(ProblemType type, String detail) {
    this(type, detail, List.of());
  }

  public ApiProblemException(ProblemType type, String detail, List<FieldError> errors) {
    super(detail);
    this.type = type;
    this.errors = List.copyOf(errors);
  }

  public ProblemType type() {
    return type;
  }

  public List<FieldError> errors() {
    return errors;
  }
}
