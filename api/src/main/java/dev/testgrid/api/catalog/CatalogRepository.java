package dev.testgrid.api.catalog;

import dev.testgrid.api.catalog.CatalogResponses.Case;
import dev.testgrid.api.catalog.CatalogResponses.Links;
import dev.testgrid.api.catalog.CatalogResponses.Project;
import dev.testgrid.api.catalog.CatalogResponses.Suite;
import dev.testgrid.api.domain.Engine;
import dev.testgrid.api.domain.FixtureMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

  private static final String SUITE_COLUMNS =
      """
      s.id, s.project_id, s.suite_key, s.revision, s.display_name, s.enabled, s.is_current,
      s.timeout_ms, s.playwright_retries, s.allowed_engines, s.fixture_modes,
      (SELECT count(*) FROM test_cases c WHERE c.suite_id = s.id) AS case_count
      """;

  private final JdbcClient jdbc;

  public CatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Project> findProject(UUID projectId) {
    return jdbc.sql("SELECT id, slug, name FROM projects WHERE id = :p")
        .param("p", projectId)
        .query((rs, i) ->
            new Project(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                new Links("/api/v1/projects/" + projectId)))
        .optional();
  }

  /** The current revision of each suite key, as the manifest defines them now. */
  public List<Suite> currentSuites(UUID projectId) {
    return jdbc.sql(
            "SELECT "
                + SUITE_COLUMNS
                + " FROM suites s WHERE s.project_id = :p AND s.is_current ORDER BY s.suite_key")
        .param("p", projectId)
        .query((rs, i) -> mapSuite(rs))
        .list();
  }

  /** Any revision, including old ones that past runs still point to. */
  public Optional<Suite> findSuite(UUID projectId, UUID suiteId) {
    return jdbc.sql(
            "SELECT " + SUITE_COLUMNS + " FROM suites s WHERE s.project_id = :p AND s.id = :id")
        .param("p", projectId)
        .param("id", suiteId)
        .query((rs, i) -> mapSuite(rs))
        .optional();
  }

  public List<Case> cases(UUID projectId, UUID suiteId) {
    return jdbc.sql(
            """
            SELECT c.case_key, c.title, c.tag, c.critical, c.expected_duration_ms
            FROM test_cases c JOIN suites s ON s.id = c.suite_id
            WHERE s.project_id = :p AND c.suite_id = :id
            ORDER BY c.case_key
            """)
        .param("p", projectId)
        .param("id", suiteId)
        .query((rs, i) ->
            new Case(
                rs.getString("case_key"),
                rs.getString("title"),
                rs.getString("tag"),
                rs.getBoolean("critical"),
                rs.getObject("expected_duration_ms", Integer.class)))
        .list();
  }

  private static Suite mapSuite(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    return new Suite(
        id,
        rs.getObject("project_id", UUID.class),
        rs.getString("suite_key"),
        rs.getString("revision"),
        rs.getString("display_name"),
        rs.getBoolean("enabled"),
        rs.getBoolean("is_current"),
        rs.getInt("timeout_ms"),
        rs.getInt("playwright_retries"),
        Arrays.stream((String[]) rs.getArray("allowed_engines").getArray())
            .map(Engine::fromWireName)
            .sorted()
            .toList(),
        Arrays.stream((String[]) rs.getArray("fixture_modes").getArray())
            .map(FixtureMode::valueOf)
            .sorted()
            .toList(),
        rs.getInt("case_count"),
        new Links("/api/v1/suites/" + id));
  }
}
