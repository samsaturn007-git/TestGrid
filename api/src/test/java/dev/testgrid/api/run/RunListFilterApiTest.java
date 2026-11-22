package dev.testgrid.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.testgrid.api.project.LocalProject;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** PRD-05 T3: GET /runs filters (status, suiteKey, branch, trigger, projectId) on real Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class RunListFilterApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  private UUID checkout;
  private UUID catalog;

  // The runs every test filters, newest first. Names make failures readable.
  private UUID failedMain;
  private UUID passedMain;
  private UUID timedOutFeature;
  private UUID queuedCatalogCi;
  private UUID runningNoBranch;
  private UUID failedCatalogCi;

  @BeforeEach
  void setUp() {
    RunTestSupport.clearRuns(jdbc);
    checkout = RunTestSupport.currentSuiteId(jdbc, "checkout-smoke");
    catalog = RunTestSupport.currentSuiteId(jdbc, "catalog-regression");
    failedMain = insert(checkout, "FAILED", "MANUAL", "main", 1);
    passedMain = insert(checkout, "PASSED", "MANUAL", "main", 2);
    timedOutFeature = insert(checkout, "TIMED_OUT", "MANUAL", "feat/coupon", 3);
    queuedCatalogCi = insert(catalog, "QUEUED", "CI", "main", 4);
    runningNoBranch = insert(checkout, "RUNNING", "MANUAL", null, 5);
    failedCatalogCi = insert(catalog, "FAILED", "CI", "feat/coupon", 6);
  }

  @AfterEach
  void cleanUp() {
    RunTestSupport.clearRuns(jdbc);
  }

  private UUID insert(UUID suiteId, String status, String trigger, String branch, int minutesAgo) {
    boolean terminal = !status.equals("QUEUED") && !status.equals("RUNNING");
    return jdbc.sql(
            """
            INSERT INTO runs (project_id, suite_id, status, trigger, branch, idempotency_key,
                              request_hash, queued_at, started_at, finished_at)
            VALUES (:p, :s, :status, :trigger, :branch, :k, :h,
                    now() - make_interval(mins => :ago),
                    CASE WHEN :status <> 'QUEUED' THEN now() - make_interval(mins => :ago) END,
                    CASE WHEN :terminal THEN now() END)
            RETURNING id
            """)
        .param("p", LocalProject.ID)
        .param("s", suiteId)
        .param("status", status)
        .param("trigger", trigger)
        .param("branch", branch)
        .param("k", "filter-" + UUID.randomUUID())
        .param("h", new byte[32])
        .param("ago", minutesAgo)
        .param("terminal", terminal)
        .query(UUID.class)
        .single();
  }

  private List<String> ids(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.items[*].id");
  }

  private static List<String> s(UUID... ids) {
    return java.util.Arrays.stream(ids).map(UUID::toString).toList();
  }

  @Test
  void noFilterListsEveryRunNewestFirst() throws Exception {
    assertThat(ids(get("/api/v1/runs")))
        .containsExactlyElementsOf(
            s(
                failedMain,
                passedMain,
                timedOutFeature,
                queuedCatalogCi,
                runningNoBranch,
                failedCatalogCi));
  }

  @Test
  void statusFiltersOnTheAggregateStatus() throws Exception {
    assertThat(ids(get("/api/v1/runs").param("status", "FAILED")))
        .containsExactlyElementsOf(s(failedMain, failedCatalogCi));
  }

  @Test
  void repeatedStatusValuesMatchAnyOfThem() throws Exception {
    assertThat(ids(get("/api/v1/runs").param("status", "FAILED", "TIMED_OUT")))
        .containsExactlyElementsOf(s(failedMain, timedOutFeature, failedCatalogCi));
    // Spring also splits a comma list; both spellings mean the same filter.
    assertThat(ids(get("/api/v1/runs").param("status", "FAILED,TIMED_OUT")))
        .containsExactlyElementsOf(s(failedMain, timedOutFeature, failedCatalogCi));
  }

  @Test
  void suiteKeyMatchesTheSuiteAcrossRevisions() throws Exception {
    assertThat(ids(get("/api/v1/runs").param("suiteKey", "catalog-regression")))
        .containsExactlyElementsOf(s(queuedCatalogCi, failedCatalogCi));
  }

  @Test
  void branchIsAnExactMatchAndNeverMatchesRunsWithoutOne() throws Exception {
    assertThat(ids(get("/api/v1/runs").param("branch", "main")))
        .containsExactlyElementsOf(s(failedMain, passedMain, queuedCatalogCi));
    assertThat(ids(get("/api/v1/runs").param("branch", "mai"))).isEmpty();
  }

  @Test
  void triggerFilters() throws Exception {
    assertThat(ids(get("/api/v1/runs").param("trigger", "CI")))
        .containsExactlyElementsOf(s(queuedCatalogCi, failedCatalogCi));
  }

  @Test
  void differentFiltersMustAllMatch() throws Exception {
    assertThat(
            ids(
                get("/api/v1/runs")
                    .param("status", "FAILED")
                    .param("suiteKey", "catalog-regression")
                    .param("branch", "feat/coupon")
                    .param("trigger", "CI")))
        .containsExactlyElementsOf(s(failedCatalogCi));
    assertThat(ids(get("/api/v1/runs").param("status", "PASSED").param("trigger", "CI"))).isEmpty();
  }

  @Test
  void anEmptyFilterValueMeansNoFilter() throws Exception {
    assertThat(ids(get("/api/v1/runs").param("status", "").param("branch", ""))).hasSize(6);
  }

  @Test
  void theCursorPagesThroughFilteredResultsWithoutGapsOrDuplicates() throws Exception {
    // Two of the six runs are skipped by the filter; page size 1 crosses each of them.
    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      var request = get("/api/v1/runs").param("limit", "1").param("trigger", "MANUAL");
      if (cursor != null) {
        request = request.param("cursor", cursor);
      }
      String body =
          mvc.perform(request)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      seen.addAll(JsonPath.read(body, "$.items[*].id"));
      cursor = JsonPath.read(body, "$.nextCursor");
      pages++;
    } while (cursor != null && pages < 10);

    assertThat(seen)
        .containsExactlyElementsOf(s(failedMain, passedMain, timedOutFeature, runningNoBranch));
    assertThat(pages).isEqualTo(4);
  }

  @Test
  void theCallersProjectIdIsAccepted() throws Exception {
    assertThat(ids(get("/api/v1/runs").param("projectId", LocalProject.ID.toString()))).hasSize(6);
  }

  @Test
  void aProjectOutsideTheCallersScopeIsNotFound() throws Exception {
    mvc.perform(get("/api/v1/runs").param("projectId", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/not-found"));
  }

  @Test
  void unknownEnumValuesAreRejectedNotIgnored() throws Exception {
    for (String[] bad :
        new String[][] {{"status", "GREEN"}, {"status", "failed"}, {"trigger", "NIGHTLY"}}) {
      mvc.perform(get("/api/v1/runs").param(bad[0], bad[1]))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.type").value("https://testgrid.local/problems/validation-failed"))
          .andExpect(jsonPath("$.errors[0].field").value(bad[0]));
    }
  }

  @Test
  void suiteKeyAndBranchFollowTheCreateRunRules() throws Exception {
    mvc.perform(get("/api/v1/runs").param("suiteKey", "Checkout Smoke").param("branch", "a..b"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("suiteKey", "branch")));
    // A second value of a single-valued filter arrives comma-joined, which neither rule allows.
    mvc.perform(get("/api/v1/runs").param("branch", "main", "dev"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("branch"));
  }

  @Test
  void aMisspeltFilterIsRejectedRatherThanIgnored() throws Exception {
    mvc.perform(get("/api/v1/runs").param("suite", "checkout-smoke").param("Status", "FAILED"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("Status", "suite")))
        .andExpect(jsonPath("$.errors[0].message").value("unknown query parameter"));
  }

  @Test
  void filterValuesAreBoundNotSpliced() throws Exception {
    // A quote is outside the branch rule, so it never reaches SQL at all...
    mvc.perform(get("/api/v1/runs").param("branch", "main'--"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("branch"));
    // ...and values that do pass are bound parameters, never part of the SQL text.
    assertThat(
            RunRepository.listSql(
                new RunFilter(java.util.Set.of(), "x", "y", java.util.Set.of()), false))
        .doesNotContain("'x'")
        .contains(":suiteKey")
        .contains(":branch");
  }
}
