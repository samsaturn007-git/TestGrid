// FR-7: map a Playwright failure to a contracts §2.1 ErrorClass where it is observed.
// Order matters: a test timeout or a launch failure can mention a locator too, so the
// most specific signal wins. The demo-shop fixtures are the ground truth (SECURITY doc).

export type ErrorClass =
  | 'ASSERTION'
  | 'TEST_TIMEOUT'
  | 'SELECTOR_NOT_FOUND'
  | 'BROWSER_LAUNCH'
  | 'WORKER_LOST'
  | 'WORKER_SHUTDOWN'
  | 'LEASE_EXPIRED'
  | 'NETWORK'
  | 'ARTIFACT_STORE'
  | 'CONFIG'
  | 'CANCELLED'
  | 'UNKNOWN';

// eslint-disable-next-line no-control-regex
const ANSI = /\u001b\[[0-9;]*[A-Za-z]/g;

export function stripAnsi(text: string): string {
  return text.replace(ANSI, '');
}

export function classifyFailure(rawMessage: string, resultStatus: string): ErrorClass {
  const message = stripAnsi(rawMessage);
  if (resultStatus === 'timedOut' || /Test timeout of \d+ms exceeded/.test(message)) {
    return 'TEST_TIMEOUT';
  }
  if (/browserType\.launch|Executable doesn't exist|Failed to launch/.test(message)) {
    return 'BROWSER_LAUNCH';
  }
  // expect(...) failures are assertions even when the element is missing: the test reached
  // its check and the page didn't satisfy it (PRODUCT_DEFECT, AUTOMATION_FLAKE).
  if (/^\s*(\w*Error: )?expect\(/.test(message)) {
    return 'ASSERTION';
  }
  // An action that never found its target: the selector itself is wrong (SELECTOR_DRIFT).
  if (/locator\.\w+: Timeout \d+ms exceeded|waiting for (getBy\w+|locator)\(/.test(message)) {
    return 'SELECTOR_NOT_FOUND';
  }
  if (/net::ERR_|NS_ERROR_|ECONNREFUSED|ECONNRESET/.test(message)) {
    return 'NETWORK';
  }
  return 'UNKNOWN';
}
