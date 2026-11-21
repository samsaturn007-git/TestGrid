import path from 'node:path';
import amqp from 'amqplib';
import { WorkerApiClient } from './api-client.ts';
import { loadConfig } from './config.ts';
import { runConsumer } from './consumer.ts';
import { createLogger } from './logger.ts';
import { DEFAULT_WORKER_ROOT, loadManifest } from './manifest.ts';
import { WorkerRegistration } from './registration.ts';
import { handleDelivery } from './worker.ts';
import type { FaultPoint } from './worker.ts';

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

const api = new WorkerApiClient(config.apiUrl, config.token);
const stopping = new AbortController();
const registration = new WorkerRegistration({
  api,
  log,
  registration: {
    name: config.workerId,
    version: config.version,
    engines: config.engines,
    slots: config.slots,
  },
  stopping: stopping.signal,
});
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
    // FR-7: DRAINING in the registry while the current attempts finish.
    void registration.drain();
    stopping.abort();
  });
}

const deps = {
  api,
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
  fault: (point: FaultPoint) => {
    if (point !== config.fault) return;
    // A kill -9 at this exact point: no completion, no ack, no drain, no clean close.
    log.warn({ fault: point }, 'TG_FAULT fired: killing the worker process');
    process.kill(process.pid, 'SIGKILL');
  },
};
if (config.fault) log.warn({ fault: config.fault }, 'TG_FAULT armed');

// FR-7: register before taking any message.
if (await registration.start()) {
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
}
registration.stop();
log.info('worker exited cleanly');
