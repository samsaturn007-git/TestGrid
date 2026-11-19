package dev.testgrid.api.manifest;

/** A validated manifest entry plus the revision computed from it and its spec files. */
public record LoadedSuite(SuiteManifest.Suite suite, String revision) {}
