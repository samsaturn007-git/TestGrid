// Worker-side twin of the API's Redactor (PRD-06 FR-5): text from Playwright is cleaned before
// it leaves the worker, so a token echoed into an error message or the log tail never reaches the
// API, the store or a log. The API redacts error text again before storing it.

// Header values first, so "Authorization: Bearer x" and "Cookie: a=b; c=d" lose the whole value.
const HEADER =
  /\b(authorization|proxy-authorization|cookie|set-cookie)(["']?\s*[:=]\s*["']?)[^\r\n"']+/gi;
const BEARER = /bearer\s+[A-Za-z0-9._~+/=-]+/gi;
// Three base64url segments, the first starting like a JSON header ({"...): a JWT.
const JWT = /\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*/g;
// A lease token is 43 base64url characters; also catches other long opaque secrets.
const OPAQUE = /[A-Za-z0-9_-]{40,}/g;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/g;
// eslint-disable-next-line no-control-regex
const CONTROL_KEEP_LINES = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g;
// eslint-disable-next-line no-control-regex
const ANSI = /\u001b\[[0-9;?]*[ -/]*[@-~]/g;
export const MASK = '[REDACTED]';
const MAX = 2000;

/** FR-5: environment variables whose values are secrets, wherever they appear. */
export const SECRET_ENV_NAME = /(^|_)(TOKEN|KEY|SECRET|PASSWORD)$/;
/** Shorter values are not masked: "test" or "1" would erase ordinary words and numbers. */
export const MIN_SECRET_LENGTH = 8;

/** The values of every secret-named variable, longest first (a longer secret may contain a shorter one). */
export function secretsFromEnv(env: NodeJS.ProcessEnv): string[] {
  const values = new Set<string>();
  for (const [name, value] of Object.entries(env)) {
    if (value && value.length >= MIN_SECRET_LENGTH && SECRET_ENV_NAME.test(name)) values.add(value);
  }
  return [...values].sort((a, b) => b.length - a.length);
}

function scrub(text: string, secrets: readonly string[]): string {
  let out = text;
  for (const secret of secrets) if (secret) out = out.split(secret).join(MASK);
  return out
    .replace(HEADER, `$1$2${MASK}`)
    .replace(BEARER, `Bearer ${MASK}`)
    .replace(JWT, MASK)
    .replace(OPAQUE, MASK);
}

/** Error messages: one line of evidence, at most 2,000 characters (contracts §7). */
export function redact(text: string | null, secrets: readonly string[] = []): string | null {
  if (text === null) return null;
  const out = scrub(text.replace(CONTROL, ''), secrets);
  return out.length <= MAX ? out : `${out.slice(0, MAX - 1)}…`;
}

/**
 * The log tail artifact: keeps line breaks and tabs, drops colour codes and other control
 * characters (NUL included, which the API refuses in a log), then keeps the last maxBytes of
 * UTF-8. Redaction runs before the cut, so a secret can't be split into an unmasked tail.
 */
export function redactLog(text: string, secrets: readonly string[], maxBytes: number): string {
  const out = scrub(text.replace(ANSI, '').replace(CONTROL_KEEP_LINES, ''), secrets);
  const bytes = Buffer.from(out, 'utf8');
  if (bytes.length <= maxBytes) return out;
  // Cutting may split a character; drop the partial bytes rather than emit U+FFFD.
  let start = bytes.length - maxBytes;
  while (start < bytes.length && (bytes[start]! & 0xc0) === 0x80) start++;
  return bytes.subarray(start).toString('utf8');
}
