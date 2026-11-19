import path from 'node:path';
import express from 'express';
import type { NextFunction, Request, Response } from 'express';
import { z } from 'zod';
import { CATALOG, findProductById, findProductBySlug } from './catalog.ts';
import { FIXTURE_MODES } from './fixture-mode.ts';
import { isPrivateAddress } from './network.ts';
import { buildCartLines, computeTotals } from './pricing.ts';
import { SessionStore } from './sessions.ts';
import type { ShopSession } from './sessions.ts';

export interface AppConfig {
  fixturesEnabled: boolean;
  /** Built SPA directory, or null to serve the API only (unit tests). */
  clientDir: string | null;
  /** AUTOMATION_FLAKE: how late a "late" confirmation is. Must exceed the test's expect timeout. */
  flakeDelayMs: number;
  /** TIMEOUT: how long the order API holds a request. Must exceed the test timeout. */
  orderHangMs: number;
}

export interface ShopApp {
  app: express.Express;
  sessions: SessionStore;
  /** Cancels held (TIMEOUT / late) responses so the process can exit cleanly. */
  shutdown: () => void;
}

const COOKIE = 'tg_shop_sid';
const MAX_QUANTITY = 10;
const DECLINED_CARD_SUFFIX = '0002';
// Selector-drift ground truth: the checkout button's test id changes, nothing else.
const CHECKOUT_TEST_ID = 'checkout-button';
const DRIFTED_CHECKOUT_TEST_ID = 'cart-checkout-cta';

const AddItemBody = z.strictObject({
  productId: z.string().max(20),
  quantity: z.number().int().min(1).max(MAX_QUANTITY),
});
const SetQuantityBody = z.strictObject({
  quantity: z.number().int().min(1).max(MAX_QUANTITY),
});
const CouponBody = z.strictObject({ code: z.string().trim().min(1).max(32) });
const OrderBody = z.strictObject({
  name: z.string().trim().min(1).max(100),
  email: z.email().max(200),
  address: z.string().trim().min(1).max(200),
  // Fake payment only: digits, never stored beyond the last four.
  cardNumber: z.string().regex(/^\d{12,19}$/, 'card number must be 12–19 digits'),
});
const FixtureSessionBody = z.strictObject({
  mode: z.enum(FIXTURE_MODES),
  seed: z
    .number()
    .int()
    .min(0)
    .max(2 ** 31 - 1),
});

function readCookie(req: Request, name: string): string | undefined {
  const header = req.headers.cookie;
  if (!header) return undefined;
  for (const part of header.split(';')) {
    const eq = part.indexOf('=');
    if (eq > 0 && part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return undefined;
}

function setSessionCookie(res: Response, session: ShopSession): void {
  res.setHeader('Set-Cookie', `${COOKIE}=${session.id}; Path=/; HttpOnly; SameSite=Lax`);
}

function sessionOf(res: Response): ShopSession {
  return res.locals.session as ShopSession;
}

function validationError(res: Response, error: z.ZodError): void {
  res.status(400).json({
    error: 'validation_failed',
    issues: error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
  });
}

function cartView(session: ShopSession) {
  const lines = buildCartLines(session.cart);
  return {
    lines,
    coupon: session.coupon,
    ...computeTotals(lines, session.coupon, session.mode),
  };
}

export function createApp(config: AppConfig): ShopApp {
  const sessions = new SessionStore();
  const heldTimers = new Set<NodeJS.Timeout>();
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', false);

  app.use((_req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    next();
  });
  app.use(express.json({ limit: '16kb', strict: true }));

  // Responds after delayMs unless the client goes away first (closed browser context).
  function respondLater(res: Response, delayMs: number, send: () => void): void {
    const timer = setTimeout(() => {
      heldTimers.delete(timer);
      if (!res.writableEnded) send();
    }, delayMs);
    heldTimers.add(timer);
    res.on('close', () => {
      clearTimeout(timer);
      heldTimers.delete(timer);
    });
  }

  app.get('/healthz', (_req, res) => {
    res.json({ status: 'ok', fixtures: config.fixturesEnabled ? 'enabled' : 'disabled' });
  });

  if (config.fixturesEnabled) {
    const fixtures = express.Router();
    // Not just disabled-by-flag: requests from public addresses get the same 404 as
    // a shop with fixtures off, so the routes are invisible outside the local network.
    fixtures.use((req, res, next) => {
      if (isPrivateAddress(req.socket.remoteAddress)) next();
      else res.status(404).json({ error: 'not_found' });
    });
    fixtures.post('/session', (req, res) => {
      const parsed = FixtureSessionBody.safeParse(req.body);
      if (!parsed.success) return validationError(res, parsed.error);
      const session = sessions.create(parsed.data.mode, parsed.data.seed);
      setSessionCookie(res, session);
      res.status(201).json({ mode: session.mode, seed: session.seed });
    });
    fixtures.post('/reset', (_req, res) => {
      sessions.clear();
      res.status(204).end();
    });
    app.use('/__fixture', fixtures);
  }

  const api = express.Router();
  api.use((req, res, next) => {
    let session = sessions.get(readCookie(req, COOKIE));
    if (!session) {
      session = sessions.create();
      setSessionCookie(res, session);
    }
    res.locals.session = session;
    res.setHeader('Cache-Control', 'no-store');
    next();
  });

  api.get('/products', (_req, res) => {
    res.json({ items: CATALOG });
  });

  api.get('/products/:slug', (req, res) => {
    const product = findProductBySlug(req.params.slug);
    if (!product) return void res.status(404).json({ error: 'product_not_found' });
    res.json(product);
  });

  api.get('/ui-config', (_req, res) => {
    const drift = sessionOf(res).mode === 'SELECTOR_DRIFT';
    res.json({ checkoutButtonTestId: drift ? DRIFTED_CHECKOUT_TEST_ID : CHECKOUT_TEST_ID });
  });

  api.get('/cart', (_req, res) => {
    res.json(cartView(sessionOf(res)));
  });

  api.post('/cart/items', (req, res) => {
    const parsed = AddItemBody.safeParse(req.body);
    if (!parsed.success) return validationError(res, parsed.error);
    if (!findProductById(parsed.data.productId)) {
      return void res.status(404).json({ error: 'product_not_found' });
    }
    const session = sessionOf(res);
    const current = session.cart.get(parsed.data.productId) ?? 0;
    session.cart.set(parsed.data.productId, Math.min(MAX_QUANTITY, current + parsed.data.quantity));
    res.status(201).json(cartView(session));
  });

  api.patch('/cart/items/:productId', (req, res) => {
    const parsed = SetQuantityBody.safeParse(req.body);
    if (!parsed.success) return validationError(res, parsed.error);
    const session = sessionOf(res);
    if (!session.cart.has(req.params.productId)) {
      return void res.status(404).json({ error: 'line_not_found' });
    }
    session.cart.set(req.params.productId, parsed.data.quantity);
    res.json(cartView(session));
  });

  api.delete('/cart/items/:productId', (req, res) => {
    const session = sessionOf(res);
    session.cart.delete(req.params.productId);
    res.json(cartView(session));
  });

  api.post('/cart/coupon', (req, res) => {
    const parsed = CouponBody.safeParse(req.body);
    if (!parsed.success) return validationError(res, parsed.error);
    const code = parsed.data.code.toUpperCase();
    if (code !== 'SAVE10') {
      return void res
        .status(422)
        .json({ error: 'invalid_coupon', message: 'Unknown coupon code.' });
    }
    const session = sessionOf(res);
    session.coupon = code;
    res.json(cartView(session));
  });

  api.delete('/cart/coupon', (_req, res) => {
    const session = sessionOf(res);
    session.coupon = null;
    res.json(cartView(session));
  });

  api.post('/orders', (req, res) => {
    const parsed = OrderBody.safeParse(req.body);
    if (!parsed.success) return validationError(res, parsed.error);
    const session = sessionOf(res);
    if (session.cart.size === 0) return void res.status(409).json({ error: 'cart_empty' });

    if (session.mode === 'TIMEOUT') {
      // Ground truth for TIMEOUT: the order API stays silent past the test timeout.
      return respondLater(res, config.orderHangMs, () => {
        res.status(504).json({ error: 'order_service_timeout' });
      });
    }

    if (parsed.data.cardNumber.endsWith(DECLINED_CARD_SUFFIX)) {
      return void res
        .status(402)
        .json({ error: 'card_declined', message: 'Your card was declined.' });
    }

    const { totalCents } = cartView(session);
    session.orderSeq += 1;
    const orderNumber = `ORD-${String(session.orderSeq).padStart(6, '0')}`;
    // One PRNG draw per placed order ("try"), so a seed always yields the same pattern.
    const lateConfirmation = session.mode === 'AUTOMATION_FLAKE' && session.nextRandom() < 0.5;
    session.orders.set(orderNumber, {
      orderNumber,
      totalCents,
      cardLast4: parsed.data.cardNumber.slice(-4),
      lateConfirmation,
    });
    session.cart.clear();
    session.coupon = null;
    res.status(201).json({ orderNumber });
  });

  api.get('/orders/:orderNumber', (req, res) => {
    const order = sessionOf(res).orders.get(req.params.orderNumber);
    if (!order) return void res.status(404).json({ error: 'order_not_found' });
    const body = {
      orderNumber: order.orderNumber,
      totalCents: order.totalCents,
      cardLast4: order.cardLast4,
    };
    if (order.lateConfirmation) return respondLater(res, config.flakeDelayMs, () => res.json(body));
    res.json(body);
  });

  api.use((_req, res) => {
    res.status(404).json({ error: 'not_found' });
  });
  app.use('/api', api);

  if (config.clientDir) {
    const clientDir = config.clientDir;
    app.use(express.static(clientDir, { index: false, fallthrough: true }));
    app.get(/^\/(?!api\/|__fixture\/|__fixture$|healthz).*/, (_req, res) => {
      res.sendFile(path.join(clientDir, 'index.html'));
    });
  }

  app.use((_req, res) => {
    res.status(404).json({ error: 'not_found' });
  });

  app.use((err: unknown, _req: Request, res: Response, next: NextFunction) => {
    if (res.headersSent) return next(err);
    const type = (err as { type?: string }).type;
    if (type === 'entity.parse.failed')
      return void res.status(400).json({ error: 'malformed_json' });
    if (type === 'entity.too.large') return void res.status(413).json({ error: 'body_too_large' });
    console.error('demo-shop: unhandled error', err instanceof Error ? err.message : 'unknown');
    res.status(500).json({ error: 'internal_error' });
  });

  return {
    app,
    sessions,
    shutdown: () => {
      for (const t of heldTimers) clearTimeout(t);
      heldTimers.clear();
    },
  };
}
