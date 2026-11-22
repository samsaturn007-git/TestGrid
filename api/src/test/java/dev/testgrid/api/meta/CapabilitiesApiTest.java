package dev.testgrid.api.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.testgrid.api.config.TestgridProperties;
import dev.testgrid.api.support.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PRD-05 FR-2: the dashboard learns whether fixture modes are accepted, without the profile name.
 * This class runs with TESTGRID_PROFILE unset; {@link CapabilitiesLocalProfileTest} with local.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class CapabilitiesApiTest {

  @Autowired private MockMvc mvc;

  @Test
  void fixturesAreNotAllowedWhenTheProfileIsUnset() throws Exception {
    mvc.perform(get("/api/v1/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixturesAllowed").value(false))
        .andExpect(jsonPath("$.profile").doesNotExist());
  }

  @Test
  void onlyTheExactValueLocalAllowsFixtures() {
    assertThat(new TestgridProperties("local").isLocal()).isTrue();
    for (String nearMiss : new String[] {"Local", "LOCAL", " local", "local ", "", null}) {
      assertThat(new TestgridProperties(nearMiss).isLocal()).as("profile %s", nearMiss).isFalse();
    }
  }
}
