import { expect, test as base } from '@playwright/test';
import type { Page } from '@playwright/test';
import { parseFixtureEnv } from './fixture-env.ts';

export const fixtureEnv = parseFixtureEnv(process.env);

/**
 * Every test gets its own browser context, so it also gets its own shop session:
 * the fixture call sets the session cookie on this context only.
 *
 * The seed is offset by the Playwright retry index. The shop's PRNG then decides
 * each try deterministically: the same FIXTURE_SEED always gives the same
 * late/on-time pattern across retries (AUTOMATION_FLAKE).
 */
export const test = base.extend<{ fixtureSession: void }>({
  fixtureSession: [
    async ({ context }, use, testInfo) => {
      const res = await context.request.post('/__fixture/session', {
        data: { mode: fixtureEnv.mode, seed: fixtureEnv.seed + testInfo.retry },
      });
      expect(res.status(), 'fixture session must be created (is DEMO_SHOP_FIXTURES=enabled?)').toBe(
        201,
      );
      await use();
    },
    { auto: true },
  ],
});

export { expect };

export async function addToCart(page: Page, slug: string): Promise<void> {
  await page.goto(`/products/${slug}`);
  await page.getByTestId('add-to-cart-button').click();
  await expect(page.getByTestId('added-to-cart')).toBeVisible();
}

export async function fillCheckout(page: Page, cardNumber: string): Promise<void> {
  await page.getByTestId('checkout-name').fill('Test Buyer');
  await page.getByTestId('checkout-email').fill('buyer@example.test');
  await page.getByTestId('checkout-address').fill('1 Fixture Lane');
  await page.getByTestId('checkout-card-number').fill(cardNumber);
}

/**
 * Clicks "Place order" and waits for the order API with no timeout of its own, so a
 * silent order service (fixture TIMEOUT) surfaces as a Playwright *test* timeout.
 */
export async function placeOrder(page: Page): Promise<number> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith('/api/orders') && r.request().method() === 'POST',
      { timeout: 0 },
    ),
    page.getByTestId('place-order-button').click(),
  ]);
  return response.status();
}
