package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.artifact.ArtifactKind;
import dev.testgrid.api.artifact.ArtifactStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * PRD-06 T2: {@code POST /internal/v1/attempts/{id}/artifacts} over real HTTP on the internal port,
 * against Testcontainers PostgreSQL and a real local store. ART-001 (size and type), ART-003 (IDs).
 */
class ArtifactUploadTest extends WorkerApiTestBase {

  static final String BOUNDARY = "tg-test-boundary-91";
  static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

  @Autowired ArtifactStore store;
  @Autowired ArtifactUploadService uploads;

  @BeforeEach
  void cleanStore() throws Exception {
    for (String key : store.keys()) {
      store.delete(key);
    }
  }

  static byte[] png(int size) {
    byte[] bytes = Arrays.copyOf(PNG_SIGNATURE, size);
    Arrays.fill(bytes, PNG_SIGNATURE.length, size, (byte) 3);
    return bytes;
  }

  static byte[] zip(int size) {
    byte[] bytes = Arrays.copyOf(new byte[] {'P', 'K', 3, 4}, size);
    Arrays.fill(bytes, 4, size, (byte) 9);
    return bytes;
  }

  static byte[] multipart(byte[] content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(content);
    out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  HttpResponse<String> upload(String attemptId, String token, String query, byte[] body) {
    return upload(attemptId, token, query, body, "multipart/form-data; boundary=" + BOUNDARY);
  }

  HttpResponse<String> upload(
      String attemptId, String token, String query, byte[] body, String contentType) {
    var builder =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + internalPort()
                        + "/internal/v1/attempts/"
                        + attemptId
                        + "/artifacts?"
                        + query))
            .header("Authorization", "Bearer " + TOKEN)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (token != null) {
      builder.header(ArtifactUploadController.LEASE_HEADER, token);
    }
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  Claim running() {
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    Claim claim = claim("chromium");
    assertThat(start(claim).statusCode()).isEqualTo(200);
    return claim;
  }

  List<Path> filesUnderRoot() throws Exception {
    try (Stream<Path> walk = Files.walk(Path.of(ARTIFACT_ROOT))) {
      return walk.filter(Files::isRegularFile).toList();
    }
  }

  private void assertNothingStored() throws Exception {
    assertThat(count("SELECT count(*) FROM artifacts")).isZero();
    assertThat(filesUnderRoot()).as("no file, not even a partial one").isEmpty();
  }

  private static String problemType(HttpResponse<String> response) {
    return JsonPath.read(response.body(), "$.type");
  }

  @Test
  void storesEachKindAndTheCompletionLinksScreenshotsAndTracesToTheirCase() throws Exception {
    Claim claim = running();
    byte[] screenshot = png(2048);

    var shot =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=SCREENSHOT&caseKey=cart-total",
            multipart(screenshot));
    var trace =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=TRACE&caseKey=cart-total",
            multipart(zip(4096)));
    var log =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=LOG",
            multipart("worker log tail ✓\n".getBytes(StandardCharsets.UTF_8)));

    assertThat(shot.statusCode()).as(shot.body()).isEqualTo(201);
    assertThat(trace.statusCode()).as(trace.body()).isEqualTo(201);
    assertThat(log.statusCode()).as(log.body()).isEqualTo(201);
    String shotId = JsonPath.read(shot.body(), "$.id");
    assertThat(shot.headers().firstValue("Location")).contains("/api/v1/artifacts/" + shotId);
    assertThat(JsonPath.<String>read(shot.body(), "$.mime")).isEqualTo("image/png");
    assertThat(JsonPath.<Integer>read(shot.body(), "$.byteSize")).isEqualTo(2048);
    assertThat(JsonPath.<String>read(shot.body(), "$.state")).isEqualTo("AVAILABLE");
    assertThat(JsonPath.<String>read(shot.body(), "$.sha256"))
        .isEqualTo(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(screenshot)));
    assertThat(shot.body()).doesNotContain("storageKey").doesNotContain(claim.token());
    assertThat(JsonPath.<String>read(log.body(), "$.mime")).isEqualTo("text/plain; charset=utf-8");

    Map<String, Object> row =
        row(
            "SELECT storage_key, case_key, extract(epoch FROM expires_at - created_at) AS ttl"
                + " FROM artifacts WHERE id = :id",
            shotId);
    assertThat(row).containsEntry("case_key", "cart-total");
    assertThat(((Number) row.get("ttl")).doubleValue()).isEqualTo(7 * 24 * 3600.0);
    try (var in = store.open((String) row.get("storage_key"))) {
      assertThat(in.readAllBytes()).isEqualTo(screenshot);
    }
    assertThat(
            one(
                "SELECT string_agg(type, ',' ORDER BY id) FROM run_events WHERE attempt_id = :id",
                claim.attemptId()))
        .isEqualTo(
            "TARGET_CLAIMED,TARGET_STARTED,ARTIFACT_AVAILABLE,ARTIFACT_AVAILABLE,ARTIFACT_AVAILABLE");

    String failed =
        "{\"caseKey\":\"cart-total\",\"status\":\"FAILED\",\"durationMs\":2912,\"retryCount\":0,"
            + "\"errorClass\":\"ASSERTION\",\"errorMessage\":\"Expected total 59.97\"}";
    var done =
        complete(
            claim,
            "attempt-" + claim.attemptId() + "-final",
            "COMPLETED_WITH_RESULTS",
            checkoutResults(Map.of("cart-total", failed)));
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);

    assertThat(
            count(
                "SELECT count(*) FROM artifacts a JOIN case_results c ON c.id = a.case_result_id"
                    + " WHERE c.case_key = 'cart-total' AND c.attempt_id = '"
                    + claim.attemptId()
                    + "'"))
        .isEqualTo(2);
    assertThat(
            count("SELECT count(*) FROM artifacts WHERE kind = 'LOG' AND case_result_id IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void art001OversizedUploadsAre413AndLeaveNothing() throws Exception {
    Claim claim = running();
    // Over the limit while streaming (the declared length is still within limit + overhead).
    var streamed =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=SCREENSHOT&caseKey=cart-total",
            multipart(png((int) ArtifactKind.SCREENSHOT.maxBytes() + 1)));
    // Refused from Content-Length alone, before the body is read.
    var declared =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=LOG",
            multipart(new byte[(int) (ArtifactKind.LOG.maxBytes() + 20_000)]));

    assertThat(streamed.statusCode()).as(streamed.body()).isEqualTo(413);
    assertThat(problemType(streamed)).endsWith("/artifact-too-large");
    assertThat(declared.statusCode()).as(declared.body()).isEqualTo(413);
    assertNothingStored();
  }

  @Test
  void art001WrongBytesOrContentTypeAre415AndLeaveNothing() throws Exception {
    Claim claim = running();
    var zipAsScreenshot =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=SCREENSHOT&caseKey=cart-total",
            multipart(zip(100)));
    var pngAsTrace =
        upload(
            claim.attemptId(), claim.token(), "kind=TRACE&caseKey=cart-total", multipart(png(100)));
    var binaryLog =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=LOG",
            multipart(new byte[] {'a', 0, (byte) 0xff}));
    var empty = upload(claim.attemptId(), claim.token(), "kind=LOG", multipart(new byte[0]));
    var json =
        upload(claim.attemptId(), claim.token(), "kind=LOG", "{}".getBytes(), "application/json");

    for (var response : List.of(zipAsScreenshot, pngAsTrace, binaryLog, empty, json)) {
      assertThat(response.statusCode()).as(response.body()).isEqualTo(415);
      assertThat(problemType(response)).endsWith("/unsupported-media-type");
    }
    assertNothingStored();
  }

  @Test
  void malformedMultipartIs400AndLeavesNothing() throws Exception {
    Claim claim = running();
    byte[] noClose =
        ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nhello")
            .getBytes(StandardCharsets.US_ASCII);
    var response = upload(claim.attemptId(), claim.token(), "kind=LOG", noClose);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(problemType(response)).endsWith("/validation-failed");
    assertNothingStored();
  }

  @Test
  void onlyTheCurrentLeaseHolderCanUpload() throws Exception {
    Claim claim = running();
    byte[] body = multipart("x".getBytes());

    var unknown = upload(UUID.randomUUID().toString(), claim.token(), "kind=LOG", body);
    var wrongToken = upload(claim.attemptId(), "A".repeat(43), "kind=LOG", body);
    var noToken = upload(claim.attemptId(), null, "kind=LOG", body);
    jdbc.sql("UPDATE attempts SET leased_until = now() - interval '1 second' WHERE id = :id")
        .param("id", UUID.fromString(claim.attemptId()))
        .update();
    var expired = upload(claim.attemptId(), claim.token(), "kind=LOG", body);

    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(wrongToken.statusCode()).isEqualTo(410);
    assertThat(problemType(wrongToken)).endsWith("/lease-expired");
    assertThat(noToken.statusCode()).isEqualTo(400);
    assertThat(expired.statusCode()).isEqualTo(410);
    assertNothingStored();
  }

  @Test
  void aFinishedAttemptTakesNoMoreArtifacts() throws Exception {
    Claim claim = running();
    complete(
        claim,
        "attempt-" + claim.attemptId() + "-final",
        "COMPLETED_WITH_RESULTS",
        checkoutResults(Map.of()));

    var late = upload(claim.attemptId(), claim.token(), "kind=LOG", multipart("late".getBytes()));

    assertThat(late.statusCode()).isEqualTo(409);
    assertThat(problemType(late)).endsWith("/attempt-not-active");
    assertNothingStored();
  }

  @Test
  void art003CaseKeysAndQueryAreChecked() throws Exception {
    Claim claim = running();
    byte[] shot = multipart(png(64));

    var logWithCase =
        upload(
            claim.attemptId(),
            claim.token(),
            "kind=LOG&caseKey=cart-total",
            multipart("x".getBytes()));
    var shotWithoutCase = upload(claim.attemptId(), claim.token(), "kind=SCREENSHOT", shot);
    var unknownCase =
        upload(claim.attemptId(), claim.token(), "kind=SCREENSHOT&caseKey=not-a-case", shot);
    var traversal =
        upload(claim.attemptId(), claim.token(), "kind=SCREENSHOT&caseKey=..%2F..%2Fetc", shot);
    var unknownParam =
        upload(claim.attemptId(), claim.token(), "kind=LOG&path=/tmp/x", multipart("x".getBytes()));
    var badKind = upload(claim.attemptId(), claim.token(), "kind=VIDEO", shot);
    var twice =
        upload(claim.attemptId(), claim.token(), "kind=LOG&kind=LOG", multipart("x".getBytes()));
    var forgedAttempt =
        upload("..%2F..%2Fetc", claim.token(), "kind=LOG", multipart("x".getBytes()));

    for (var response :
        List.of(
            logWithCase, shotWithoutCase, unknownCase, traversal, unknownParam, badKind, twice)) {
      assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
      assertThat(problemType(response)).endsWith("/validation-failed");
    }
    assertThat(forgedAttempt.statusCode()).isIn(400, 404);
    assertNothingStored();
  }

  @Test
  void anAttemptHoldsAtMost20Artifacts() throws Exception {
    Claim claim = running();
    for (int i = 0; i < ArtifactUploadService.MAX_ARTIFACTS_PER_ATTEMPT; i++) {
      assertThat(
              upload(
                      claim.attemptId(),
                      claim.token(),
                      "kind=LOG",
                      multipart(("log " + i).getBytes()))
                  .statusCode())
          .isEqualTo(201);
    }

    var oneMore = upload(claim.attemptId(), claim.token(), "kind=LOG", multipart("21".getBytes()));

    assertThat(oneMore.statusCode()).isEqualTo(413);
    assertThat(count("SELECT count(*) FROM artifacts")).isEqualTo(20);
    assertThat(filesUnderRoot()).hasSize(20);
  }

  @Test
  void aLeaseLostWhileStreamingDeletesTheStoredBytes() throws Exception {
    Claim claim = running();
    UUID attemptId = UUID.fromString(claim.attemptId());

    assertThatThrownBy(() ->
            uploads.upload(
                attemptId,
                claim.token(),
                ArtifactKind.LOG,
                null,
                () -> {
                  // The reaper's view: the lease runs out while the bytes are on the way.
                  jdbc.sql(
                          "UPDATE attempts SET leased_until = now() - interval '1 second' WHERE id = :id")
                      .param("id", attemptId)
                      .update();
                  return new ByteArrayInputStream("slow upload".getBytes());
                }))
        .hasMessageContaining("the lease has expired");
    assertNothingStored();
  }

  @Test
  void otherRequestsKeepThe64KbBodyCap() {
    Claim claim = running();
    String huge = "{\"leaseToken\":\"" + "a".repeat(70 * 1024) + "\"}";

    var response = internalPost("/internal/v1/attempts/" + claim.attemptId() + "/heartbeat", huge);

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void theUploadIsNotServedOnThePublicPort() throws Exception {
    Claim claim = running();
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + publicPort
                        + "/internal/v1/attempts/"
                        + claim.attemptId()
                        + "/artifacts?kind=LOG"))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .header(ArtifactUploadController.LEASE_HEADER, claim.token())
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart("x".getBytes())))
            .build();

    assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(404);
    assertNothingStored();
  }

  @Test
  void storedContentIsImmutableAndStateOnlyMovesForward() {
    Claim claim = running();
    var shot = upload(claim.attemptId(), claim.token(), "kind=LOG", multipart("x".getBytes()));
    UUID id = UUID.fromString(JsonPath.read(shot.body(), "$.id"));

    assertThatThrownBy(() ->
            jdbc.sql("UPDATE artifacts SET sha256 = repeat('0', 64) WHERE id = :id")
                .param("id", id)
                .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM artifacts WHERE id = :id").param("id", id).update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
            jdbc.sql("UPDATE artifacts SET state = 'PURGED' WHERE id = :id")
                .param("id", id)
                .update())
        .isInstanceOf(DataAccessException.class);
    jdbc.sql("UPDATE artifacts SET state = 'EXPIRED' WHERE id = :id").param("id", id).update();
    assertThatThrownBy(() ->
            jdbc.sql("UPDATE artifacts SET state = 'AVAILABLE' WHERE id = :id")
                .param("id", id)
                .update())
        .isInstanceOf(DataAccessException.class);
    jdbc.sql("UPDATE artifacts SET state = 'PURGED' WHERE id = :id").param("id", id).update();
    assertThat(one("SELECT state FROM artifacts WHERE id = :id", id)).isEqualTo("PURGED");
  }
}
