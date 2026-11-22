package dev.testgrid.api.run;

import dev.testgrid.api.common.CanonicalJson;
import dev.testgrid.api.common.Hashing;
import dev.testgrid.api.config.TestgridProperties;
import dev.testgrid.api.dispatch.DispatchSignal;
import dev.testgrid.api.dispatch.SchedulerSettingsRepository;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.TargetStatus;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import dev.testgrid.api.project.CallerScope;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * PRD-05 FR-6: {@code POST /runs/{runId}/rerun} creates a new run linked to the old one ({@code
 * trigger=RERUN}, {@code parent_run_id}). It reruns exactly what the old run ran: the same suite
 * revision, fixture and source, on the chosen targets. Idempotency works as for create-run (same
 * key and request replays, a different request is 409), sharing the project's key space.
 */
@Service
public class RerunService {

  /** What {@code FAILED_TARGETS} reruns: the failure outcomes. PASSED and CANCELLED are not. */
  static final Set<TargetStatus> FAILED =
      Set.of(TargetStatus.FAILED, TargetStatus.TIMED_OUT, TargetStatus.INFRA_ERROR);

  private final RunRepository runs;
  private final TestgridProperties properties;
  private final JsonMapper mapper;
  private final CallerScope scope;
  private final SchedulerSettingsRepository settings;
  private final DispatchSignal signal;

  public RerunService(
      RunRepository runs,
      TestgridProperties properties,
      JsonMapper mapper,
      CallerScope scope,
      SchedulerSettingsRepository settings,
      DispatchSignal signal) {
    this.runs = runs;
    this.properties = properties;
    this.mapper = mapper;
    this.scope = scope;
    this.settings = settings;
    this.signal = signal;
  }

  @Transactional
  public RunCreationService.Result rerun(UUID parentId, RerunRequest request) {
    UUID projectId = scope.projectId();
    RunResponse parent =
        runs.findRun(projectId, parentId)
            .orElseThrow(() ->
                new ApiProblemException(ProblemType.NOT_FOUND, "run " + parentId + " not found"));
    byte[] requestHash = requestHash(parentId, request.scope());

    // A known key is answered first, like create-run, so a retry replays even if the old run's
    // suite has moved on since.
    var existing = runs.findByIdempotencyKey(projectId, request.idempotencyKey());
    if (existing.isPresent()) {
      return replayOrConflict(projectId, request, existing.get(), requestHash);
    }

    if (!parent.status().isTerminal()) {
      throw new ApiProblemException(
          ProblemType.INVALID_RERUN,
          "run " + parentId + " is " + parent.status() + "; rerun it once it has finished",
          List.of());
    }
    List<RunResponse.Target> chosen =
        parent.targets().stream()
            .filter(t -> request.scope() == RerunRequest.Scope.ALL || FAILED.contains(t.status()))
            .toList();
    if (chosen.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.INVALID_RERUN,
          "run " + parentId + " has no FAILED, TIMED_OUT or INFRA_ERROR target to rerun",
          List.of(new FieldError("scope", "no failed targets")));
    }

    // The same revision, or nothing: a rerun on newer code would not be the same test.
    runs.findCurrentSuite(projectId, parent.suiteKey())
        .filter(s -> s.enabled() && s.id().equals(parent.suiteId()))
        .orElseThrow(() ->
            new ApiProblemException(
                ProblemType.UNKNOWN_SUITE,
                "suite "
                    + parent.suiteKey()
                    + " revision "
                    + parent.suiteRevision()
                    + " is no longer the current, enabled revision; start a new run instead",
                List.of(new FieldError("suiteKey", "revision is no longer current"))));
    if (parent.fixture() != null && !properties.isLocal()) {
      throw new ApiProblemException(
          ProblemType.FIXTURE_NOT_ALLOWED,
          "fixture modes are only accepted when TESTGRID_PROFILE=local",
          List.of(new FieldError("fixture", "not allowed outside the local profile")));
    }

    var inserted =
        runs.insertRunIfAbsent(
            new RunRepository.NewRun(
                projectId,
                parent.suiteId(),
                Trigger.RERUN,
                parent.fixture(),
                parent.source(),
                request.idempotencyKey(),
                requestHash,
                parent.sharding(),
                parentId));
    if (inserted.isEmpty()) {
      // A concurrent request with the same key committed first; our INSERT waited for it.
      return replayOrConflict(
          projectId,
          request,
          runs.findByIdempotencyKey(projectId, request.idempotencyKey()).orElseThrow(),
          requestHash);
    }
    UUID runId = inserted.get();

    int maxAttempts = settings.current().maxAttempts();
    List<String> targetKeys = new ArrayList<>();
    for (RunResponse.Target target : chosen) {
      String key =
          RunCreationService.targetKey(
              new CreateRunRequest.Target(target.executor(), target.engine()));
      runs.insertTarget(runId, key, target.executor(), target.engine(), maxAttempts);
      targetKeys.add(key);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("suiteKey", parent.suiteKey());
    payload.put("suiteRevision", parent.suiteRevision());
    payload.put("trigger", Trigger.RERUN.name());
    payload.put("targetKeys", targetKeys);
    payload.put("parentRunId", parentId.toString());
    payload.put("rerunScope", request.scope().name());
    runs.insertRunEvent(runId, EventType.RUN_CREATED, mapper.writeValueAsString(payload));
    signal.dispatchAfterCommit();

    return new RunCreationService.Result(runs.findRun(projectId, runId).orElseThrow(), false);
  }

  /** sha256 of the canonical {@code {"rerunOf": <runId>, "scope": <scope>}}. */
  private byte[] requestHash(UUID parentId, RerunRequest.Scope rerunScope) {
    ObjectNode node = mapper.createObjectNode();
    node.put("rerunOf", parentId.toString());
    node.put("scope", rerunScope.name());
    return Hashing.sha256(CanonicalJson.write(node));
  }

  private RunCreationService.Result replayOrConflict(
      UUID projectId,
      RerunRequest request,
      RunRepository.ExistingRun existing,
      byte[] requestHash) {
    if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
      throw new ApiProblemException(
          ProblemType.IDEMPOTENCY_CONFLICT,
          "idempotency key "
              + request.idempotencyKey()
              + " was already used for run "
              + existing.id()
              + " with a different request",
          List.of(new FieldError("idempotencyKey", "already used with a different request")));
    }
    return new RunCreationService.Result(
        runs.findRun(projectId, existing.id()).orElseThrow(), true);
  }
}
