import { expect, test } from '@playwright/test';
import type { Browser } from '@playwright/test';

// PRD-01 AC-3 (browser level): two browser contexts with different fixture modes run
// at the same time and each sees only its own mode.

async function cartTotalIn(browser: Browser, mode: string): Promise<string> {
  const context = await browser.newContext();
  try {
    const res = await context.request.post('/__fixture/session', { data: { mode, seed: 1 } });
    expect(res.status()).toBe(201);
    const page = await context.newPage();
    for (const slug of ['aurora-desk-lamp', 'nimbus-mug', 'nimbus-mug']) {
      await page.goto(`/products/${slug}`);
      await page.getByTestId('add-to-cart-button').click();
      await expect(page.getByTestId('added-to-cart')).toBeVisible();
    }
    await page.goto('/cart');
    await expect(page.getByTestId('cart-subtotal')).toHaveText('$74.99');
    return (await page.getByTestId('cart-total').textContent()) ?? '';
  } finally {
    await context.close();
  }
}

test(
  'concurrent PASS and PRODUCT_DEFECT contexts do not affect each other',
  { tag: '@fixture-isolation' },
  async ({ browser }) => {
    const [defect, pass, defect2, pass2] = await Promise.all([
      cartTotalIn(browser, 'PRODUCT_DEFECT'),
      cartTotalIn(browser, 'PASS'),
      cartTotalIn(browser, 'PRODUCT_DEFECT'),
      cartTotalIn(browser, 'PASS'),
    ]);
    expect({ defect, pass, defect2, pass2 }).toEqual({
      defect: '$82.99',
      pass: '$80.99',
      defect2: '$82.99',
      pass2: '$80.99',
    });
  },
);
