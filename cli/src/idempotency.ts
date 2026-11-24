import { createHash, randomUUID } from 'node:crypto';

export interface KeySource {
  repo: string | null;
  commitSha: string | null;
  ciRef: string | null;
  suite: string;
  engines: readonly string[];
  fixture: { mode: string; seed: number } | null;
}

/**
 * Contracts §11: sha256 of repo, commit, CI ref, suite and engines (sorted), so re-running the same
 * CI job step replays its run instead of creating a second one. The fixture is part of it too, so one
 * job can run several fixture scenarios without a 409. Without a CI ref there is no job to dedupe
 * against, and every invocation gets a fresh random key.
 */
export function idempotencyKey(source: KeySource): string {
  if (!source.ciRef) {
    return `tgcli:${randomUUID()}`;
  }
  const lines = [
    'testgrid-cli-idempotency/v1',
    source.repo ?? '',
    source.commitSha ?? '',
    source.ciRef,
    source.suite,
    [...source.engines].sort().join(','),
    source.fixture ? `${source.fixture.mode}:${source.fixture.seed}` : '',
  ];
  return `tgcli:${createHash('sha256').update(lines.join('\n'), 'utf8').digest('hex')}`;
}
