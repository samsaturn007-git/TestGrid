package dev.testgrid.api.events;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Reads a real text/event-stream over HTTP and splits it into frames, the way EventSource does:
 * {@code id:}, {@code event:} and {@code data:} lines up to a blank line; {@code :} lines are
 * comments.
 */
final class SseTestClient implements AutoCloseable {

  record Frame(String id, String event, String data) {}

  final int status;
  final HttpResponse<InputStream> response;
  private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> comments = new LinkedBlockingQueue<>();
  private final Thread reader;

  private SseTestClient(HttpResponse<InputStream> response) {
    this.response = response;
    this.status = response.statusCode();
    // Only a 200 is a stream; an error keeps its body for body().
    this.reader = status == 200 ? Thread.ofPlatform().daemon().start(this::read) : null;
  }

  static SseTestClient connect(int port, String path, String lastEventId) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "text/event-stream")
            .GET();
    if (lastEventId != null) {
      request.header("Last-Event-ID", lastEventId);
    }
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    return new SseTestClient(http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()));
  }

  private void read() {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String id = null;
      String event = null;
      StringBuilder data = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isEmpty()) {
          if (event != null || !data.isEmpty()) {
            frames.add(new Frame(id, event, data.toString()));
          }
          id = null;
          event = null;
          data.setLength(0);
        } else if (line.startsWith(":")) {
          comments.add(line);
        } else if (line.startsWith("id:")) {
          id = line.substring(3).trim();
        } else if (line.startsWith("event:")) {
          event = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
          data.append(data.isEmpty() ? "" : "\n").append(line.substring(5).trim());
        }
      }
    } catch (Exception e) {
      // closed by the test
    }
  }

  /** The next frame, or null after {@code ms}. */
  Frame next(long ms) throws InterruptedException {
    return frames.poll(ms, TimeUnit.MILLISECONDS);
  }

  /** Every frame that arrives within {@code ms}. */
  List<Frame> drain(long ms) throws InterruptedException {
    List<Frame> out = new ArrayList<>();
    long end = System.currentTimeMillis() + ms;
    Frame f;
    while ((f = frames.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS))
        != null) {
      out.add(f);
    }
    return out;
  }

  String nextComment(long ms) throws InterruptedException {
    return comments.poll(ms, TimeUnit.MILLISECONDS);
  }

  String body() throws Exception {
    return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
  }

  @Override
  public void close() throws Exception {
    response.body().close();
    if (reader != null) {
      reader.interrupt();
    }
  }
}
