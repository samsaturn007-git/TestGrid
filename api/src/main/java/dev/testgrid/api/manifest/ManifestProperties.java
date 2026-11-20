package dev.testgrid.api.manifest;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code root} is the worker directory: it holds {@code manifest/suites.json}, and spec paths in
 * the manifest are relative to it. Bound as a string on purpose: with a servlet container running,
 * Spring's String-to-Path conversion resolves relative paths as web resources and rejects "..".
 */
@ConfigurationProperties("testgrid.manifest")
public record ManifestProperties(String root) {

  public Path rootPath() {
    return root == null ? null : Path.of(root);
  }
}
