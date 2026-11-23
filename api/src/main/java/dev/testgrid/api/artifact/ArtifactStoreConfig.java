package dev.testgrid.api.artifact;

import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The local filesystem store is the default (ADR-005); it refuses to start on an unusable root. */
@Configuration(proxyBeanMethods = false)
class ArtifactStoreConfig {

  @Bean
  ArtifactStore artifactStore(ArtifactProperties properties) throws IOException {
    return new LocalFsArtifactStore(properties.root());
  }
}
