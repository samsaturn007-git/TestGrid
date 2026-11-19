package dev.testgrid.api.manifest;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.testgrid.api.common.CanonicalJson;
import dev.testgrid.api.common.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and validates the suite manifest (contracts §10, PRD-02 FR-2) and computes each suite's
 * revision. Every problem becomes an {@link InvalidManifestException} naming the file and the
 * offending field, so a bad manifest stops startup with a readable error.
 */
public class ManifestLoader {

  static final String MANIFEST_PATH = "manifest/suites.json";
  static final String SCHEMA_RESOURCE = "/schema/suite-manifest.schema.json";
  static final String REVISION_PREFIX = "testgrid-suite-revision/v1\n";
  private static final long MAX_FILE_BYTES = 1024 * 1024;
  private static final int MAX_REPORTED_ERRORS = 20;

  private final JsonMapper mapper =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final Schema schema;

  public ManifestLoader() {
    try (InputStream in = ManifestLoader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("manifest JSON Schema missing from classpath");
      }
      schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public List<LoadedSuite> load(Path workerRoot) {
    Path root = realRoot(workerRoot);
    Path file = root.resolve(MANIFEST_PATH);
    JsonNode tree = parse(file);

    List<Error> errors = schema.validate(tree);
    if (!errors.isEmpty()) {
      String details =
          errors.stream()
              .limit(MAX_REPORTED_ERRORS)
              .map(e -> "  " + e.getInstanceLocation() + ": " + e.getMessage())
              .collect(Collectors.joining("\n"));
      throw new InvalidManifestException(
          file + " does not match the manifest schema (" + errors.size() + " errors):\n" + details);
    }

    SuiteManifest manifest = mapper.treeToValue(tree, SuiteManifest.class);
    checkUniqueSuiteKeys(file, manifest);

    List<LoadedSuite> loaded = new ArrayList<>();
    for (int i = 0; i < manifest.suites().size(); i++) {
      SuiteManifest.Suite suite = manifest.suites().get(i);
      String where = file + " $.suites[" + i + "] (" + suite.suiteKey() + ")";
      checkCases(where, suite);
      String revision = revision(where, root, tree.get("suites").get(i), suite.specFiles());
      loaded.add(new LoadedSuite(suite, revision));
    }
    return List.copyOf(loaded);
  }

  /**
   * Revision = first 12 hex chars of sha256 over: the prefix line, the canonical JSON of the
   * manifest entry as written (defaults not applied), then for each spec file in manifest order its
   * path and the sha256 hex of its bytes, each on its own line. Any change to the entry or a spec
   * file gives a new revision; reformatting the manifest does not.
   */
  private String revision(String where, Path root, JsonNode entry, List<String> specFiles) {
    StringBuilder input = new StringBuilder(REVISION_PREFIX);
    input.append(CanonicalJson.write(entry)).append('\n');
    for (String spec : specFiles) {
      byte[] bytes = readSpec(where, root, spec);
      input.append(spec).append('\n').append(Hashing.sha256Hex(bytes)).append('\n');
    }
    return Hashing.sha256Hex(input.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
  }

  private byte[] readSpec(String where, Path root, String spec) {
    Path suitesDir = root.resolve("suites");
    Path path = root.resolve(spec).normalize();
    try {
      // The schema already forbids "..", but a symlink could still point outside the worker
      // directory; compare real paths so the API only ever hashes files under suites/.
      Path real = path.toRealPath();
      if (!real.startsWith(suitesDir.toRealPath())
          || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
        throw new InvalidManifestException(
            where + ": spec file " + spec + " must be a regular file inside " + suitesDir);
      }
      if (Files.size(real) > MAX_FILE_BYTES) {
        throw new InvalidManifestException(where + ": spec file " + spec + " is larger than 1 MiB");
      }
      return Files.readAllBytes(real);
    } catch (NoSuchFileException e) {
      throw new InvalidManifestException(where + ": spec file " + spec + " does not exist", e);
    } catch (IOException e) {
      throw new InvalidManifestException(where + ": cannot read spec file " + spec, e);
    }
  }

  private static void checkUniqueSuiteKeys(Path file, SuiteManifest manifest) {
    Set<String> seen = new HashSet<>();
    for (SuiteManifest.Suite suite : manifest.suites()) {
      if (!seen.add(suite.suiteKey())) {
        throw new InvalidManifestException(file + ": duplicate suiteKey " + suite.suiteKey());
      }
    }
  }

  private static void checkCases(String where, SuiteManifest.Suite suite) {
    Set<String> seen = new HashSet<>();
    for (SuiteManifest.Case c : suite.cases()) {
      if (!seen.add(c.caseKey())) {
        throw new InvalidManifestException(where + ": duplicate caseKey " + c.caseKey());
      }
      if (!c.tag().equals("@" + c.caseKey())) {
        throw new InvalidManifestException(
            where
                + ": case "
                + c.caseKey()
                + " must have tag @"
                + c.caseKey()
                + ", not "
                + c.tag());
      }
    }
  }

  private JsonNode parse(Path file) {
    try {
      if (Files.size(file) > MAX_FILE_BYTES) {
        throw new InvalidManifestException(file + " is larger than 1 MiB");
      }
      return mapper.readTree(Files.readAllBytes(file));
    } catch (NoSuchFileException e) {
      throw new InvalidManifestException("suite manifest not found at " + file, e);
    } catch (IOException e) {
      throw new InvalidManifestException("cannot read suite manifest " + file, e);
    } catch (JacksonException e) {
      throw new InvalidManifestException(file + " is not valid JSON: " + e.getOriginalMessage(), e);
    }
  }

  private static Path realRoot(Path workerRoot) {
    if (workerRoot == null) {
      throw new InvalidManifestException("testgrid.manifest.root is not set");
    }
    try {
      return workerRoot.toRealPath();
    } catch (IOException e) {
      throw new InvalidManifestException(
          "manifest root " + workerRoot.toAbsolutePath() + " does not exist", e);
    }
  }
}
