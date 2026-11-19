package dev.testgrid.api.manifest;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code root} is the worker directory: it holds {@code manifest/suites.json}, and spec paths in
 * the manifest are relative to it.
 */
@ConfigurationProperties("testgrid.manifest")
public record ManifestProperties(Path root) {}
