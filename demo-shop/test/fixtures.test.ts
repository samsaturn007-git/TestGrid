import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { mulberry32 } from '../src/server/prng.ts';
import { ShopClient, startShop } from './helpers.ts';
import type { RunningShop } from './helpers.ts';

describe('shop API', () => {
  let shop: RunningShop;
  before(async () => {
    shop = await startShop();
  });
  after(() => shop.close());

  it('serves health and the 6-product catalog', async () => {
    const c = new ShopClient(shop.baseUrl);
    assert.equal((await c.request('GET', '/healthz')).status, 200);
    const res = await c.request('GET', '/api/products');
    assert.equal((res.body.items as unknown[]).length, 6);
  });

  it('adds, changes and removes cart lines', async () => {
    const c = new ShopClient(shop.baseUrl);
    await c.add('p-002', 2);
    let cart = await c.request('PATCH', '/api/cart/items/p-002', { quantity: 3 });
    assert.equal(cart.body.subtotalCents, 3750);
    cart = await c.request('DELETE', '/api/cart/items/p-002');
    assert.deepEqual(cart.body.lines, []);
  });

  it('rejects unknown fields, bad JSON and unknown coupons', async () => {
    const c = new ShopClient(shop.baseUrl);
    assert.equal(
      (await c.request('POST', '/api/cart/items', { productId: 'p-001', quantity: 1, price: 0 }))
        .status,
      400,
    );
    assert.equal((await c.request('POST', '/api/cart/items', '{not json')).status, 400);
    assert.equal((await c.request('POST', '/api/cart/coupon', { code: 'BOGUS' })).status, 422);
  });

  it('declines cards ending in 0002 and approves others', async () => {
    const c = new ShopClient(shop.baseUrl);
    await c.add('p-004', 1);
    const declined = await c.order('4000000000000002');
    assert.equal(declined.status, 402);
    assert.equal(declined.body.error, 'card_declined');
    const ok = await c.order('4242424242424242');
    assert.equal(ok.status, 201);
    assert.match(String(ok.body.orderNumber), /^ORD-\d{6}$/);
    const summary = await c.request('GET', `/api/orders/${String(ok.body.orderNumber)}`);
    assert.equal(summary.body.cardLast4, '4242');
    assert.equal(JSON.stringify(summary.body).includes('4242424242424242'), false);
  });

  it('rejects unknown fixture modes and extra fields', async () => {
    const c = new ShopClient(shop.baseUrl);
    assert.equal((await c.fixture('BROWSER_LAUNCH', 1)).status, 400);
    assert.equal(
      (await c.request('POST', '/__fixture/session', { mode: 'PASS', seed: 1, url: 'http://x' }))
        .status,
      400,
    );
    assert.equal((await c.fixture('PASS', -1)).status, 400);
  });

  it('SELECTOR_DRIFT renames only the checkout test id', async () => {
    const pass = new ShopClient(shop.baseUrl);
    const drift = new ShopClient(shop.baseUrl);
    await pass.fixture('PASS', 1);
    await drift.fixture('SELECTOR_DRIFT', 1);
    assert.equal(
      (await pass.request('GET', '/api/ui-config')).body.checkoutButtonTestId,
      'checkout-button',
    );
    assert.equal(
      (await drift.request('GET', '/api/ui-config')).body.checkoutButtonTestId,
      'cart-checkout-cta',
    );
  });

  it('TIMEOUT holds the order API open until the client gives up', async () => {
    const c = new ShopClient(shop.baseUrl);
    await c.fixture('TIMEOUT', 1);
    await c.add('p-001', 1);
    const started = Date.now();
    await assert.rejects(c.order(undefined, { signal: AbortSignal.timeout(400) }), {
      name: 'TimeoutError',
    });
    assert.ok(Date.now() - started >= 350);
  });

  it('AUTOMATION_FLAKE: the late/on-time pattern is fixed by the seed', async () => {
    async function pattern(seed: number): Promise<boolean[]> {
      const c = new ShopClient(shop.baseUrl);
      await c.fixture('AUTOMATION_FLAKE', seed);
      const late: boolean[] = [];
      for (let i = 0; i < 6; i++) {
        await c.add('p-004', 1);
        const { body } = await c.order();
        const t0 = Date.now();
        await c.request('GET', `/api/orders/${String(body.orderNumber)}`);
        late.push(Date.now() - t0 >= 250); // flakeDelayMs is 300 in tests
      }
      return late;
    }
    const next = mulberry32(7);
    const expected = Array.from({ length: 6 }, () => next() < 0.5);
    assert.ok(
      expected.includes(true) && expected.includes(false),
      'seed 7 should mix late and on-time',
    );
    assert.deepEqual(await pattern(7), expected);
    assert.deepEqual(await pattern(7), expected);
  });
});

// PRD-01 AC-3: two concurrent fixture sessions with different modes don't affect each other.
describe('AC-3 fixture session isolation', () => {
  let shop: RunningShop;
  before(async () => {
    shop = await startShop();
  });
  after(() => shop.close());

  it('PASS and PRODUCT_DEFECT sessions interleaved concurrently keep their own totals', async () => {
    const pairs = Array.from({ length: 10 }, () => ({
      pass: new ShopClient(shop.baseUrl),
      defect: new ShopClient(shop.baseUrl),
    }));
    await Promise.all(
      pairs.flatMap(({ pass, defect }) => [
        pass.fixture('PASS', 1),
        defect.fixture('PRODUCT_DEFECT', 1),
      ]),
    );
    await Promise.all(
      pairs.flatMap(({ pass, defect }) => [
        pass.add('p-001', 1),
        defect.add('p-001', 1),
        pass.add('p-002', 2),
        defect.add('p-002', 2),
      ]),
    );
    const totals = await Promise.all(
      pairs.map(async ({ pass, defect }) => ({
        pass: (await pass.request('GET', '/api/cart')).body.totalCents,
        defect: (await defect.request('GET', '/api/cart')).body.totalCents,
      })),
    );
    for (const t of totals) assert.deepEqual(t, { pass: 8099, defect: 8299 });
  });

  it('a hanging TIMEOUT session does not block a PASS session', async () => {
    const stuck = new ShopClient(shop.baseUrl);
    const healthy = new ShopClient(shop.baseUrl);
    await stuck.fixture('TIMEOUT', 1);
    await healthy.fixture('PASS', 1);
    await stuck.add('p-001', 1);
    await healthy.add('p-001', 1);
    const controller = new AbortController();
    const hanging = stuck.order(undefined, { signal: controller.signal }).catch(() => 'aborted');
    const ok = await healthy.order();
    assert.equal(ok.status, 201);
    controller.abort();
    assert.equal(await hanging, 'aborted');
  });

  it('/__fixture/reset clears carts', async () => {
    const c = new ShopClient(shop.baseUrl);
    await c.fixture('PASS', 1);
    await c.add('p-001', 1);
    assert.equal((await c.request('POST', '/__fixture/reset')).status, 204);
    assert.deepEqual((await c.request('GET', '/api/cart')).body.lines, []);
  });
});

// PRD-01 AC-4: /__fixture/* returns 404 when fixtures are disabled.
describe('AC-4 fixtures disabled', () => {
  let shop: RunningShop;
  before(async () => {
    shop = await startShop({ fixturesEnabled: false });
  });
  after(() => shop.close());

  for (const [method, path] of [
    ['POST', '/__fixture/session'],
    ['POST', '/__fixture/reset'],
    ['GET', '/__fixture/slow?class=S'],
    ['GET', '/__fixture'],
  ] as const) {
    it(`${method} ${path} → 404`, async () => {
      const c = new ShopClient(shop.baseUrl);
      const res = await c.request(
        method,
        path,
        method === 'POST' ? { mode: 'PRODUCT_DEFECT', seed: 1 } : undefined,
      );
      assert.equal(res.status, 404);
    });
  }

  it('the shop itself still works', async () => {
    const c = new ShopClient(shop.baseUrl);
    assert.equal((await c.request('GET', '/healthz')).body.fixtures, 'disabled');
    assert.equal((await c.add('p-001', 1)).status, 201);
  });
});
