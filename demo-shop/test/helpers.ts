import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import { createApp } from '../src/server/app.ts';
import type { AppConfig } from '../src/server/app.ts';

export interface RunningShop {
  baseUrl: string;
  close: () => Promise<void>;
}

export async function startShop(overrides: Partial<AppConfig> = {}): Promise<RunningShop> {
  const { app, shutdown } = createApp({
    fixturesEnabled: true,
    clientDir: null,
    flakeDelayMs: 300,
    orderHangMs: 60_000,
    ...overrides,
  });
  const server: Server = await new Promise((resolve) => {
    const s = app.listen(0, '127.0.0.1', () => resolve(s));
  });
  const { port } = server.address() as AddressInfo;
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    close: () =>
      new Promise((resolve) => {
        shutdown();
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

/** A tiny HTTP client with its own cookie jar: one instance = one browser context. */
export class ShopClient {
  private cookie: string | undefined;
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  async request(
    method: string,
    path: string,
    body?: unknown,
    init: { signal?: AbortSignal } = {},
  ): Promise<{ status: number; body: Record<string, unknown> }> {
    const headers: Record<string, string> = {};
    if (this.cookie) headers.cookie = this.cookie;
    if (body !== undefined) headers['content-type'] = 'application/json';
    const reqInit: RequestInit = { method, headers };
    if (body !== undefined) reqInit.body = typeof body === 'string' ? body : JSON.stringify(body);
    if (init.signal) reqInit.signal = init.signal;
    const res = await fetch(this.baseUrl + path, reqInit);
    const setCookie = res.headers.get('set-cookie');
    if (setCookie) this.cookie = setCookie.split(';')[0];
    const text = await res.text();
    return { status: res.status, body: text ? (JSON.parse(text) as Record<string, unknown>) : {} };
  }

  fixture(mode: string, seed: number) {
    return this.request('POST', '/__fixture/session', { mode, seed });
  }

  add(productId: string, quantity: number) {
    return this.request('POST', '/api/cart/items', { productId, quantity });
  }

  order(cardNumber = '4242424242424242', init: { signal?: AbortSignal } = {}) {
    return this.request(
      'POST',
      '/api/orders',
      { name: 'Test Buyer', email: 'buyer@example.test', address: '1 Test Lane', cardNumber },
      init,
    );
  }
}
