import { isIP } from 'node:net';

// True for loopback, RFC 1918, link-local and IPv6 ULA addresses. Fixture routes
// answer only these, so a shop accidentally published to the internet hides them.
export function isPrivateAddress(raw: string | undefined): boolean {
  if (!raw) return false;
  const ip = raw.startsWith('::ffff:') && isIP(raw.slice(7)) === 4 ? raw.slice(7) : raw;
  const family = isIP(ip);
  if (family === 4) {
    const [a, b] = ip.split('.').map(Number) as [number, number];
    return (
      a === 127 ||
      a === 10 ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 169 && b === 254)
    );
  }
  if (family === 6) {
    const lower = ip.toLowerCase();
    return (
      lower === '::1' || lower.startsWith('fc') || lower.startsWith('fd') || /^fe[89ab]/.test(lower)
    );
  }
  return false;
}
