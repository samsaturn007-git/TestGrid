package dev.testgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.testgrid.api.support.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresTestcontainer.class)
class DatabaseConnectivityTest {

  @Autowired private JdbcClient jdbc;

  @Test
  void contextStartsAgainstPinnedPostgres() {
    String version = jdbc.sql("SHOW server_version").query(String.class).single();
    assertThat(version).startsWith("18.7");
  }
}
