import { addToCart, expect, test } from './support/shop-test.ts';

// catalog-regression: twelve cases over catalogue, product pages, navigation and
// cart arithmetic. One tag per case; the tag is the manifest case key, so keep it stable.

const SEEDED = [
  { slug: 'aurora-desk-lamp', name: 'Aurora Desk Lamp', price: '$49.99' },
  { slug: 'nimbus-mug', name: 'Nimbus Mug', price: '$12.50' },
  { slug: 'terra-planter', name: 'Terra Planter', price: '$24.00' },
  { slug: 'drift-notebook', name: 'Drift Notebook', price: '$8.75' },
  { slug: 'cobalt-water-bottle', name: 'Cobalt Water Bottle', price: '$19.99' },
  { slug: 'ember-candle', name: 'Ember Candle', price: '$15.25' },
];

test('catalog shows exactly six products', { tag: '@catalog-count' }, async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('catalog-page')).toBeVisible();
  await expect(page.getByTestId('product-card')).toHaveCount(6);
});

test('catalog lists products in seeded order', { tag: '@catalog-order' }, async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('product-name')).toHaveText(SEEDED.map((p) => p.name));
});

test('catalog shows every seeded price', { tag: '@catalog-prices' }, async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('product-price')).toHaveText(SEEDED.map((p) => p.price));
});

test('product links point at product slugs', { tag: '@catalog-links' }, async ({ page }) => {
  await page.goto('/');
  const links = page.getByTestId('product-link');
  await expect(links).toHaveCount(6);
  for (const [i, p] of SEEDED.entries()) {
    await expect(links.nth(i)).toHaveAttribute('href', `/products/${p.slug}`);
  }
});

test('lamp detail page shows name and price', { tag: '@product-detail-lamp' }, async ({ page }) => {
  await page.goto('/products/aurora-desk-lamp');
  await expect(page.getByTestId('product-detail-name')).toHaveText('Aurora Desk Lamp');
  await expect(page.getByTestId('product-detail-price')).toHaveText('$49.99');
});

test(
  'bottle detail page shows name and price',
  { tag: '@product-detail-bottle' },
  async ({ page }) => {
    await page.goto('/products/cobalt-water-bottle');
    await expect(page.getByTestId('product-detail-name')).toHaveText('Cobalt Water Bottle');
    await expect(page.getByTestId('product-detail-price')).toHaveText('$19.99');
  },
);

test('unknown product slug shows an error', { tag: '@product-not-found' }, async ({ page }) => {
  await page.goto('/products/no-such-product');
  await expect(page.getByTestId('product-error')).toBeVisible();
  await expect(page.getByTestId('add-to-cart-button')).toHaveCount(0);
});

test('unknown route shows the not-found page', { tag: '@route-not-found' }, async ({ page }) => {
  await page.goto('/definitely/not/a/page');
  await expect(page.getByTestId('not-found')).toHaveText('Page not found.');
});

test('home link returns to the catalog', { tag: '@home-link' }, async ({ page }) => {
  await page.goto('/products/ember-candle');
  await page.getByTestId('home-link').click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('product-card')).toHaveCount(6);
});

test('a new session starts with an empty cart', { tag: '@cart-empty-state' }, async ({ page }) => {
  await page.goto('/cart');
  await expect(page.getByTestId('cart-empty')).toBeVisible();
  await expect(page.getByTestId('cart-count')).toHaveText('0');
});

test('cart badge counts items across products', { tag: '@cart-badge' }, async ({ page }) => {
  await addToCart(page, 'nimbus-mug');
  await addToCart(page, 'drift-notebook');
  await expect(page.getByTestId('cart-count')).toHaveText('2');
});

test(
  'discounted cart total is subtotal minus coupon plus tax',
  { tag: '@discounted-total' },
  async ({ page }) => {
    await addToCart(page, 'cobalt-water-bottle');
    await page.goto('/cart');
    await page.getByTestId('coupon-input').fill('SAVE10');
    await page.getByTestId('coupon-apply-button').click();
    await expect(page.getByTestId('coupon-applied')).toBeVisible();

    // 19.99 − 2.00 (10%, rounded) = 17.99; tax 8% = 1.44 (1.4392); total 19.43.
    await expect(page.getByTestId('cart-subtotal')).toHaveText('$19.99');
    await expect(page.getByTestId('cart-discount')).toHaveText('-$2.00');
    await expect(page.getByTestId('cart-tax')).toHaveText('$1.44');
    await expect(page.getByTestId('cart-total')).toHaveText('$19.43');
  },
);
