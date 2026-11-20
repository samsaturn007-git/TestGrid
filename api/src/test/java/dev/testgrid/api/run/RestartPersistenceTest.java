package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.ApiApplication;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-006 / AC-1: a run created through one API process is still there, unchanged, when a new API
 * process starts on the same database. Two full application starts on a real HTTP port; the second
 * one also re-runs Flyway and the manifest seeder against the existing data.
 */
@Testcontainers
class RestartPersistenceTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestcontainer.IMAGE);

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void api006RunAndTargetsSurviveAnApiRestart() throws Exception {
    String key = "restart-" + UUID.randomUUID();
    String body =
        "{\"suiteKey\":\"checkout-smoke\",\"targets\":[{\"executor\":\"LOCAL\",\"engine\":"
            + "\"chromium\"},{\"executor\":\"LOCAL\",\"engine\":\"webkit\"}],\"trigger\":\"CI\","
            + "\"idempotencyKey\":\""
            + key
            + "\",\"fixture\":{\"mode\":\"PASS\",\"seed\":7}}";

    String runId;
    String before;
    try (ConfigurableApplicationContext first = start()) {
      HttpResponse<String> created = post(port(first), body);
      assertThat(created.statusCode()).isEqualTo(202);
      runId = JsonPath.read(created.body(), "$.id");
      before = get(port(first), "/api/v1/runs/" + runId).body();
    }

    try (ConfigurableApplicationContext second = start()) {
      HttpResponse<String> after = get(port(second), "/api/v1/runs/" + runId);
      assertThat(after.statusCode()).isEqualTo(200);
      assertThat(tree(after.body())).isEqualTo(tree(before));
      assertThat((Object) JsonPath.read(after.body(), "$.targets.length()")).isEqualTo(2);

      // The idempotency record survived too: the same retry replays instead of creating a run.
      HttpResponse<String> retry = post(port(second), body);
      assertThat(retry.statusCode()).isEqualTo(200);
      assertThat(retry.headers().firstValue(RunController.REPLAY_HEADER)).hasValue("true");
      assertThat((String) JsonPath.read(retry.body(), "$.id")).isEqualTo(runId);
    }
  }

  private static ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(ApiApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--testgrid.profile=local");
  }

  private static int port(ConfigurableApplicationContext context) {
    return ((WebServerApplicationContext) context).getWebServer().getPort();
  }

  private HttpResponse<String> post(int port, String json) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/projects/" + LocalProject.ID + "/runs"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> tree(String json) {
    return mapper.readValue(json, Map.class);
  }
}
