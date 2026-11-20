package dev.testgrid.api.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.testgrid.api.support.PostgresTestcontainer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PRD-02 FR-9: api/openapi.yaml is the spec springdoc generates from the code. In normal runs this
 * test fails if the committed file is stale; {@code make contracts} runs it with
 * -Dtestgrid.openapi.write=true to rewrite the file instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class OpenApiSpecTest {

  static final Path SPEC = Path.of("openapi.yaml");

  @Autowired private MockMvc mvc;

  @Test
  void committedSpecMatchesTheCode() throws Exception {
    String generated =
        mvc.perform(get("/v3/api-docs.yaml"))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replace("\r\n", "\n");
    if (!generated.endsWith("\n")) {
      generated += "\n";
    }

    if (Boolean.getBoolean("testgrid.openapi.write")) {
      Files.writeString(SPEC, generated);
      return;
    }
    assertThat(Files.exists(SPEC)).as("api/openapi.yaml exists (run: make contracts)").isTrue();
    assertThat(Files.readString(SPEC))
        .as("api/openapi.yaml is stale. Run `make contracts` and commit the result.")
        .isEqualTo(generated);
  }
}
