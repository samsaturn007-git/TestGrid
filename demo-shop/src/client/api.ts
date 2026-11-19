export interface Product {
  id: string;
  slug: string;
  name: string;
  description: string;
  priceCents: number;
}

export interface CartLine {
  productId: string;
  slug: string;
  name: string;
  unitPriceCents: number;
  quantity: number;
  lineTotalCents: number;
}

export interface Cart {
  lines: CartLine[];
  coupon: string | null;
  subtotalCents: number;
  discountCents: number;
  taxCents: number;
  totalCents: number;
}

export interface OrderSummary {
  orderNumber: string;
  totalCents: number;
  cardLast4: string;
}

export interface OrderInput {
  name: string;
  email: string;
  address: string;
  cardNumber: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const init: RequestInit = { method, headers };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(url, init);
  const data = (await res.json().catch(() => ({}))) as { error?: string; message?: string };
  if (!res.ok) {
    throw new ApiError(
      res.status,
      data.error ?? 'error',
      data.message ?? `Request failed (${res.status})`,
    );
  }
  return data as T;
}

export const api = {
  products: () => request<{ items: Product[] }>('GET', '/api/products').then((r) => r.items),
  product: (slug: string) => request<Product>('GET', `/api/products/${encodeURIComponent(slug)}`),
  uiConfig: () => request<{ checkoutButtonTestId: string }>('GET', '/api/ui-config'),
  cart: () => request<Cart>('GET', '/api/cart'),
  addItem: (productId: string, quantity: number) =>
    request<Cart>('POST', '/api/cart/items', { productId, quantity }),
  setQuantity: (productId: string, quantity: number) =>
    request<Cart>('PATCH', `/api/cart/items/${encodeURIComponent(productId)}`, { quantity }),
  removeItem: (productId: string) =>
    request<Cart>('DELETE', `/api/cart/items/${encodeURIComponent(productId)}`),
  applyCoupon: (code: string) => request<Cart>('POST', '/api/cart/coupon', { code }),
  placeOrder: (input: OrderInput) => request<{ orderNumber: string }>('POST', '/api/orders', input),
  order: (orderNumber: string) =>
    request<OrderSummary>('GET', `/api/orders/${encodeURIComponent(orderNumber)}`),
};

export function formatMoney(cents: number): string {
  const sign = cents < 0 ? '-' : '';
  return `${sign}$${(Math.abs(cents) / 100).toFixed(2)}`;
}
