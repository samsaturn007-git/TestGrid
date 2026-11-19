package dev.testgrid.api.manifest;

import static dev.testgrid.api.manifest.ManifestFixtures.copyOfRepoWorker;
import static dev.testgrid.api.manifest.ManifestFixtures.editManifest;
import static dev.testgrid.api.manifest.ManifestFixtures.firstSuite;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.testgrid.api.ApiApplication;
import dev.testgrid.api.support.PostgresTestcontainer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PRD-02 FR-2: an invalid manifest stops the API from starting, with an error naming the field. */
@Testcontainers
class InvalidManifestStartupTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestcontainer.IMAGE);

  @TempDir Path tmp;

  @Test
  void startupFailsAndNamesTheInvalidField() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(root, m -> firstSuite(m).put("playwrightRetries", 9));

    SpringApplicationBuilder app = new SpringApplicationBuilder(ApiApplication.class);

    assertThatThrownBy(() -> app.run(args(root)))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContainingAll(
            "does not match the manifest schema", "/suites/0/playwrightRetries");
  }

  @Test
  void nothingIsSeededFromAnInvalidManifest() {
    Path root = copyOfRepoWorker(tmp);
    editManifest(root, m -> firstSuite(m).put("timeoutMs", "fast"));
    SpringApplicationBuilder app = new SpringApplicationBuilder(ApiApplication.class);

    assertThatThrownBy(() -> app.run(args(root))).isInstanceOf(InvalidManifestException.class);

    try (Connection conn =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ResultSet rs = conn.createStatement().executeQuery("SELECT count(*) FROM suites")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  // Command-line arguments outrank application.properties; builder defaults would not.
  private static String[] args(Path root) {
    return new String[] {
      "--spring.main.web-application-type=none",
      "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
      "--spring.datasource.username=" + POSTGRES.getUsername(),
      "--spring.datasource.password=" + POSTGRES.getPassword(),
      "--testgrid.manifest.root=" + root
    };
  }
}
