import path from 'node:path';
import { WorkerApiClient } from './api-client.ts';
import { loadConfig } from './config.ts';
import { createLogger } from './logger.ts';
import { DEFAULT_WORKER_ROOT, loadManifest } from './manifest.ts';
import { runWorker } from './worker.ts';

const config = loadConfig(process.env);
const log = createLogger([config.token], { workerId: config.workerId });
const suites = loadManifest();
log.info(
  { suites: suites.map((s) => `${s.suite.suiteKey}@${s.revision}`), api: config.apiUrl },
  'manifest loaded',
);

const stopping = new AbortController();
let signals = 0;
for (const sig of ['SIGTERM', 'SIGINT'] as const) {
  process.on(sig, () => {
    signals++;
    log.info(
      { signal: sig },
      signals === 1 ? 'stopping after the current attempt' : 'stopping now',
    );
    // A second signal skips the grace period.
    if (signals > 1) process.exit(1);
    stopping.abort();
  });
}

await runWorker({
  api: new WorkerApiClient(config.apiUrl, config.token),
  log,
  workerId: config.workerId,
  engines: config.engines,
  attemptDeps: {
    suites,
    workerRoot: DEFAULT_WORKER_ROOT,
    configPath: path.join(DEFAULT_WORKER_ROOT, 'playwright.config.ts'),
    baseUrl: config.baseUrl,
    secrets: [config.token],
    parentEnv: process.env,
  },
  pollMinMs: config.pollMinMs,
  pollMaxMs: config.pollMaxMs,
  shutdownGraceMs: config.shutdownGraceMs,
  stopping: stopping.signal,
});
log.info('worker exited cleanly');
