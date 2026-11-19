export interface Product {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly description: string;
  readonly priceCents: number;
}

// Seeded catalogue. Prices are integer cents so totals never hit float rounding.
export const CATALOG: readonly Product[] = Object.freeze([
  {
    id: 'p-001',
    slug: 'aurora-desk-lamp',
    name: 'Aurora Desk Lamp',
    description: 'Warm LED lamp with a dimmable brass arm.',
    priceCents: 4999,
  },
  {
    id: 'p-002',
    slug: 'nimbus-mug',
    name: 'Nimbus Mug',
    description: 'Stoneware mug, 350 ml, speckled glaze.',
    priceCents: 1250,
  },
  {
    id: 'p-003',
    slug: 'terra-planter',
    name: 'Terra Planter',
    description: 'Terracotta planter with a drainage saucer.',
    priceCents: 2400,
  },
  {
    id: 'p-004',
    slug: 'drift-notebook',
    name: 'Drift Notebook',
    description: 'A5 dot-grid notebook, 160 pages.',
    priceCents: 875,
  },
  {
    id: 'p-005',
    slug: 'cobalt-water-bottle',
    name: 'Cobalt Water Bottle',
    description: 'Insulated steel bottle, 750 ml.',
    priceCents: 1999,
  },
  {
    id: 'p-006',
    slug: 'ember-candle',
    name: 'Ember Candle',
    description: 'Soy candle with cedar and orange peel.',
    priceCents: 1525,
  },
]);

export function findProductById(id: string): Product | undefined {
  return CATALOG.find((p) => p.id === id);
}

export function findProductBySlug(slug: string): Product | undefined {
  return CATALOG.find((p) => p.slug === slug);
}
