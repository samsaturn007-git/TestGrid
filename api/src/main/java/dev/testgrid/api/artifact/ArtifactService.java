package dev.testgrid.api.artifact;

import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public artifact reads (PRD-06 FR-4, contracts §4). The decisions, in order:
 *
 * <ul>
 *   <li>content only: a missing, forged, moved or expired link is {@code 403 forbidden}, checked
 *       before any lookup;
 *   <li>an unknown artifact, or one outside the caller's project, is {@code 404 not-found};
 *   <li>{@code MISSING} (the bytes are gone) is {@code 404}; {@code EXPIRED}, {@code PURGED} or
 *       past {@code expires_at} is {@code 410 artifact-expired}.
 * </ul>
 *
 * Every time comparison uses the database clock.
 */
@Service
public class ArtifactService {

  private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

  public record Link(String url, Instant expiresAt) {}

  public record Content(ArtifactView artifact, InputStream bytes) {}

  private final ArtifactRepository artifacts;
  private final ArtifactStore store;
  private final ArtifactLinks links;
  private final ArtifactProperties properties;
  private final CallerScope scope;

  public ArtifactService(
      ArtifactRepository artifacts,
      ArtifactStore store,
      ArtifactLinks links,
      ArtifactProperties properties,
      CallerScope scope) {
    this.artifacts = artifacts;
    this.store = store;
    this.links = links;
    this.properties = properties;
    this.scope = scope;
  }

  public ArtifactView metadata(UUID artifactId) {
    ArtifactRepository.Found found = visible(artifactId);
    return found.artifact().view().effective(found.pastExpiry());
  }

  public Link link(UUID artifactId) {
    ArtifactRepository.Found found = readable(visible(artifactId));
    long expires = found.dbNow().getEpochSecond() + properties.linkTtlSeconds();
    String url =
        "/api/v1/artifacts/"
            + artifactId
            + "/content?expires="
            + expires
            + "&sig="
            + links.sign(artifactId, expires);
    return new Link(url, Instant.ofEpochSecond(expires));
  }

  public Content open(UUID artifactId, String expires, String sig) throws IOException {
    OptionalLong linkExpiry = links.verify(artifactId, expires, sig);
    if (linkExpiry.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.FORBIDDEN, "a valid signed link is required (POST .../links)");
    }
    ArtifactRepository.Found found = visible(artifactId);
    long now = found.dbNow().getEpochSecond();
    // Also refuses a link that claims to last longer than this server ever signs.
    if (linkExpiry.getAsLong() <= now
        || linkExpiry.getAsLong() > now + ArtifactProperties.MAX_LINK_TTL_SECONDS) {
      throw new ApiProblemException(ProblemType.FORBIDDEN, "the signed link has expired");
    }
    readable(found);
    try {
      return new Content(found.artifact().view(), store.open(found.artifact().storageKey()));
    } catch (NoSuchFileException e) {
      // The orphan sweeper (FR-6) marks the row MISSING; until then the answer is the same.
      log.warn("artifact {} is AVAILABLE but its bytes are gone", artifactId);
      throw new ApiProblemException(
          ProblemType.NOT_FOUND, "the bytes of artifact " + artifactId + " are missing");
    }
  }

  private ArtifactRepository.Found visible(UUID artifactId) {
    return artifacts
        .find(artifactId)
        .filter(f -> scope.canSee(f.projectId()))
        .orElseThrow(() ->
            new ApiProblemException(
                ProblemType.NOT_FOUND, "artifact " + artifactId + " not found"));
  }

  private static ArtifactRepository.Found readable(ArtifactRepository.Found found) {
    ArtifactView view = found.artifact().view();
    switch (view.effective(found.pastExpiry()).state()) {
      case AVAILABLE -> {
        return found;
      }
      case MISSING ->
          throw new ApiProblemException(
              ProblemType.NOT_FOUND, "the bytes of artifact " + view.id() + " are missing");
      case EXPIRED, PURGED ->
          throw new ApiProblemException(
              ProblemType.ARTIFACT_EXPIRED,
              "artifact "
                  + view.id()
                  + " expired at "
                  + view.expiresAt()
                  + " and is no longer kept");
    }
    throw new IllegalStateException("unreachable");
  }
}
