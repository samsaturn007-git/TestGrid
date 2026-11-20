import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { classifyFailure } from '../src/classify.ts';

describe('classifyFailure (FR-7)', () => {
  const cases: [string, string, string][] = [
    ['Error: expect(locator).toHaveText(expected) failed', 'failed', 'ASSERTION'],
    ['Error: expect(received).toBe(expected) // Object.is equality', 'failed', 'ASSERTION'],
    [
      "TimeoutError: locator.click: Timeout 5000ms exceeded.\n  - waiting for getByTestId('x')",
      'failed',
      'SELECTOR_NOT_FOUND',
    ],
    ['\u001b[31mTest timeout of 20000ms exceeded.\u001b[39m', 'timedOut', 'TEST_TIMEOUT'],
    [
      "browserType.launch: Executable doesn't exist at /ms-playwright/chromium",
      'failed',
      'BROWSER_LAUNCH',
    ],
    ['page.goto: net::ERR_CONNECTION_REFUSED at http://demo-shop:4173/', 'failed', 'NETWORK'],
    ['something else entirely', 'failed', 'UNKNOWN'],
    ['', 'failed', 'UNKNOWN'],
  ];
  for (const [message, status, expected] of cases) {
    it(`${expected}: ${message.slice(0, 40)}`, () => {
      assert.equal(classifyFailure(message, status), expected);
    });
  }

  it('a timed-out result is a test timeout whatever the message says', () => {
    assert.equal(classifyFailure("waiting for getByTestId('x')", 'timedOut'), 'TEST_TIMEOUT');
  });
});
