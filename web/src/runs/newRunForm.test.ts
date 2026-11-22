import { describe, expect, it } from 'vitest';
import type { CreateRunRequest } from '../api/types.ts';
import { CATALOG, CHECKOUT, suite } from '../test/api.ts';
import {
  apiFieldErrors,
  buildRequest,
  formFieldFor,
  IdempotencyKeys,
  initialValues,
  runnableSuites,
  validate,
  type NewRunValues,
} from './newRunForm.ts';

const base = (patch: Partial<NewRunValues> = {}): NewRunValues => ({
  ...initialValues(CHECKOUT, true),
  ...patch,
});

describe('new-run form rules (PRD-05 FR-2)', () => {
  describe('initial values', () => {
    it('ticks every engine the suite allows, in canonical order', () => {
      const s = suite({ id: 'x', suiteKey: 'x', allowedEngines: ['webkit', 'chromium'] });
      expect(initialValues(s, true).engines).toEqual(['chromium', 'webkit']);
    });

    it('starts on PASS when fixtures are allowed, and on no fixture when they are not', () => {
      expect(initialValues(CHECKOUT, true).fixtureMode).toBe('PASS');
      expect(initialValues(CHECKOUT, false).fixtureMode).toBeNull();
    });

    it('uses the first declared mode when PASS is not declared, and none when nothing is', () => {
      const noPass = suite({ id: 'a', suiteKey: 'a', fixtureModes: ['TIMEOUT'] });
      expect(initialValues(noPass, true).fixtureMode).toBe('TIMEOUT');
      const none = suite({ id: 'b', suiteKey: 'b', fixtureModes: [] });
      expect(initialValues(none, true).fixtureMode).toBeNull();
    });

    it('offers only current, enabled suites', () => {
      const disabled = suite({ id: 'd', suiteKey: 'disabled', enabled: false });
      const old = suite({ id: 'o', suiteKey: 'old', current: false });
      expect(runnableSuites([CHECKOUT, disabled, old, CATALOG])).toEqual([CHECKOUT, CATALOG]);
    });
  });

  describe('validation mirrors the API (contracts §4)', () => {
    it('accepts the defaults', () => {
      expect(validate(base(), CHECKOUT)).toEqual({});
    });

    it('needs a suite and at least one engine', () => {
      expect(validate(base({ engines: [] }), undefined)).toEqual({
        suite: 'Choose a suite.',
        engines: 'Choose at least one engine.',
      });
    });

    it('refuses an engine the suite does not allow', () => {
      const s = suite({ id: 'c', suiteKey: 'c', allowedEngines: ['chromium'] });
      expect(validate(base({ engines: ['chromium', 'webkit'] }), s).engines).toMatch(
        /only engines/,
      );
    });

    it('refuses a fixture mode the suite does not declare', () => {
      expect(validate(base({ fixtureMode: 'TIMEOUT' }), CATALOG).fixtureMode).toMatch(/declare/);
    });

    it.each(['', '-1', '1.5', 'abc', '2147483648', '99999999999'])('refuses seed %j', (seed) => {
      expect(validate(base({ seed }), CHECKOUT).seed).toMatch(/whole number/);
    });

    it.each(['0', '42', ' 7 ', '2147483647'])('accepts seed %j', (seed) => {
      expect(validate(base({ seed }), CHECKOUT).seed).toBeUndefined();
    });

    it('ignores the seed when no fixture is sent', () => {
      expect(validate(base({ fixtureMode: null, seed: 'x' }), CHECKOUT)).toEqual({});
    });

    it.each(['main', 'feat/catalog-sort', 'release-1.2', 'a_b'])('accepts branch %j', (branch) => {
      expect(validate(base({ branch }), CHECKOUT).branch).toBeUndefined();
    });

    it.each(['a..b', 'gh:main', 'has space', 'https://example.test/x', 'x'.repeat(201)])(
      'refuses branch %j',
      (branch) => {
        expect(validate(base({ branch }), CHECKOUT).branch).toBeDefined();
      },
    );

    it.each(['abc1234', 'a'.repeat(64), '0123456789abcdef0123456789abcdef01234567'])(
      'accepts commit SHA %j',
      (commitSha) => {
        expect(validate(base({ commitSha }), CHECKOUT).commitSha).toBeUndefined();
      },
    );

    it.each(['abc123', 'ABC1234', 'zz91zz91', 'a'.repeat(65)])(
      'refuses commit SHA %j',
      (commitSha) => {
        expect(validate(base({ commitSha }), CHECKOUT).commitSha).toMatch(/hex/);
      },
    );
  });

  describe('request body', () => {
    it('sends LOCAL targets in canonical engine order, a MANUAL trigger and the fixture', () => {
      const request = buildRequest(base({ engines: ['webkit', 'chromium'], seed: ' 7 ' }));
      expect(request).toEqual({
        suiteKey: 'checkout-smoke',
        targets: [
          { executor: 'LOCAL', engine: 'chromium' },
          { executor: 'LOCAL', engine: 'webkit' },
        ],
        trigger: 'MANUAL',
        fixture: { mode: 'PASS', seed: 7 },
      });
    });

    it('leaves out the fixture and empty source fields instead of sending blanks', () => {
      const request = buildRequest(base({ fixtureMode: null, branch: '  ', commitSha: '' }));
      expect(request).not.toHaveProperty('fixture');
      expect(request).not.toHaveProperty('source');
      expect(request).not.toHaveProperty('sharding');
    });

    it('sends trimmed branch and commit SHA as source', () => {
      const request = buildRequest(base({ branch: ' main ', commitSha: 'abc1234' }));
      expect(request.source).toEqual({ branch: 'main', commitSha: 'abc1234' });
    });

    it('can only carry contract fields: no URL, script, regex or Playwright flag (ADR-003)', () => {
      const request = buildRequest(base({ branch: 'main', commitSha: 'abc1234' }));
      const allowed: (keyof CreateRunRequest)[] = [
        'suiteKey',
        'targets',
        'trigger',
        'fixture',
        'source',
        'sharding',
        'idempotencyKey',
      ];
      for (const key of Object.keys(request)) expect(allowed).toContain(key);
      expect(Object.keys(request.source ?? {}).sort()).toEqual(['branch', 'commitSha']);
    });
  });

  describe('API field errors', () => {
    it.each([
      ['suiteKey', 'suite'],
      ['targets', 'engines'],
      ['targets[1]', 'engines'],
      ['targets[0].engine', 'engines'],
      ['fixture', 'fixtureMode'],
      ['fixture.mode', 'fixtureMode'],
      ['fixture.seed', 'seed'],
      ['source.branch', 'branch'],
      ['source.commitSha', 'commitSha'],
      ['idempotencyKey', null],
      ['source.ciRef', null],
    ])('maps %s to %s', (field, expected) => {
      expect(formFieldFor(field)).toBe(expected);
    });

    it('keeps the first message per input and returns the rest as unmapped', () => {
      const { byField, unmapped } = apiFieldErrors([
        { field: 'targets[0].engine', message: 'webkit is not in allowedEngines' },
        { field: 'targets[1].engine', message: 'firefox is not in allowedEngines' },
        { field: 'idempotencyKey', message: 'already used with a different request' },
      ]);
      expect(byField).toEqual({ engines: 'webkit is not in allowedEngines' });
      expect(unmapped).toEqual([
        { field: 'idempotencyKey', message: 'already used with a different request' },
      ]);
    });
  });

  describe('idempotency keys', () => {
    function counter() {
      let n = 0;
      return new IdempotencyKeys(() => `key-${++n}`);
    }

    it('reuses the key when the same request is sent again (a safe retry)', () => {
      const keys = counter();
      const request = buildRequest(base());
      expect(keys.keyFor(request)).toBe('key-1');
      expect(keys.keyFor(buildRequest(base()))).toBe('key-1');
    });

    it('takes a fresh key when anything in the request changes, so it is never a 409', () => {
      const keys = counter();
      expect(keys.keyFor(buildRequest(base()))).toBe('key-1');
      expect(keys.keyFor(buildRequest(base({ engines: ['chromium'] })))).toBe('key-2');
      expect(keys.keyFor(buildRequest(base({ engines: ['chromium'] })))).toBe('key-2');
    });

    it('makes keys the API accepts (1–200 of A-Z a-z 0-9 . _ : -)', () => {
      const key = new IdempotencyKeys().keyFor(buildRequest(base()));
      expect(key).toMatch(/^[A-Za-z0-9._:-]{1,200}$/);
    });
  });
});
