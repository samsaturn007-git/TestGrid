import assert from 'node:assert/strict';
import { Writable } from 'node:stream';
import { setTimeout as sleep } from 'node:timers/promises';
import { describe, it } from 'node:test';
import { ApiError } from '../src/api-client.ts';
import type { Registered } from '../src/api-client.ts';
import { createLogger } from '../src/logger.ts';
import { WorkerRegistration } from '../src/registration.ts';

// Unit tests against a fake registry API (mocked); the real endpoints are tested in the API's
// WorkerRegistryTest.

const quiet = () => createLogger([], {}, new Writable({ write: (_c, _e, done) => done() }));

function fakeApi(script: { failRegister?: number; heartbeat?: (n: number) => number }) {
  const calls: string[] = [];
  let registers = 0;
  let beats = 0;
  return {
    calls,
    api: {
      register: (): Promise<Registered> => {
        calls.push('register');
        if (registers++ < (script.failRegister ?? 0)) {
          return Promise.reject(new ApiError(503, null, 'down'));
        }
        return Promise.resolve({
          id: `00000000-0000-4000-8000-00000000000${registers}`,
          name: 'worker-x',
          state: 'ACTIVE',
          heartbeatIntervalSeconds: 1,
        });
      },
      workerHeartbeat: (id: string) => {
        calls.push(`heartbeat ${id.slice(-1)}`);
        const status = script.heartbeat?.(++beats) ?? 200;
        return status === 200
          ? Promise.resolve('ACTIVE')
          : Promise.reject(new ApiError(status, 'lease-expired', 'lost'));
      },
      drain: (id: string) => {
        calls.push(`drain ${id.slice(-1)}`);
        return Promise.resolve('DRAINING');
      },
    },
  };
}

function registration(api: ReturnType<typeof fakeApi>['api'], stopping = new AbortController()) {
  return new WorkerRegistration({
    api,
    log: quiet(),
    registration: { name: 'worker-x', version: 'dev', engines: ['chromium'], slots: 1 },
    stopping: stopping.signal,
    retryMaxMs: 20,
  });
}

describe('WorkerRegistration against a fake registry', () => {
  it('retries registration until the API answers', async () => {
    const fake = fakeApi({ failRegister: 2 });
    const r = registration(fake.api);
    assert.equal(await r.start(), true);
    assert.deepEqual(fake.calls, ['register', 'register', 'register']);
    assert.ok(r.id);
    r.stop();
  });

  it('gives up registering when the worker is stopping', async () => {
    const fake = fakeApi({ failRegister: 100 });
    const stopping = new AbortController();
    const r = registration(fake.api, stopping);
    setTimeout(() => stopping.abort(), 50);
    assert.equal(await r.start(), false);
    r.stop();
  });

  it('heartbeats, and registers again (draining again) after a 410', async () => {
    const fake = fakeApi({ heartbeat: (n) => (n === 2 ? 410 : 200) });
    const r = registration(fake.api);
    await r.start();
    await r.drain();
    await sleep(2300);
    r.stop();
    assert.deepEqual(fake.calls.slice(0, 5), [
      'register',
      'drain 1',
      'heartbeat 1',
      'heartbeat 1',
      'register',
    ]);
    assert.equal(fake.calls[5], 'drain 2', 'still draining under the new registration');
  });

  it('drain before registration is a no-op that never throws', async () => {
    const fake = fakeApi({});
    await registration(fake.api).drain();
    assert.deepEqual(fake.calls, []);
  });
});
