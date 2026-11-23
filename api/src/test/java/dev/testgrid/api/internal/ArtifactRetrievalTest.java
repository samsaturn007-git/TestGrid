package dev.testgrid.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.artifact.ArtifactKind;
import dev.testgrid.api.artifact.ArtifactLinks;
import dev.testgrid.api.artifact.ArtifactStore;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PRD-06 T4 over real HTTP on the public port: metadata, signed links and content. ART-002 (expired
 * is 410 and still described), ART-003 (unsigned, forged, moved and expired links are 403; a forged
 * ID reaches no file).
 */
class ArtifactRetrievalTest extends WorkerApiTestBase {

  @Autowired ArtifactStore store;
  @Autowired ArtifactLinks links;

  Claim claim;
  byte[] screenshot;

  @BeforeEach
  void uploadOne() throws Exception {
    for (String key : store.keys()) {
      store.delete(key);
    }
    createRun("checkout-smoke", "PRODUCT_DEFECT", "chromium");
    claim = claim("chromium");
    start(claim);
    screenshot = ArtifactUploadTest.png(1500);
  }

  String upload(ArtifactKind kind, String caseKey, byte[] content) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + internalPort()
                        + "/internal/v1/attempts/"
                        + claim.attemptId()
                        + "/artifacts?kind="
                        + kind
                        + (caseKey == null ? "" : "&caseKey=" + caseKey)))
            .header("Authorization", "Bearer " + TOKEN)
            .header("Content-Type", "multipart/form-data; boundary=" + ArtifactUploadTest.BOUNDARY)
            .header(ArtifactUploadController.LEASE_HEADER, claim.token())
            .POST(HttpRequest.BodyPublishers.ofByteArray(ArtifactUploadTest.multipart(content)))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return JsonPath.read(response.body(), "$.id");
  }

  HttpResponse<byte[]> getBytes(String pathAndQuery) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + pathAndQuery)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  HttpResponse<String> link(String id) {
    return post(publicPort, "/api/v1/artifacts/" + id + "/links", "", null);
  }

  String type(HttpResponse<?> response) {
    String body =
        response.body() instanceof byte[] b ? new String(b) : String.valueOf(response.body());
    return JsonPath.read(body, "$.type");
  }

  void setState(String id, String state) {
    jdbc.sql("UPDATE artifacts SET state = :s WHERE id = :id")
        .param("s", state)
        .param("id", UUID.fromString(id))
        .update();
  }

  @Test
  void metadataDescribesTheArtifactWithoutItsStorageKey() throws Exception {
    String id = upload(ArtifactKind.SCREENSHOT, "cart-total", screenshot);

    var response = get(publicPort, "/api/v1/artifacts/" + id);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.kind")).isEqualTo("SCREENSHOT");
    assertThat(JsonPath.<String>read(response.body(), "$.caseKey")).isEqualTo("cart-total");
    assertThat(JsonPath.<String>read(response.body(), "$.state")).isEqualTo("AVAILABLE");
    assertThat(JsonPath.<String>read(response.body(), "$.attemptId")).isEqualTo(claim.attemptId());
    assertThat(response.body()).doesNotContain("storage").doesNotContain("2026/");
    assertThat(get(publicPort, "/api/v1/artifacts/" + UUID.randomUUID()).statusCode())
        .isEqualTo(404);
  }

  @Test
  void aSignedLinkStreamsTheBytesAsAHardenedAttachment() throws Exception {
    String id = upload(ArtifactKind.SCREENSHOT, "cart-total", screenshot);

    var issued = link(id);
    assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
    String url = JsonPath.read(issued.body(), "$.url");
    Instant expiresAt = Instant.parse(JsonPath.read(issued.body(), "$.expiresAt"));
    assertThat(url)
        .matches("^/api/v1/artifacts/" + id + "/content\\?expires=[0-9]+&sig=[A-Za-z0-9_-]{43}$");
    assertThat(expiresAt).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(305));

    var content = getBytes(url);

    assertThat(content.statusCode()).isEqualTo(200);
    assertThat(content.body()).isEqualTo(screenshot);
    var headers = content.headers();
    assertThat(headers.firstValue("Content-Type")).contains("image/png");
    assertThat(headers.firstValue("Content-Length")).contains("1500");
    assertThat(headers.firstValue("Content-Disposition"))
        .contains("attachment; filename=\"screenshot-" + id + ".png\"");
    assertThat(headers.firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(headers.firstValue("Content-Security-Policy"))
        .contains("default-src 'none'; img-src 'self'; sandbox");
    assertThat(headers.firstValue("Cache-Control")).contains("private, no-store");
    assertThat(headers.firstValue("Cross-Origin-Resource-Policy")).contains("same-origin");
    assertThat(headers.firstValue("Referrer-Policy")).contains("no-referrer");
  }

  @Test
  void aTraceDownloadsUnderAPlaywrightFriendlyName() throws Exception {
    String id = upload(ArtifactKind.TRACE, "cart-total", ArtifactUploadTest.zip(800));

    var content = getBytes(JsonPath.read(link(id).body(), "$.url"));

    assertThat(content.headers().firstValue("Content-Type")).contains("application/zip");
    assertThat(content.headers().firstValue("Content-Disposition"))
        .contains("attachment; filename=\"trace-" + id + ".zip\"");
  }

  @Test
  void art003UnsignedForgedMovedAndExpiredLinksAre403() throws Exception {
    String id = upload(ArtifactKind.SCREENSHOT, "cart-total", screenshot);
    String other = upload(ArtifactKind.LOG, null, "log".getBytes());
    String url = JsonPath.read(link(id).body(), "$.url");
    String query = url.substring(url.indexOf('?'));
    long expires = Long.parseLong(query.replaceAll(".*expires=([0-9]+).*", "$1"));
    String sig = query.replaceAll(".*sig=", "");
    String base = "/api/v1/artifacts/" + id + "/content";

    var unsigned = getBytes(base);
    var noSig = getBytes(base + "?expires=" + expires);
    var forged = getBytes(base + "?expires=" + expires + "&sig=" + "A".repeat(43));
    var extended = getBytes(base + "?expires=" + (expires + 60) + "&sig=" + sig);
    var moved = getBytes("/api/v1/artifacts/" + other + "/content" + query);
    long past = Instant.now().getEpochSecond() - 1;
    var expired =
        getBytes(base + "?expires=" + past + "&sig=" + links.sign(UUID.fromString(id), past));
    long far = Instant.now().getEpochSecond() + 3600;
    var tooLong =
        getBytes(base + "?expires=" + far + "&sig=" + links.sign(UUID.fromString(id), far));

    for (var response :
        java.util.List.of(unsigned, noSig, forged, extended, moved, expired, tooLong)) {
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(type(response)).endsWith("/forbidden");
      assertThat(response.body()).isNotEqualTo(screenshot);
    }
  }

  @Test
  void art003ForgedIdsAndExtraParametersReachNoFile() throws Exception {
    String id = upload(ArtifactKind.SCREENSHOT, "cart-total", screenshot);
    String query = JsonPath.<String>read(link(id).body(), "$.url").replaceAll(".*\\?", "?");
    UUID unknown = UUID.randomUUID();
    long expires = Instant.now().getEpochSecond() + 60;

    var traversal = getBytes("/api/v1/artifacts/..%2F..%2Fetc%2Fpasswd/content" + query);
    var notUuid = getBytes("/api/v1/artifacts/2026-11-23-x/content" + query);
    var signedButUnknown =
        getBytes(
            "/api/v1/artifacts/"
                + unknown
                + "/content?expires="
                + expires
                + "&sig="
                + links.sign(unknown, expires));
    var extra = getBytes("/api/v1/artifacts/" + id + "/content" + query + "&key=2026/11/23/x.png");

    assertThat(traversal.statusCode()).isIn(400, 404);
    assertThat(notUuid.statusCode()).isEqualTo(400);
    assertThat(signedButUnknown.statusCode()).isEqualTo(404);
    assertThat(extra.statusCode()).isEqualTo(400);
    assertThat(link(unknown.toString()).statusCode()).isEqualTo(404);
  }

  @Test
  void art002AnExpiredArtifactIs410AndStillDescribed() throws Exception {
    String id = upload(ArtifactKind.SCREENSHOT, "cart-total", screenshot);
    String url = JsonPath.read(link(id).body(), "$.url");

    setState(id, "EXPIRED");

    var meta = get(publicPort, "/api/v1/artifacts/" + id);
    assertThat(meta.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(meta.body(), "$.state")).isEqualTo("EXPIRED");
    var newLink = link(id);
    assertThat(newLink.statusCode()).isEqualTo(410);
    assertThat(type(newLink)).endsWith("/artifact-expired");
    // A link issued before the expiry stops working too.
    var content = getBytes(url);
    assertThat(content.statusCode()).isEqualTo(410);

    setState(id, "PURGED");
    assertThat(link(id).statusCode()).isEqualTo(410);
    assertThat(getBytes(url).statusCode()).isEqualTo(410);
  }

  @Test
  void art002PastExpiresAtIsExpiredBeforeThePurgeJobRuns() throws Exception {
    ArtifactStore.Stored bytes =
        store.put(
            new ByteArrayInputStream(screenshot),
            new ArtifactStore.Meta(ArtifactKind.SCREENSHOT, Instant.now()));
    UUID id =
        jdbc.sql(
                "INSERT INTO artifacts (attempt_id, case_key, kind, mime, storage_key, byte_size,"
                    + " sha256, expires_at, created_at) VALUES (:a, 'cart-total', 'SCREENSHOT',"
                    + " 'image/png', :k, :s, :sha, now() - interval '1 minute', now() - interval '8 days')"
                    + " RETURNING id")
            .param("a", UUID.fromString(claim.attemptId()))
            .param("k", bytes.key())
            .param("s", bytes.byteSize())
            .param("sha", bytes.sha256())
            .query(UUID.class)
            .single();

    var meta = get(publicPort, "/api/v1/artifacts/" + id);
    assertThat(JsonPath.<String>read(meta.body(), "$.state")).isEqualTo("EXPIRED");
    assertThat(link(id.toString()).statusCode()).isEqualTo(410);
    long expires = Instant.now().getEpochSecond() + 60;
    assertThat(
            getBytes(
                    "/api/v1/artifacts/"
                        + id
                        + "/content?expires="
                        + expires
                        + "&sig="
                        + links.sign(id, expires))
                .statusCode())
        .isEqualTo(410);
  }

  @Test
  void art002MissingBytesAre404WhetherMarkedOrNot() throws Exception {
    String marked = upload(ArtifactKind.SCREENSHOT, "cart-total", screenshot);
    String url = JsonPath.read(link(marked).body(), "$.url");
    setState(marked, "MISSING");

    assertThat(link(marked).statusCode()).isEqualTo(404);
    assertThat(getBytes(url).statusCode()).isEqualTo(404);
    assertThat(
            JsonPath.<String>read(get(publicPort, "/api/v1/artifacts/" + marked).body(), "$.state"))
        .isEqualTo("MISSING");

    String unmarked = upload(ArtifactKind.TRACE, "cart-total", ArtifactUploadTest.zip(100));
    String unmarkedUrl = JsonPath.read(link(unmarked).body(), "$.url");
    String key =
        jdbc.sql("SELECT storage_key FROM artifacts WHERE id = :id")
            .param("id", UUID.fromString(unmarked))
            .query(String.class)
            .single();
    store.delete(key);

    var response = getBytes(unmarkedUrl);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(type(response)).endsWith("/not-found");
  }

  @Test
  void theContentIsNotServedOnTheInternalPort() throws Exception {
    String id = upload(ArtifactKind.SCREENSHOT, "cart-total", screenshot);
    String url = JsonPath.read(link(id).body(), "$.url");

    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + internalPort() + url))
                .header("Authorization", "Bearer " + TOKEN)
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }
}
