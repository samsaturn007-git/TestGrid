import { randomBytes } from 'node:crypto';
import type { FixtureMode } from './fixture-mode.ts';
import { mulberry32 } from './prng.ts';

export interface Order {
  orderNumber: string;
  totalCents: number;
  cardLast4: string;
  lateConfirmation: boolean;
}

export interface ShopSession {
  readonly id: string;
  readonly mode: FixtureMode;
  readonly seed: number;
  readonly nextRandom: () => number;
  readonly cart: Map<string, number>;
  coupon: string | null;
  readonly orders: Map<string, Order>;
  orderSeq: number;
}

const SESSION_ID = /^[A-Za-z0-9_-]{22}$/;

// Everything a session can change (cart, coupon, orders, PRNG state) lives on the
// session object, so two browser contexts never share state: that is the whole
// isolation guarantee for concurrent fixture modes (PRD-01 AC-3).
export class SessionStore {
  private readonly sessions = new Map<string, ShopSession>();
  private readonly maxSessions: number;

  constructor(maxSessions = 5000) {
    this.maxSessions = maxSessions;
  }

  create(mode: FixtureMode = 'PASS', seed = 0): ShopSession {
    const session: ShopSession = {
      id: randomBytes(16).toString('base64url'),
      mode,
      seed,
      nextRandom: mulberry32(seed),
      cart: new Map(),
      coupon: null,
      orders: new Map(),
      orderSeq: 0,
    };
    // Map keeps insertion order, so the first key is the oldest session.
    if (this.sessions.size >= this.maxSessions) {
      const oldest = this.sessions.keys().next().value;
      if (oldest !== undefined) this.sessions.delete(oldest);
    }
    this.sessions.set(session.id, session);
    return session;
  }

  get(id: string | undefined): ShopSession | undefined {
    if (!id || !SESSION_ID.test(id)) return undefined;
    return this.sessions.get(id);
  }

  clear(): void {
    this.sessions.clear();
  }

  get size(): number {
    return this.sessions.size;
  }
}
