import { addToCart, expect, fillCheckout, placeOrder, test } from './support/shop-test.ts';

// checkout-smoke: six cases, one tag each. Tags are the case keys the manifest
// (PRD-03) uses to select cases, so they must stay stable.

test(
  'catalog lists the seeded products and opens a product',
  { tag: '@browse-catalog' },
  async ({ page }) => {
    await page.goto('/');
    const cards = page.getByTestId('product-card');
    await expect(cards).toHaveCount(6);
    await cards.filter({ hasText: 'Nimbus Mug' }).getByTestId('product-link').click();
    await expect(page.getByTestId('product-detail-name')).toHaveText('Nimbus Mug');
    await expect(page.getByTestId('product-detail-price')).toHaveText('$12.50');
  },
);

test('cart supports add, quantity change and remove', { tag: '@add-to-cart' }, async ({ page }) => {
  await addToCart(page, 'aurora-desk-lamp');
  await expect(page.getByTestId('cart-count')).toHaveText('1');

  await page.getByTestId('cart-link').click();
  const line = page.getByTestId('cart-line');
  await expect(line).toHaveCount(1);
  await expect(line.getByTestId('cart-line-name')).toHaveText('Aurora Desk Lamp');

  await line.getByTestId('cart-line-increase').click();
  await expect(line.getByTestId('cart-line-quantity')).toHaveText('2');
  await expect(line.getByTestId('cart-line-total')).toHaveText('$99.98');

  await line.getByTestId('cart-line-decrease').click();
  await expect(line.getByTestId('cart-line-quantity')).toHaveText('1');

  await line.getByTestId('cart-line-remove').click();
  await expect(page.getByTestId('cart-empty')).toBeVisible();
  await expect(page.getByTestId('cart-count')).toHaveText('0');
});

test('cart total includes the fixed 8% tax', { tag: '@cart-total' }, async ({ page }) => {
  await addToCart(page, 'aurora-desk-lamp');
  await addToCart(page, 'nimbus-mug');
  await page.goto('/cart');
  await page
    .getByTestId('cart-line')
    .filter({ hasText: 'Nimbus Mug' })
    .getByTestId('cart-line-increase')
    .click();

  // 49.99 + 2 × 12.50 = 74.99; tax 8% = 6.00 (5.9992 rounded); total 80.99.
  await expect(page.getByTestId('cart-subtotal')).toHaveText('$74.99');
  await expect(page.getByTestId('cart-tax')).toHaveText('$6.00');
  await expect(page.getByTestId('cart-total')).toHaveText('$80.99');
});

test(
  'coupon SAVE10 takes 10% off and unknown codes are rejected',
  { tag: '@apply-coupon' },
  async ({ page }) => {
    await addToCart(page, 'terra-planter');
    await page.goto('/cart');

    await page.getByTestId('coupon-input').fill('BOGUS');
    await page.getByTestId('coupon-apply-button').click();
    await expect(page.getByTestId('coupon-error')).toHaveText('Unknown coupon code.');

    await page.getByTestId('coupon-input').fill('SAVE10');
    await page.getByTestId('coupon-apply-button').click();
    await expect(page.getByTestId('coupon-applied')).toHaveText('Coupon SAVE10 applied.');
    await expect(page.getByTestId('cart-discount')).toHaveText('-$2.40');
  },
);

test(
  'checkout with an approved card shows an order number',
  { tag: '@checkout-happy-path' },
  async ({ page }) => {
    await addToCart(page, 'drift-notebook');
    await page.goto('/cart');
    await page.getByTestId('checkout-button').click();
    await expect(page.getByTestId('checkout-page')).toBeVisible();

    await fillCheckout(page, '4242 4242 4242 4242');
    expect(await placeOrder(page)).toBe(201);

    await expect(page.getByTestId('order-confirmation-banner')).toBeVisible();
    await expect(page.getByTestId('order-number')).toHaveText(/^ORD-\d{6}$/);
  },
);

test('a card ending in 0002 is declined', { tag: '@card-declined' }, async ({ page }) => {
  await addToCart(page, 'ember-candle');
  await page.goto('/cart');
  await page.getByTestId('checkout-button').click();

  await fillCheckout(page, '4000 0000 0000 0002');
  expect(await placeOrder(page)).toBe(402);

  await expect(page.getByTestId('payment-error')).toHaveText('Your card was declined.');
  await expect(page).toHaveURL(/\/checkout$/);
  await expect(page.getByTestId('order-confirmation-banner')).toHaveCount(0);
});
