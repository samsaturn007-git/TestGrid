import { findProductById } from './catalog.ts';
import type { FixtureMode } from './fixture-mode.ts';

export const TAX_RATE_PERCENT = 8;
export const COUPON_CODE = 'SAVE10';
export const COUPON_PERCENT_OFF = 10;
// PRODUCT_DEFECT ground truth: the total is wrong by exactly this much.
export const PRODUCT_DEFECT_TOTAL_SKEW_CENTS = 200;

export interface CartLineView {
  productId: string;
  slug: string;
  name: string;
  unitPriceCents: number;
  quantity: number;
  lineTotalCents: number;
}

export interface CartTotals {
  subtotalCents: number;
  discountCents: number;
  taxCents: number;
  totalCents: number;
}

// Integer percentage of a cent amount, rounded half up (inputs are never negative).
function percentOf(cents: number, percent: number): number {
  return Math.round((cents * percent) / 100);
}

export function buildCartLines(quantities: ReadonlyMap<string, number>): CartLineView[] {
  const lines: CartLineView[] = [];
  for (const [productId, quantity] of quantities) {
    const product = findProductById(productId);
    if (!product) continue;
    lines.push({
      productId,
      slug: product.slug,
      name: product.name,
      unitPriceCents: product.priceCents,
      quantity,
      lineTotalCents: product.priceCents * quantity,
    });
  }
  return lines;
}

export function computeTotals(
  lines: readonly CartLineView[],
  coupon: string | null,
  mode: FixtureMode,
): CartTotals {
  const subtotalCents = lines.reduce((sum, l) => sum + l.lineTotalCents, 0);
  const discountCents = coupon === COUPON_CODE ? percentOf(subtotalCents, COUPON_PERCENT_OFF) : 0;
  const taxCents = percentOf(subtotalCents - discountCents, TAX_RATE_PERCENT);
  let totalCents = subtotalCents - discountCents + taxCents;
  if (mode === 'PRODUCT_DEFECT' && lines.length > 0) totalCents += PRODUCT_DEFECT_TOTAL_SKEW_CENTS;
  return { subtotalCents, discountCents, taxCents, totalCents };
}
