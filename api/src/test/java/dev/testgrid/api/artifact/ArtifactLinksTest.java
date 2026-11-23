package dev.testgrid.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PRD-06 FR-4 / T4, ART-003: what a signature binds, and what it refuses. */
class ArtifactLinksTest {

  static final String KEY = "test-only-link-key-0123456789abcdef";
  final ArtifactLinks links = new ArtifactLinks(properties(KEY));
  final UUID id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

  static ArtifactProperties properties(String key) {
    return new ArtifactProperties(Path.of("unused"), Duration.ofDays(7), key, 300);
  }

  @Test
  void aSignatureVerifiesForExactlyItsArtifactAndExpiry() {
    String sig = links.sign(id, 1_800_000_000L);

    assertThat(sig).matches("^[A-Za-z0-9_-]{43}$");
    assertThat(links.verify(id, "1800000000", sig)).hasValue(1_800_000_000L);
    assertThat(links.verify(UUID.randomUUID(), "1800000000", sig)).isEmpty();
    assertThat(links.verify(id, "1800000001", sig)).isEmpty();
  }

  @Test
  void art003RefusesMissingMalformedAndForgedSignatures() {
    String sig = links.sign(id, 1_800_000_000L);
    char last = sig.charAt(42);
    String flipped = sig.substring(0, 42) + (last == 'A' ? 'B' : 'A');

    assertThat(links.verify(id, null, sig)).isEmpty();
    assertThat(links.verify(id, "1800000000", null)).isEmpty();
    assertThat(links.verify(id, "1800000000", flipped)).isEmpty();
    assertThat(links.verify(id, "-1", sig)).isEmpty();
    assertThat(links.verify(id, "1.8e9", sig)).isEmpty();
    assertThat(links.verify(id, "1800000000", sig + "=")).isEmpty();
    assertThat(links.verify(id, "1800000000", "../../etc/passwd")).isEmpty();
  }

  @Test
  void anotherKeyNeverVerifies() {
    ArtifactLinks other = new ArtifactLinks(properties("another-test-only-key-0123456789abc"));
    assertThat(other.verify(id, "1800000000", links.sign(id, 1_800_000_000L))).isEmpty();
    // No key configured: a random one, so nothing signed elsewhere verifies either.
    assertThat(
            new ArtifactLinks(properties(null))
                .verify(id, "1800000000", links.sign(id, 1_800_000_000L)))
        .isEmpty();
  }

  @Test
  void theKeyAndTtlAreValidatedAndTheKeyIsNeverPrinted() {
    assertThatThrownBy(() -> properties("too-short")).hasMessageContaining("at least 32");
    assertThatThrownBy(() -> new ArtifactProperties(Path.of("x"), Duration.ofDays(7), KEY, 301))
        .hasMessageContaining("1..300");
    assertThat(properties(KEY).toString()).doesNotContain(KEY).contains("[REDACTED]");
  }
}
