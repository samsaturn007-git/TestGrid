package dev.testgrid.api.dispatch;

import dev.testgrid.api.common.LogContext;
import dev.testgrid.api.common.Traceparent;
import dev.testgrid.api.domain.EventType;
import dev.testgrid.api.internal.AttemptRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRD-04 FR-2 / ADR-004: the database decides the order, the broker only delivers. One tick holds
 * the dispatch advisory lock, computes {@code free = global_max_sessions − active −
 * dispatched_unclaimed}, and releases at most that many targets in FIFO order. Each release bumps
 * {@code dispatch_generation}, sets {@code dispatched_at} and writes {@code TARGET_DISPATCHED} to
 * both {@code outbox_events} and {@code run_events}, all in this one transaction, so a target is
 * never dispatched without a message to deliver (invariant I7) or counted twice against the cap.
 */
@Service
public class Dispatcher {

  private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

  public static final int MESSAGE_SCHEMA_VERSION = 1;

  private final DispatchRepository dispatch;
  private final SchedulerSettingsRepository settings;
  private final AttemptRepository events;
  private final DispatchSignal signal;
  private final JsonMapper mapper;

  public Dispatcher(
      DispatchRepository dispatch,
      SchedulerSettingsRepository settings,
      AttemptRepository events,
      DispatchSignal signal,
      JsonMapper mapper) {
    this.dispatch = dispatch;
    this.settings = settings;
    this.events = events;
    this.signal = signal;
    this.mapper = mapper;
  }

  public record Dispatched(
      UUID targetId, UUID runId, UUID messageId, int dispatchGeneration, long outboxId) {}

  /** One tick. Returns what it released (empty when the cap is full or nothing is waiting). */
  @Transactional
  public List<Dispatched> dispatchOnce() {
    dispatch.lock();
    SchedulerSettings current = settings.current();
    DispatchRepository.Capacity capacity = dispatch.capacity();
    int free = current.globalMaxSessions() - capacity.used();
    if (free <= 0) {
      return List.of();
    }
    // SchedulerPolicy has only FIFO until PRD-11 adds LPT_AGING.
    List<DispatchRepository.Candidate> picked = dispatch.pickFifo(free);
    List<Dispatched> released = new ArrayList<>(picked.size());
    for (DispatchRepository.Candidate candidate : picked) {
      released.add(release(candidate));
    }
    if (!released.isEmpty()) {
      signal.publishAfterCommit();
      log.info(
          "dispatched {} target(s) cap={} active={} dispatchedUnclaimed={}",
          released.size(),
          current.globalMaxSessions(),
          capacity.active(),
          capacity.dispatchedUnclaimed());
    }
    return released;
  }

  private Dispatched release(DispatchRepository.Candidate candidate) {
    int generation = dispatch.markDispatched(candidate.targetId());
    UUID messageId = UUID.randomUUID();
    String traceparent = Traceparent.newRoot();

    // Contracts §9 message body, stored as the outbox payload and published unchanged.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messageId", messageId.toString());
    body.put("targetId", candidate.targetId().toString());
    body.put("dispatchGeneration", generation);
    body.put("schemaVersion", MESSAGE_SCHEMA_VERSION);
    long outboxId =
        dispatch.insertOutbox(candidate.targetId(), mapper.writeValueAsString(body), traceparent);

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("messageId", messageId.toString());
    event.put("dispatchGeneration", generation);
    event.put("engine", candidate.engine().wireName());
    events.insertEvent(
        candidate.runId(),
        candidate.targetId(),
        null,
        EventType.TARGET_DISPATCHED,
        mapper.writeValueAsString(event));

    try (var ctx =
        LogContext.open()
            .put("runId", candidate.runId())
            .put("targetId", candidate.targetId())
            .put("traceId", Traceparent.traceId(traceparent))) {
      log.info(
          "dispatched target engine={} dispatchGeneration={} messageId={} outboxId={}",
          candidate.engine().wireName(),
          generation,
          messageId,
          outboxId);
    }
    return new Dispatched(candidate.targetId(), candidate.runId(), messageId, generation, outboxId);
  }
}
