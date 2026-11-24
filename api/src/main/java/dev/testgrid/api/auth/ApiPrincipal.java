package dev.testgrid.api.auth;

import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The token a request presented, once {@link ApiTokenFilter} has verified it. */
public record ApiPrincipal(UUID tokenId, String name, Set<String> scopes) {

  static final String ATTRIBUTE = ApiPrincipal.class.getName();

  public static Optional<ApiPrincipal> of(HttpServletRequest request) {
    return Optional.ofNullable((ApiPrincipal) request.getAttribute(ATTRIBUTE));
  }

  /** 401 without a token; 403 when the token lacks {@code scope}. */
  public static ApiPrincipal require(HttpServletRequest request, ApiScope scope, String why) {
    ApiPrincipal principal =
        of(request)
            .orElseThrow(() ->
                new ApiProblemException(
                    ProblemType.UNAUTHORIZED,
                    why + " needs an Authorization: Bearer token with " + scope.wire()));
    if (!principal.scopes().contains(scope.wire())) {
      throw new ApiProblemException(
          ProblemType.FORBIDDEN, "token " + principal.name() + " lacks " + scope.wire());
    }
    return principal;
  }
}
