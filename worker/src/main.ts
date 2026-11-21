import path from 'node:path';
import amqp from 'amqplib';
import { WorkerApiClient } from './api-client.ts';
import { loadConfig } from './config.ts';
import { runConsumer } from './consumer.ts';
import { createLogger } from './logger.ts';
import { DEFAULT_WORKER_ROOT, loadManifest } from './manifest.ts';
import { handleDelivery } from './worker.ts';

const config = loadConfig(process.env);
const secrets = [config.token, config.amqp.password];
const log = createLogger(secrets, { workerId: config.workerId });
const suites = loadManifest();
log.info(
  {
    suites: suites.map((s) => `${s.suite.suiteKey}@${s.revision}`),
    api: config.apiUrl,
    broker: `${config.amqp.hostname}:${config.amqp.port}`,
    engines: config.engines,
    slots: config.slots,
  },
  'manifest loaded',
);

const stopping = new AbortController();
let signals = 0;
for (const sig of ['SIGTERM', 'SIGINT'] as const) {
  process.on(sig, () => {
    signals++;
    log.info(
      { signal: sig },
      signals === 1 ? 'stopping after the current attempts' : 'stopping now',
    );
    // A second signal skips the grace period.
    if (signals > 1) process.exit(1);
    stopping.abort();
  });
}

const deps = {
  api: new WorkerApiClient(config.apiUrl, config.token),
  log,
  workerId: config.workerId,
  attemptDeps: {
    suites,
    workerRoot: DEFAULT_WORKER_ROOT,
    configPath: path.join(DEFAULT_WORKER_ROOT, 'playwright.config.ts'),
    baseUrl: config.baseUrl,
    secrets,
    parentEnv: process.env,
  },
  shutdownGraceMs: config.shutdownGraceMs,
  stopping: stopping.signal,
};

await runConsumer({
  // Object form: the password never has to be URL-encoded into a string that could be logged.
  connect: () =>
    amqp.connect(
      { protocol: 'amqp', ...config.amqp, heartbeat: 15 },
      { clientProperties: { connection_name: config.workerId } },
    ),
  engines: config.engines,
  slots: config.slots,
  log,
  stopping: stopping.signal,
  handle: (body, traceparent) => handleDelivery(deps, body, traceparent),
  reconnectMaxMs: config.reconnectMaxMs,
});
log.info('worker exited cleanly');
