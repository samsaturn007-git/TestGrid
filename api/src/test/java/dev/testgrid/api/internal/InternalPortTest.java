package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** FR-2: each port serves only its own surface, and the worker API needs the service token. */
class InternalPortTest extends WorkerApiTestBase {

  private static final String TARGET = "00000000-0000-4000-8000-0000000000aa";
  private static final String CLAIM_PATH = "/internal/v1/targets/" + TARGET + "/claim";
  private static final String CLAIM_BODY =
      "{\"workerId\":\"w-1\",\"messageId\":\"00000000-0000-4000-8000-0000000000bb\","
          + "\"dispatchGeneration\":1}";

  @Test
  void thePublicPortAnswers404ForInternalPaths() {
    var response = post(publicPort, CLAIM_PATH, CLAIM_BODY, TOKEN);
    assertThat(response.statusCode()).isEqualTo(404);
    // Same answer as a path that doesn't exist at all.
    var unknown = post(publicPort, "/no/such/path", CLAIM_BODY, TOKEN);
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("/problems/not-found");
    assertThat(unknown.body()).contains("/problems/not-found");
  }

  @Test
  void theInternalPortAnswers404ForPublicPaths() {
    assertThat(get(internalPort(), "/api/v1/runs").statusCode()).isEqualTo(404);
    assertThat(get(internalPort(), "/healthz").statusCode()).isEqualTo(404);
    assertThat(get(publicPort, "/healthz").statusCode()).isEqualTo(200);
  }

  @Test
  void aMissingOrWrongTokenIs401() {
    var missing = post(internalPort(), CLAIM_PATH, CLAIM_BODY, null);
    assertThat(missing.statusCode()).isEqualTo(401);
    assertThat(missing.body()).contains("/problems/unauthorized");
    assertThat(missing.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");

    var wrong = post(internalPort(), CLAIM_PATH, CLAIM_BODY, TOKEN + "x");
    assertThat(wrong.statusCode()).isEqualTo(401);
    var almost = post(internalPort(), CLAIM_PATH, CLAIM_BODY, TOKEN.substring(1) + "0");
    assertThat(almost.statusCode()).isEqualTo(401);
  }

  @Test
  void theRightTokenReachesTheWorkerApi() {
    // The controller answers: an unknown target, not the filter's 404 for an unknown path.
    var response = post(internalPort(), CLAIM_PATH, CLAIM_BODY, TOKEN);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("target " + TARGET + " not found");
  }

  @Test
  void theInternalPortIsADifferentPort() {
    assertThat(internalPort()).isPositive().isNotEqualTo(publicPort);
  }
}
