// Worker-side twin of the API's Redactor: error text from Playwright is cleaned before it
// leaves the worker, so a token echoed into an error message never reaches the API or a log.
const BEARER = /bearer\s+[A-Za-z0-9._~+/=-]+/gi;
const OPAQUE = /[A-Za-z0-9_-]{40,}/g;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/g;
export const MASK = '[REDACTED]';
const MAX = 2000;

export function redact(text: string | null, secrets: readonly string[] = []): string | null {
  if (text === null) return null;
  let out = text.replace(CONTROL, '');
  for (const secret of secrets) if (secret) out = out.split(secret).join(MASK);
  out = out.replace(BEARER, `Bearer ${MASK}`).replace(OPAQUE, MASK);
  return out.length <= MAX ? out : `${out.slice(0, MAX - 1)}…`;
}
