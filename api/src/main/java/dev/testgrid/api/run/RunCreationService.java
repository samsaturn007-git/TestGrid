package dev.testgrid.api.run;

import dev.testgrid.api.config.TestgridProperties;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.domain.Executor;
import dev.testgrid.api.domain.ShardingMode;
import dev.testgrid.api.domain.Trigger;
import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.FieldError;
import dev.testgrid.api.problem.ProblemType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** PRD-02 FR-4: checks a create-run request against the manifest and writes it in one go. */
@Service
public class RunCreationService {

  private final RunRepository runs;
  private final TestgridProperties properties;
  private final JsonMapper mapper;

  public RunCreationService(RunRepository runs, TestgridProperties properties, JsonMapper mapper) {
    this.runs = runs;
    this.properties = properties;
    this.mapper = mapper;
  }

  /**
   * The run, its targets and the {@code RUN_CREATED} event commit together or not at all, so a
   * rejected or failed request leaves nothing behind (AC-3).
   */
  @Transactional
  public RunResponse create(UUID projectId, CreateRunRequest request) {
    if (!runs.projectExists(projectId)) {
      throw new ApiProblemException(ProblemType.NOT_FOUND, "project " + projectId + " not found");
    }
    checkShape(request);
    RunRepository.CurrentSuite suite =
        runs.findCurrentSuite(projectId, request.suiteKey())
            .filter(RunRepository.CurrentSuite::enabled)
            .orElseThrow(() ->
                new ApiProblemException(
                    ProblemType.UNKNOWN_SUITE,
                    "suite " + request.suiteKey() + " is not an enabled suite in the manifest",
                    List.of(new FieldError("suiteKey", "unknown suite"))));
    checkEngines(request, suite);
    checkFixture(request, suite);

    UUID runId =
        runs.insertRun(
            new RunRepository.NewRun(
                projectId,
                suite.id(),
                request.trigger(),
                request.fixture(),
                request.source(),
                request.idempotencyKey(),
                RunRequestHash.of(request, mapper),
                request.shardingOrDefault()));

    List<String> targetKeys = new ArrayList<>();
    for (CreateRunRequest.Target target : request.targets()) {
      String key = targetKey(target);
      runs.insertTarget(runId, key, target.executor(), target.engine());
      targetKeys.add(key);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("suiteKey", request.suiteKey());
    payload.put("suiteRevision", suite.revision());
    payload.put("trigger", request.trigger().name());
    payload.put("targetKeys", targetKeys);
    runs.insertRunEvent(runId, EventType.RUN_CREATED, mapper.writeValueAsString(payload));

    return runs.findRun(projectId, runId).orElseThrow();
  }

  static String targetKey(CreateRunRequest.Target target) {
    // Shard index is always 0 until PRD-11 adds sharding.
    return target.executor().name().toLowerCase(Locale.ROOT)
        + ":"
        + target.engine().wireName()
        + ":0";
  }

  private static void checkShape(CreateRunRequest request) {
    List<FieldError> errors = new ArrayList<>();
    if (request.trigger() == Trigger.RERUN) {
      errors.add(new FieldError("trigger", "RERUN is set by POST /runs/{runId}/rerun (PRD-05)"));
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < request.targets().size(); i++) {
      CreateRunRequest.Target target = request.targets().get(i);
      if (target.executor() != Executor.LOCAL) {
        errors.add(
            new FieldError(
                "targets[" + i + "].executor", "only LOCAL is available (BROWSERSTACK: PRD-09)"));
      }
      if (!seen.add(targetKey(target))) {
        errors.add(new FieldError("targets[" + i + "]", "duplicate target"));
      }
    }
    CreateRunRequest.Sharding sharding = request.shardingOrDefault();
    if (sharding.mode() != ShardingMode.NONE || sharding.shards() != 1) {
      errors.add(new FieldError("sharding", "only mode NONE with 1 shard (sharding: PRD-11)"));
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.VALIDATION_FAILED, "the request has invalid fields", errors);
    }
  }

  private static void checkEngines(CreateRunRequest request, RunRepository.CurrentSuite suite) {
    List<FieldError> errors = new ArrayList<>();
    for (int i = 0; i < request.targets().size(); i++) {
      var engine = request.targets().get(i).engine();
      if (!suite.engines().contains(engine)) {
        errors.add(
            new FieldError(
                "targets[" + i + "].engine",
                engine.wireName() + " is not in allowedEngines for " + request.suiteKey()));
      }
    }
    if (!errors.isEmpty()) {
      throw new ApiProblemException(
          ProblemType.ENGINE_NOT_ALLOWED,
          "suite " + request.suiteKey() + " does not allow every requested engine",
          errors);
    }
  }

  private void checkFixture(CreateRunRequest request, RunRepository.CurrentSuite suite) {
    CreateRunRequest.Fixture fixture = request.fixture();
    if (fixture == null) {
      return;
    }
    if (!properties.isLocal()) {
      throw new ApiProblemException(
          ProblemType.FIXTURE_NOT_ALLOWED,
          "fixture modes are only accepted when TESTGRID_PROFILE=local",
          List.of(new FieldError("fixture", "not allowed outside the local profile")));
    }
    if (!suite.modes().contains(fixture.mode())) {
      throw new ApiProblemException(
          ProblemType.FIXTURE_NOT_ALLOWED,
          "suite " + request.suiteKey() + " does not declare fixture mode " + fixture.mode(),
          List.of(new FieldError("fixture.mode", "not in fixtureModes for this suite")));
    }
  }
}
