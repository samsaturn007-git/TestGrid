package dev.testgrid.api.events;

import dev.testgrid.api.problem.ApiProblemException;
import dev.testgrid.api.problem.ProblemType;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server-sent run events (PRD-05 FR-4, contracts §5).
 *
 * <p>The database is the only source of truth: a stream remembers the last event id it sent and
 * every send reads {@code run_events WHERE id > last}. NOTIFY (V7) only says "look now", so a lost
 * or duplicated notification can delay an event by one ping interval but can never drop, repeat or
 * reorder one. Events go out with {@code id:}, so EventSource's automatic reconnect sends {@code
 * Last-Event-ID} and resumes exactly where it stopped.
 *
 * <ul>
 *   <li>No {@code Last-Event-ID}: one {@code RESYNC} carrying the run's newest event id. The client
 *       loads the snapshot, and everything after that id streams live.
 *   <li>{@code Last-Event-ID}: replay of everything after it, or one {@code RESYNC} if that is more
 *       than {@code replayLimit} events.
 * </ul>
 */
@Component
public class RunEventStreams implements SmartLifecycle {

  static final String CHANNEL = "run_events";
  static final String RESYNC = "RESYNC";

  private static final Logger log = LoggerFactory.getLogger(RunEventStreams.class);

  private final RunEventRepository events;
  private final DataSource dataSource;
  private final JsonMapper mapper;
  private final SseProperties properties;
  private final Map<UUID, Set<Stream>> byRun = new ConcurrentHashMap<>();
  private final AtomicInteger open = new AtomicInteger();
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService sends = Executors.newVirtualThreadPerTaskExecutor();
  private Thread listener;
  private Thread pinger;

  public RunEventStreams(
      RunEventRepository events,
      DataSource dataSource,
      JsonMapper mapper,
      SseProperties properties) {
    this.events = events;
    this.dataSource = dataSource;
    this.mapper = mapper;
    this.properties = properties;
  }

  /** One open stream. The lock keeps sends on one emitter in order and never concurrent. */
  private final class Stream {
    final UUID runId;
    final SseEmitter emitter;
    final ReentrantLock lock = new ReentrantLock();
    long lastSent;
    volatile boolean closed;

    Stream(UUID runId, SseEmitter emitter) {
      this.runId = runId;
      this.emitter = emitter;
    }

    /** Sends everything after {@code lastSent}, or a RESYNC if that is too much to replay. */
    void catchUp() {
      lock.lock();
      try {
        if (closed) {
          return;
        }
        List<RunEventRepository.Envelope> batch =
            events.after(runId, lastSent, properties.replayLimit() + 1);
        if (batch.size() > properties.replayLimit()) {
          resync(events.latestId(runId));
          return;
        }
        for (RunEventRepository.Envelope event : batch) {
          emitter.send(
              SseEmitter.event()
                  .id(Long.toString(event.eventId()))
                  .name(event.type())
                  .data(mapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
          lastSent = event.eventId();
        }
      } catch (IOException | IllegalStateException e) {
        close(e);
      } catch (RuntimeException e) {
        log.warn("event stream for run {} failed: {}", runId, e.toString());
        close(e);
      } finally {
        lock.unlock();
      }
    }

    void resync(long latest) throws IOException {
      emitter.send(
          SseEmitter.event()
              .id(Long.toString(latest))
              .name(RESYNC)
              .data(
                  mapper.writeValueAsString(Map.of("runId", runId, "eventId", latest)),
                  MediaType.APPLICATION_JSON));
      lastSent = latest;
    }

    void ping() {
      lock.lock();
      try {
        if (!closed) {
          emitter.send(SseEmitter.event().comment("ping"));
        }
      } catch (IOException | IllegalStateException e) {
        close(e);
      } finally {
        lock.unlock();
      }
    }

    void close(Throwable cause) {
      if (!closed) {
        closed = true;
        emitter.completeWithError(cause);
      }
    }
  }

  /**
   * Opens a stream for a run the caller may see (the controller checks scope first).
   *
   * @throws ApiProblemException {@code 429 rate-limited} when {@code maxStreams} are open
   */
  public SseEmitter open(UUID runId, Long lastEventId) {
    if (open.incrementAndGet() > properties.maxStreams()) {
      open.decrementAndGet();
      throw new ApiProblemException(
          ProblemType.RATE_LIMITED,
          "too many open event streams (" + properties.maxStreams() + "); retry shortly",
          List.of());
    }
    SseEmitter emitter = new SseEmitter(properties.timeoutMs());
    Stream stream = new Stream(runId, emitter);
    Runnable remove =
        () -> {
          stream.closed = true;
          Set<Stream> set = byRun.get(runId);
          if (set != null && set.remove(stream)) {
            open.decrementAndGet();
            byRun.computeIfPresent(runId, (k, v) -> v.isEmpty() ? null : v);
          }
        };
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(e -> remove.run());
    byRun.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(stream);

    stream.lock.lock();
    try {
      if (lastEventId == null) {
        stream.resync(events.latestId(runId));
      } else {
        stream.lastSent = lastEventId;
      }
    } catch (IOException e) {
      stream.close(e);
    } finally {
      stream.lock.unlock();
    }
    if (lastEventId != null) {
      stream.catchUp();
    }
    return emitter;
  }

  /** Open streams, for tests and metrics. */
  public int openStreams() {
    return open.get();
  }

  private void wake(UUID runId) {
    Set<Stream> streams = byRun.get(runId);
    if (streams != null) {
      for (Stream stream : streams) {
        sends.execute(stream::catchUp);
      }
    }
  }

  private void wakeAll() {
    byRun.keySet().forEach(this::wake);
  }

  // NOTIFY: one dedicated connection, reopened with backoff if it drops. After a reconnect every
  // stream catches up, because notifications sent while it was down are gone.
  private void listenLoop() {
    long backoffMs = 500;
    boolean failing = false;
    while (running.get()) {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(true);
        statement.execute("LISTEN " + CHANNEL);
        PGConnection pg = connection.unwrap(PGConnection.class);
        if (failing) {
          log.info("event listener reconnected");
          failing = false;
          wakeAll();
        }
        backoffMs = 500;
        while (running.get()) {
          PGNotification[] notifications = pg.getNotifications(1000);
          if (notifications == null) {
            continue;
          }
          for (PGNotification n : notifications) {
            String payload = n.getParameter();
            int colon = payload.indexOf(':');
            try {
              wake(UUID.fromString(colon < 0 ? payload : payload.substring(0, colon)));
            } catch (IllegalArgumentException e) {
              log.warn("ignored malformed {} notification: {}", CHANNEL, payload);
            }
          }
        }
      } catch (SQLException e) {
        if (!running.get()) {
          return;
        }
        if (!failing) {
          log.warn("event listener lost its connection, retrying: {}", e.toString());
          failing = true;
        }
        sleep(backoffMs);
        backoffMs = Math.min(backoffMs * 2, 10_000);
      }
    }
  }

  private void pingLoop() {
    while (running.get()) {
      sleep(properties.pingIntervalMs());
      for (Set<Stream> streams : byRun.values()) {
        for (Stream stream : streams) {
          sends.execute(() -> {
            stream.ping();
            stream.catchUp();
          });
        }
      }
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    if (properties.listen()) {
      listener = Thread.ofPlatform().daemon().name("tg-sse-listen").start(this::listenLoop);
    }
    pinger = Thread.ofPlatform().daemon().name("tg-sse-ping").start(this::pingLoop);
  }

  @Override
  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    for (Thread t : new Thread[] {listener, pinger}) {
      if (t != null) {
        t.interrupt();
      }
    }
    byRun.values().forEach(streams -> streams.forEach(s -> s.emitter.complete()));
    byRun.clear();
    sends.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }
}
