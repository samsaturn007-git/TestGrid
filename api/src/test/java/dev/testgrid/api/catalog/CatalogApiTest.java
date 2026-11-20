package dev.testgrid.api.catalog;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** PRD-02 FR-3: projects, current suites, and a suite with its cases. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class CatalogApiTest {

  private static final String SMOKE = "$.items[?(@.suiteKey == 'checkout-smoke')]";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @AfterEach
  void removeOtherProjects() {
    jdbc.sql("DELETE FROM suites WHERE project_id <> :p").param("p", LocalProject.ID).update();
    jdbc.sql("DELETE FROM projects WHERE id <> :p").param("p", LocalProject.ID).update();
  }

  @Test
  void listsTheCallersProjectOnly() throws Exception {
    jdbc.sql("INSERT INTO projects (slug, name) VALUES ('other', 'Other')").update();
    mvc.perform(get("/api/v1/projects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(LocalProject.ID.toString()))
        .andExpect(jsonPath("$.items[0].slug").value("demo"))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
  }

  @Test
  void listsCurrentSuitesFromTheManifest() throws Exception {
    mvc.perform(get("/api/v1/projects/" + LocalProject.ID + "/suites"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(SMOKE + ".current").value(true))
        .andExpect(jsonPath(SMOKE + ".caseCount").value(6))
        .andExpect(jsonPath(SMOKE + ".timeoutMs").value(120000))
        .andExpect(
            jsonPath(SMOKE + ".allowedEngines[*]")
                .value(contains("chromium", "firefox", "webkit")));
  }

  @Test
  void suiteDetailHasItsCases() throws Exception {
    UUID suiteId =
        jdbc.sql(
                "SELECT id FROM suites WHERE project_id = :p AND suite_key = 'checkout-smoke'"
                    + " AND is_current")
            .param("p", LocalProject.ID)
            .query(UUID.class)
            .single();
    mvc.perform(get("/api/v1/suites/" + suiteId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.suite.suiteKey").value("checkout-smoke"))
        .andExpect(jsonPath("$.suite.links.self").value("/api/v1/suites/" + suiteId))
        .andExpect(jsonPath("$.cases.length()").value(6))
        .andExpect(jsonPath("$.cases[?(@.caseKey == 'cart-total')].critical").value(true))
        .andExpect(jsonPath("$.cases[?(@.caseKey == 'cart-total')].tag").value("@cart-total"));
  }

  @Test
  void unknownOrForeignProjectsAndSuitesAreNotFound() throws Exception {
    UUID other =
        jdbc.sql("INSERT INTO projects (slug, name) VALUES ('other', 'Other') RETURNING id")
            .query(UUID.class)
            .single();
    UUID foreignSuite =
        jdbc.sql(
                """
                INSERT INTO suites (project_id, suite_key, revision, display_name, is_current,
                                    timeout_ms, playwright_retries, allowed_engines, fixture_modes)
                VALUES (:p, 'secret', 'dddddddddddd', 'Secret', true, 60000, 0,
                        ARRAY['chromium'], ARRAY[]::text[])
                RETURNING id
                """)
            .param("p", other)
            .query(UUID.class)
            .single();

    for (String path :
        new String[] {
          "/api/v1/projects/" + other + "/suites",
          "/api/v1/projects/" + UUID.randomUUID() + "/suites",
          "/api/v1/suites/" + foreignSuite,
          "/api/v1/suites/" + UUID.randomUUID()
        }) {
      mvc.perform(get(path))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/not-found"));
    }
  }
}
