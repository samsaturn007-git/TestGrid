package dev.testgrid.api.internal;

import dev.testgrid.api.artifact.ArtifactKind;
import dev.testgrid.api.artifact.ArtifactProperties;
import dev.testgrid.api.artifact.ArtifactRepository;
import dev.testgrid.api.artifact.ArtifactStore;
import dev.testgrid.api.artifact.ArtifactValidatingStream;
import dev.testgrid.api.artifact.ArtifactView;
import dev.testgrid.api.common.LogContext;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.internal.AttemptRepository.LockedAttempt;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /internal/v1/attempts/{id}/artifacts} (PRD-06 FR-3, ADR-005). Three steps, so no
 * database lock is held while bytes stream (a 50 MB trace would otherwise block the attempt's own
 * heartbeats past its lease):
 *
 * <ol>
 *   <li>a short transaction checks the lease, the case key and the attempt's quota;
 *   <li>the body streams through the validator into the store, outside any transaction;
 *   <li>a second transaction checks the lease and quota again (the attempt may have been abandoned
 *       meanwhile), inserts the row and an {@code ARTIFACT_AVAILABLE} event.
 * </ol>
 *
 * If step 3 fails the stored bytes are deleted. A crash between 2 and 3 leaves a file without a
 * row, which the orphan sweeper removes (FR-6).
 */
@Service
public class ArtifactUploadService {

  /** Bounds storage per attempt: a few screenshots and traces per failed case plus one log. */
  static final int MAX_ARTIFACTS_PER_ATTEMPT = 20;

  static final long MAX_BYTES_PER_ATTEMPT = 200L * 1024 * 1024;

  private static final Logger log = LoggerFactory.getLogger(ArtifactUploadService.class);

  /** Opens the multipart part only after the lease has been checked. */
  @FunctionalInterface
  public interface Body {
    InputStream open() throws IOException;
  }

  private final AttemptService attempts;
  private final AttemptRepository attemptRows;
  private final ArtifactRepository artifacts;
  private final ArtifactStore store;
  private final ArtifactProperties properties;
  private final TransactionTemplate tx;
  private final JsonMapper mapper;

  public ArtifactUploadService(
      AttemptService attempts,
      AttemptRepository attemptRows,
      ArtifactRepository artifacts,
      ArtifactStore store,
      ArtifactProperties properties,
      TransactionTemplate tx,
      JsonMapper mapper) {
    this.attempts = attempts;
    this.attemptRows = attemptRows;
    this.artifacts = artifacts;
    this.store = store;
    this.properties = properties;
    this.tx = tx;
    this.mapper = mapper;
  }

  public ArtifactView upload(
      UUID attemptId, String leaseToken, ArtifactKind kind, String caseKey, Body body)
      throws IOException {
    tx.executeWithoutResult(s -> admit(attemptId, leaseToken, kind, caseKey, 0));
    ArtifactStore.Stored object;
    try (InputStream part = body.open()) {
      object =
          store.put(
              new ArtifactValidatingStream(part, kind),
              new ArtifactStore.Meta(kind, Instant.now()));
    }
    try {
      return tx.execute(s -> {
        LockedAttempt attempt = admit(attemptId, leaseToken, kind, caseKey, object.byteSize());
        ArtifactView view =
            artifacts.insert(attemptId, caseKey, kind, object, properties.retention()).view();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactId", view.id());
        payload.put("kind", kind.name());
        payload.put("caseKey", caseKey);
        payload.put("byteSize", view.byteSize());
        // Takes only FOR KEY SHARE on the run (no run FOR UPDATE here), and this transaction
        // holds no other run's rows, so it can't join a lock cycle with completions.
        attemptRows.insertEvent(
            attempt.runId(),
            attempt.targetId(),
            attemptId,
            EventType.ARTIFACT_AVAILABLE,
            mapper.writeValueAsString(payload));
        try (var ctx = context(attempt)) {
          log.info(
              "artifact stored id={} kind={} caseKey={} bytes={}",
              view.id(),
              kind,
              caseKey,
              view.byteSize());
        }
        return view;
      });
    } catch (RuntimeException e) {
      deleteQuietly(object.key());
      throw e;
    }
  }

  private LockedAttempt admit(
      UUID attemptId, String leaseToken, ArtifactKind kind, String caseKey, long incomingBytes) {
    LockedAttempt attempt = attempts.requireLeaseHolder(attemptId, leaseToken, "artifact upload");
    checkCaseKey(attempt, kind, caseKey);
    ArtifactRepository.Usage usage = artifacts.usage(attemptId);
    if (usage.count() >= MAX_ARTIFACTS_PER_ATTEMPT) {
      throw new ApiProblemException(
          ProblemType.ARTIFACT_TOO_LARGE,
          "attempt " + attemptId + " already has " + MAX_ARTIFACTS_PER_ATTEMPT + " artifacts");
    }
    if (usage.bytes() + incomingBytes > MAX_BYTES_PER_ATTEMPT) {
      throw new ApiProblemException(
          ProblemType.ARTIFACT_TOO_LARGE,
          "attempt " + attemptId + " would exceed " + MAX_BYTES_PER_ATTEMPT + " artifact bytes");
    }
    return attempt;
  }

  /** Screenshots and traces belong to a claimed case; the log tail belongs to the attempt. */
  private void checkCaseKey(LockedAttempt attempt, ArtifactKind kind, String caseKey) {
    String problem = null;
    if (kind == ArtifactKind.LOG && caseKey != null) {
      problem = "a LOG artifact belongs to the attempt, not to a case";
    } else if (kind != ArtifactKind.LOG && caseKey == null) {
      problem = "required for " + kind;
    } else if (caseKey != null && !attempts.claimedCaseKeys(attempt).contains(caseKey)) {
      problem = "not a case of the claimed suite revision";
    }
    if (problem != null) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED,
          "the artifact does not fit attempt " + attempt.id() + "'s claim",
          List.of(new FieldError("caseKey", problem)));
    }
  }

  private void deleteQuietly(String key) {
    try {
      store.delete(key);
    } catch (IOException | RuntimeException e) {
      // The orphan sweeper (FR-6) removes it later.
      log.warn("could not delete unrecorded artifact bytes: {}", e.getMessage());
    }
  }

  private static LogContext context(LockedAttempt attempt) {
    return LogContext.open()
        .put("runId", attempt.runId())
        .put("targetId", attempt.targetId())
        .put("attemptId", attempt.id())
        .put("workerId", attempt.workerId());
  }
}
