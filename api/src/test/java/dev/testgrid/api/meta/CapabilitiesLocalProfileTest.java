package dev.testgrid.api.meta;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.testgrid.api.support.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** PRD-05 FR-2: with TESTGRID_PROFILE=local the dashboard may offer fixture modes. */
@SpringBootTest(properties = "testgrid.profile=local")
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class CapabilitiesLocalProfileTest {

  @Autowired private MockMvc mvc;

  @Test
  void fixturesAreAllowedInTheLocalProfile() throws Exception {
    mvc.perform(get("/api/v1/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fixturesAllowed").value(true));
  }
}
