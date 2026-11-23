package dev.testgrid.api.run;

import dev.testgrid.api.events.RunEventRepository;
import dev.testgrid.api.internal.InternalApiProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** PRD-06 FR-7. Read-only; every timestamp comes from the database. */
@Service
public class RunTimelineService {

  /** A run's events are a few hundred at most; this bounds a pathological one. */
  static final int MAX_EVENTS = 10_000;

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final RunQueryService queries;
  private final RunEventRepository events;
  private final JdbcClient jdbc;
  private final JsonMapper mapper;
  private final InternalApiProperties internal;

  public RunTimelineService(
      RunQueryService queries,
      RunEventRepository events,
      JdbcClient jdbc,
      JsonMapper mapper,
      InternalApiProperties internal) {
    this.queries = queries;
    this.events = events;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.internal = internal;
  }

  /** One snapshot: run, results, heartbeats and events all see the same committed state. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RunTimeline timeline(UUID runId) {
    RunResponse run = queries.get(runId);
    RunResults results = queries.results(runId);
    Instant now = jdbc.sql("SELECT now()").query(Timestamp.class).single().toInstant();
    long threshold = 2L * internal.heartbeatIntervalSeconds() * 1000;

    Map<UUID, List<Instant>> beats = new HashMap<>();
    jdbc.sql(
            """
            SELECT h.attempt_id, h.beat_at FROM attempt_heartbeats h
            JOIN attempts a ON a.id = h.attempt_id JOIN run_targets t ON t.id = a.target_id
            WHERE t.run_id = :r ORDER BY h.attempt_id, h.beat_at
            """)
        .param("r", runId)
        .query(rs -> {
          beats
              .computeIfAbsent(rs.getObject("attempt_id", UUID.class), k -> new ArrayList<>())
              .add(rs.getTimestamp("beat_at").toInstant());
        });

    List<RunEventRepository.Envelope> all = events.after(runId, 0, MAX_EVENTS);
    List<RunTimeline.TimelineEvent> runEvents =
        all.stream().filter(e -> e.targetId() == null).map(this::event).toList();

    List<RunTimeline.Target> targets =
        results.targets().stream()
            .map(t ->
                new RunTimeline.Target(
                    t.id(),
                    t.targetKey(),
                    t.engine(),
                    t.status(),
                    all.stream()
                        .filter(e -> t.id().equals(e.targetId()) && e.attemptId() == null)
                        .map(this::event)
                        .toList(),
                    t.attempts().reversed().stream()
                        .map(a -> {
                          AttemptSummary s = a.attempt();
                          List<Instant> hb = beats.getOrDefault(s.id(), List.of());
                          Instant end = s.finishedAt() != null ? s.finishedAt() : now;
                          return new RunTimeline.Attempt(
                              s,
                              hb,
                              gaps(s.claimedAt(), s.startedAt(), hb, end, threshold),
                              a.caseResults(),
                              a.artifacts(),
                              all.stream()
                                  .filter(e -> s.id().equals(e.attemptId()))
                                  .map(this::event)
                                  .toList());
                        })
                        .toList()))
            .toList();

    return new RunTimeline(
        run.id(),
        run.status(),
        run.queuedAt(),
        run.finishedAt(),
        now,
        internal.heartbeatIntervalSeconds(),
        threshold,
        runEvents,
        targets);
  }

  /**
   * Silences longer than {@code thresholdMs} between consecutive signs of life: the claim, the
   * start, each heartbeat, and the end (finished, abandoned by the reaper, or now if still active).
   * O(h log h) for h heartbeats.
   */
  static List<RunTimeline.Gap> gaps(
      Instant claimedAt,
      Instant startedAt,
      List<Instant> heartbeats,
      Instant end,
      long thresholdMs) {
    List<Instant> points =
        Stream.concat(Stream.of(claimedAt, startedAt, end), heartbeats.stream())
            .filter(Objects::nonNull)
            .sorted(Comparator.naturalOrder())
            .toList();
    List<RunTimeline.Gap> gaps = new ArrayList<>();
    for (int i = 1; i < points.size(); i++) {
      long ms = Duration.between(points.get(i - 1), points.get(i)).toMillis();
      if (ms > thresholdMs) {
        gaps.add(new RunTimeline.Gap(points.get(i - 1), points.get(i), ms));
      }
    }
    return gaps;
  }

  private RunTimeline.TimelineEvent event(RunEventRepository.Envelope e) {
    return new RunTimeline.TimelineEvent(
        e.eventId(), e.type(), e.occurredAt(), mapper.convertValue(e.data(), MAP));
  }
}
