package dev.testgrid.api.manifest;

import static dev.testgrid.api.manifest.ManifestFixtures.appendToSpec;
import static dev.testgrid.api.manifest.ManifestFixtures.copyOfRepoWorker;
import static dev.testgrid.api.manifest.ManifestFixtures.editManifest;
import static dev.testgrid.api.manifest.ManifestFixtures.firstSuite;
import static dev.testgrid.api.manifest.ManifestFixtures.writeManifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.common.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ManifestLoaderTest {

  private final ManifestLoader loader = new ManifestLoader();
  @TempDir Path tmp;

  @Test
  void loadsTheRepositoryManifest() {
    List<LoadedSuite> suites = loader.load(ManifestFixtures.REPO_WORKER_ROOT);

    assertThat(suites)
        .extracting(s -> s.suite().suiteKey())
        .containsExactly("checkout-smoke", "catalog-regression");
    assertThat(suites.get(1).suite().cases()).hasSize(12);
    assertThat(suites.get(1).revision()).matches("^[0-9a-f]{12}$");
    LoadedSuite smoke = suites.getFirst();
    assertThat(smoke.suite().suiteKey()).isEqualTo("checkout-smoke");
    assertThat(smoke.suite().cases())
        .extracting(SuiteManifest.Case::caseKey)
        .containsExactly(
            "browse-catalog",
            "add-to-cart",
            "cart-total",
            "apply-coupon",
            "checkout-happy-path",
            "card-declined");
    assertThat(smoke.revision()).matches("^[0-9a-f]{12}$");
    assertThat(loader.load(ManifestFixtures.REPO_WORKER_ROOT).getFirst().revision())
        .isEqualTo(smoke.revision());
  }

  /** Pins the revision algorithm (contracts §10) so the worker can reproduce it byte for byte. */
  @Test
  void revisionFollowsTheDocumentedAlgorithm() throws IOException {
    Files.createDirectories(tmp.resolve("manifest"));
    Files.createDirectories(tmp.resolve("suites"));
    byte[] spec = "test('x', () => {});\n".getBytes(StandardCharsets.UTF_8);
    Files.write(tmp.resolve("suites/mini.spec.ts"), spec);
    writeManifest(
        tmp,
        """
        {"schemaVersion": 1, "suites": [{
          "suiteKey": "mini", "displayName": "Mini", "specFiles": ["suites/mini.spec.ts"],
          "allowedEngines": ["chromium"], "timeoutMs": 60000, "playwrightRetries": 0,
          "baseUrlRef": "DEMO_SHOP_INTERNAL", "fixtureModes": ["PASS"],
          "cases": [{"caseKey": "x", "title": "x", "tag": "@x", "critical": false}]
        }]}
        """);

    String canonicalEntry =
        "{\"allowedEngines\":[\"chromium\"],\"baseUrlRef\":\"DEMO_SHOP_INTERNAL\","
            + "\"cases\":[{\"caseKey\":\"x\",\"critical\":false,\"tag\":\"@x\",\"title\":\"x\"}],"
            + "\"displayName\":\"Mini\",\"fixtureModes\":[\"PASS\"],\"playwrightRetries\":0,"
            + "\"specFiles\":[\"suites/mini.spec.ts\"],\"suiteKey\":\"mini\",\"timeoutMs\":60000}";
    String input =
        "testgrid-suite-revision/v1\n"
            + canonicalEntry
            + "\n"
            + "suites/mini.spec.ts\n"
            + HexFormat.of().formatHex(Hashing.sha256(spec))
            + "\n";
    String expected = Hashing.sha256Hex(input.getBytes(StandardCharsets.UTF_8)).substring(0, 12);

    assertThat(loader.load(tmp).getFirst().revision()).isEqualTo(expected);
    // The worker's manifest test (worker/test/manifest.test.ts) pins the same value, so the two
    // implementations of contracts §10 are checked against each other.
    assertThat(expected).isEqualTo("3f3ac231f888");
  }

  @Test
  void reformattingTheManifestKeepsTheRevision() {
    Path root = copyOfRepoWorker(tmp);
    String before = loader.load(root).getFirst().revision();

    ObjectNode manifest = ManifestFixtures.readManifest(root);
    writeManifest(
        root,
        ManifestFixtures.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

    assertThat(loader.load(root).getFirst().revision()).isEqualTo(before);
  }

  @Test
  void changingASpecFileChangesTheRevision() {
    Path root = copyOfRepoWorker(tmp);
    String before = loader.load(root).getFirst().revision();

    appendToSpec(root, "// changed\n");

    assertThat(loader.load(root).getFirst().revision()).isNotEqualTo(before);
  }

  @Test
  void changingTheManifestEntryChangesTheRevision() {
    Path root = copyOfRepoWorker(tmp);
    String before = loader.load(root).getFirst().revision();

    editManifest(root, m -> firstSuite(m).put("timeoutMs", 90000));

    assertThat(loader.load(root).getFirst().revision()).isNotEqualTo(before);
  }

  @Test
  void missingManifestIsRejected() {
    assertThatThrownBy(() -> loader.load(tmp))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("suite manifest not found");
  }

  @Test
  void malformedJsonIsRejected() {
    Path root = copyOfRepoWorker(tmp);
    writeManifest(root, "{\"schemaVersion\": 1, \"suites\": [");
    assertInvalid(root, "is not valid JSON");
  }

  @Test
  void duplicateJsonKeysAreRejected() {
    Path root = copyOfRepoWorker(tmp);
    writeManifest(root, "{\"schemaVersion\": 1, \"schemaVersion\": 1, \"suites\": []}");
    assertInvalid(root, "is not valid JSON");
  }

  @Test
  void unknownFieldIsRejectedWithItsLocation() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(root, m -> firstSuite(m).put("command", "rm -rf /"));
    assertInvalid(root, "does not match the manifest schema", "/suites/0: property 'command'");
  }

  @Test
  void unknownEngineIsRejected() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(root, m -> ((ArrayNode) firstSuite(m).get("allowedEngines")).add("edge"));
    assertInvalid(root, "/suites/0/allowedEngines/3");
  }

  @Test
  void aUrlInsteadOfABaseUrlRefIsRejected() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(root, m -> firstSuite(m).put("baseUrlRef", "http://169.254.169.254/"));
    assertInvalid(root, "/suites/0/baseUrlRef");
  }

  @Test
  void specPathOutsideSuitesIsRejectedBySchema() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(
        root,
        m -> {
          ArrayNode specs = (ArrayNode) firstSuite(m).get("specFiles");
          specs.removeAll();
          specs.add("suites/../../etc/passwd.spec.ts");
        });
    assertInvalid(root, "/suites/0/specFiles/0");
  }

  @Test
  void symlinkEscapingSuitesIsRejected() throws IOException {
    Path root = copyOfRepoWorker(tmp.resolve("worker"));
    Path outside = Files.writeString(tmp.resolve("outside.ts"), "secret");
    Files.delete(root.resolve(ManifestFixtures.SPEC));
    Files.createSymbolicLink(root.resolve(ManifestFixtures.SPEC), outside);
    assertInvalid(root, "must be a regular file inside");
  }

  @Test
  void missingSpecFileIsRejected() throws IOException {
    Path root = copyOfRepoWorker(tmp);
    Files.delete(root.resolve(ManifestFixtures.SPEC));
    assertInvalid(root, "spec file suites/checkout-smoke.spec.ts does not exist");
  }

  @Test
  void duplicateSuiteKeyIsRejected() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(root, m -> ((ArrayNode) m.get("suites")).add(firstSuite(m).deepCopy()));
    assertInvalid(root, "duplicate suiteKey checkout-smoke");
  }

  @Test
  void duplicateCaseKeyIsRejected() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(
        root,
        m -> {
          ArrayNode cases = (ArrayNode) firstSuite(m).get("cases");
          cases.add(cases.get(0).deepCopy());
        });
    assertInvalid(root, "duplicate caseKey browse-catalog");
  }

  @Test
  void tagMustMatchCaseKey() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(
        root, m -> ((ObjectNode) firstSuite(m).get("cases").get(0)).put("tag", "@cart-total"));
    assertInvalid(root, "case browse-catalog must have tag @browse-catalog");
  }

  private void assertInvalid(Path root, String... messageParts) {
    assertThatThrownBy(() -> loader.load(root))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContainingAll(messageParts);
  }
}
