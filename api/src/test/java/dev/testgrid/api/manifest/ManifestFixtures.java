package dev.testgrid.api.manifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Copies of the repository's worker manifest in a temp dir, so tests can break one thing. */
final class ManifestFixtures {

  static final Path REPO_WORKER_ROOT = Path.of("../worker");

  /** The checkout-smoke spec, the first suite's only spec file; tests edit it to move revisions. */
  static final String SPEC = "suites/checkout-smoke.spec.ts";

  static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ManifestFixtures() {}

  static Path copyOfRepoWorker(Path dir) {
    try {
      Files.createDirectories(dir.resolve("manifest"));
      Files.createDirectories(dir.resolve("suites"));
      Files.copy(
          REPO_WORKER_ROOT.resolve(ManifestLoader.MANIFEST_PATH),
          dir.resolve(ManifestLoader.MANIFEST_PATH));
      for (JsonNode suite : readManifest(dir).get("suites")) {
        for (JsonNode spec : suite.get("specFiles")) {
          Files.copy(REPO_WORKER_ROOT.resolve(spec.asString()), dir.resolve(spec.asString()));
        }
      }
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ObjectNode readManifest(Path root) {
    try {
      return (ObjectNode)
          MAPPER.readTree(Files.readString(root.resolve(ManifestLoader.MANIFEST_PATH)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void editManifest(Path root, Consumer<ObjectNode> edit) {
    ObjectNode manifest = readManifest(root);
    edit.accept(manifest);
    writeManifest(root, MAPPER.writeValueAsString(manifest));
  }

  static ObjectNode firstSuite(ObjectNode manifest) {
    return (ObjectNode) manifest.get("suites").get(0);
  }

  static void writeManifest(Path root, String json) {
    try {
      Files.writeString(root.resolve(ManifestLoader.MANIFEST_PATH), json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void appendToSpec(Path root, String text) {
    try {
      Files.writeString(root.resolve(SPEC), Files.readString(root.resolve(SPEC)) + text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
