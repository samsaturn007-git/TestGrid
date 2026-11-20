package dev.testgrid.api.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** PRD-02 FR-8 and contracts §1: Problem Details shape, traceId, X-Request-Id, no leaks. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, ProblemDetailsTest.BoomConfig.class})
class ProblemDetailsTest {

  private static final String HEX32 = "^[0-9a-f]{32}$";
  private static final String RUNS = "/api/v1/projects/" + LocalProject.ID + "/runs";
  private static final Set<String> PROBLEM_FIELDS =
      Set.of("type", "title", "status", "detail", "instance", "traceId", "errors");

  @Autowired private MockMvc mvc;
  @Autowired private JsonMapper mapper;

  /** Throws an exception whose message must never reach the client. */
  @RestController
  static class BoomController {
    @GetMapping("/test-only/boom")
    String boom() {
      throw new IllegalStateException("db password is hunter2 at com.example.Secret");
    }
  }

  @TestConfiguration
  static class BoomConfig {
    @Bean
    BoomController boomController() {
      return new BoomController();
    }
  }

  @Test
  void unexpectedErrorsAreGeneric500ProblemsWithoutInternals() throws Exception {
    String body =
        mvc.perform(get("/test-only/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("The server could not complete the request."))
            .andExpect(jsonPath("$.traceId").value(matchesPattern(HEX32)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .doesNotContain("hunter2")
        .doesNotContain("IllegalStateException")
        .doesNotContain("com.example")
        .doesNotContain("\tat ");
  }

  @Test
  void aValidTraceparentContinuesTheCallersTrace() throws Exception {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    mvc.perform(
            post(RUNS)
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.traceId").value(traceId));
  }

  @Test
  void anInvalidOrAllZeroTraceparentStartsANewTrace() throws Exception {
    for (String bad :
        new String[] {
          "garbage",
          "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
          "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
          "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"
        }) {
      String traceId =
          JsonPath.read(
              mvc.perform(get("/api/v1/runs/not-a-uuid").header("traceparent", bad))
                  .andExpect(jsonPath("$.traceId").value(matchesPattern(HEX32)))
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              "$.traceId");
      assertThat(traceId).as(bad).isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }
  }

  @Test
  void everyResponseCarriesARequestId() throws Exception {
    mvc.perform(get("/api/v1/projects"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", matchesPattern("^[0-9a-f-]{36}$")));
    mvc.perform(get("/api/v1/runs/not-a-uuid")).andExpect(header().exists("X-Request-Id"));
    mvc.perform(get("/api/v1/projects").header("X-Request-Id", "ci-job-42.step-3"))
        .andExpect(header().string("X-Request-Id", "ci-job-42.step-3"));
    mvc.perform(get("/api/v1/projects").header("X-Request-Id", "bad id; rm -rf"))
        .andExpect(header().string("X-Request-Id", matchesPattern("^[0-9a-f-]{36}$")));
  }

  @Test
  void theBodySizeRefusalAlsoCarriesTraceAndRequestIds() throws Exception {
    mvc.perform(
            post(RUNS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"suiteKey\":\"" + "x".repeat(70 * 1024) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.traceId").value(matchesPattern(HEX32)))
        .andExpect(header().exists("X-Request-Id"));
  }

  /** The documented Problem schema (ProblemBody) matches what the API actually writes. */
  @Test
  void problemBodiesHaveExactlyTheDocumentedFields() throws Exception {
    assertShape(post(RUNS).contentType(MediaType.APPLICATION_JSON).content("{}"), true, true);
    assertShape(get("/api/v1/runs/" + UUID.randomUUID()), false, true);
    // Spring's own errors get the same shape; only catalogue errors carry a type.
    assertShape(get("/api/v1/no-such-route"), false, true);
    assertShape(post(RUNS).contentType(MediaType.TEXT_PLAIN).content("x"), false, true);
    assertShape(delete("/api/v1/projects"), false, false);
  }

  @Test
  void wrongContentTypeIsUnsupportedMediaType() throws Exception {
    mvc.perform(post(RUNS).contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(
            jsonPath("$.type").value("https://testgrid.local/problems/unsupported-media-type"));
  }

  @SuppressWarnings("unchecked")
  private void assertShape(RequestBuilder request, boolean hasErrors, boolean hasType)
      throws Exception {
    ResultActions result = mvc.perform(request);
    Map<String, Object> body =
        mapper.readValue(result.andReturn().getResponse().getContentAsString(), Map.class);
    assertThat(PROBLEM_FIELDS).containsAll(body.keySet());
    assertThat(body).containsKeys("title", "status", "detail", "instance", "traceId");
    assertThat(body.containsKey("errors")).isEqualTo(hasErrors);
    assertThat(body.containsKey("type")).isEqualTo(hasType);
  }
}
