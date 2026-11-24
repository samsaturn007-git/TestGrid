import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { loadConfig } from '../src/config.ts';

const base = {
  TESTGRID_API_URL: 'http://api:8081',
  TESTGRID_WORKER_TOKEN: 'fake-service-token-for-tests-0123456789abcdef',
  DEMO_SHOP_INTERNAL: 'http://demo-shop:4173',
  RABBITMQ_USER: 'testgrid',
  RABBITMQ_PASSWORD: 'fake-password',
};

describe('loadConfig', () => {
  it('has no fault by default and falls back to worker-<hostname>', () => {
    const c = loadConfig(base);
    assert.equal(c.fault, null);
    assert.match(c.workerId, /^worker-[A-Za-z0-9._-]+$/);
    assert.equal(c.slots, 1);
  });

  it('honours TG_FAULT only with TESTGRID_PROFILE=local', () => {
    assert.throws(
      () => loadConfig({ ...base, TG_FAULT: 'after_claim' }),
      /only honoured with TESTGRID_PROFILE=local/,
    );
    assert.equal(
      loadConfig({ ...base, TG_FAULT: 'after_claim', TESTGRID_PROFILE: 'local' }).fault,
      'after_claim',
    );
    // The API's point is accepted and ignored here.
    assert.equal(
      loadConfig({ ...base, TG_FAULT: 'after_publish_before_mark', TESTGRID_PROFILE: 'local' })
        .fault,
      null,
    );
    assert.throws(() => loadConfig({ ...base, TG_FAULT: 'rm -rf', TESTGRID_PROFILE: 'local' }));
  });

  it('PRD-07 TG_FAULT=browser_launch arms the launch fault only in the local profile', () => {
    assert.throws(
      () => loadConfig({ ...base, TG_FAULT: 'browser_launch' }),
      /only honoured with TESTGRID_PROFILE=local/,
    );
    const armed = loadConfig({ ...base, TG_FAULT: 'browser_launch', TESTGRID_PROFILE: 'local' });
    assert.equal(armed.browserLaunchFault, true);
    assert.equal(armed.fault, null, 'not a kill point');
    assert.equal(loadConfig({ ...base, TESTGRID_PROFILE: 'local' }).browserLaunchFault, false);
  });

  it('refuses slots outside 1..16 and a non-hostname broker host', () => {
    assert.throws(() => loadConfig({ ...base, TESTGRID_WORKER_SLOTS: '17' }));
    assert.throws(() => loadConfig({ ...base, RABBITMQ_HOST: 'evil.example/x' }));
  });

  it('PRD-06: honours the canary only in the local profile and only in a safe shape', () => {
    assert.equal(loadConfig(base).canary, null);
    assert.equal(loadConfig({ ...base, TESTGRID_CANARY_SECRET: '' }).canary, null);
    assert.throws(
      () => loadConfig({ ...base, TESTGRID_CANARY_SECRET: 'tg-canary-0123456789abcdef' }),
      /only honoured with TESTGRID_PROFILE=local/,
    );
    assert.equal(
      loadConfig({
        ...base,
        TESTGRID_PROFILE: 'local',
        TESTGRID_CANARY_SECRET: 'tg-canary-0123456789abcdef',
      }).canary,
      'tg-canary-0123456789abcdef',
    );
    assert.throws(
      () => loadConfig({ ...base, TESTGRID_PROFILE: 'local', TESTGRID_CANARY_SECRET: 'short' }),
      /TESTGRID_CANARY_SECRET/,
    );
  });
});
