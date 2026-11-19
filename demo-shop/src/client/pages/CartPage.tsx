import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, formatMoney } from '../api.ts';
import type { Cart } from '../api.ts';
import { useCart } from '../CartContext.tsx';
import { useAsync } from '../useAsync.ts';

export function CartPage() {
  const { cart, setCart } = useCart();
  const uiConfig = useAsync(api.uiConfig, 'ui-config');
  const navigate = useNavigate();
  const [coupon, setCoupon] = useState('');
  const [couponError, setCouponError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!cart) return <p data-testid="cart-loading">Loading cart…</p>;

  async function mutate(action: () => Promise<Cart>) {
    setBusy(true);
    try {
      setCart(await action());
    } finally {
      setBusy(false);
    }
  }

  async function applyCoupon(e: FormEvent) {
    e.preventDefault();
    setCouponError(null);
    setBusy(true);
    try {
      setCart(await api.applyCoupon(coupon));
    } catch (err) {
      setCouponError(err instanceof Error ? err.message : 'Could not apply coupon');
    } finally {
      setBusy(false);
    }
  }

  if (cart.lines.length === 0) {
    return (
      <section data-testid="cart-page">
        <h1>Cart</h1>
        <p data-testid="cart-empty">
          Your cart is empty. <Link to="/">Browse the catalog</Link>
        </p>
      </section>
    );
  }

  const checkoutTestId =
    uiConfig.status === 'ready' ? uiConfig.data.checkoutButtonTestId : 'checkout-button';

  return (
    <section data-testid="cart-page">
      <h1>Cart</h1>
      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th>Price</th>
            <th>Quantity</th>
            <th>Line total</th>
            <th>
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {cart.lines.map((line) => (
            <tr key={line.productId} data-testid="cart-line" data-product-slug={line.slug}>
              <td data-testid="cart-line-name">{line.name}</td>
              <td className="price">{formatMoney(line.unitPriceCents)}</td>
              <td>
                <button
                  type="button"
                  aria-label={`Decrease ${line.name}`}
                  disabled={busy || line.quantity <= 1}
                  onClick={() =>
                    void mutate(() => api.setQuantity(line.productId, line.quantity - 1))
                  }
                  data-testid="cart-line-decrease"
                >
                  −
                </button>{' '}
                <span data-testid="cart-line-quantity">{line.quantity}</span>{' '}
                <button
                  type="button"
                  aria-label={`Increase ${line.name}`}
                  disabled={busy || line.quantity >= 10}
                  onClick={() =>
                    void mutate(() => api.setQuantity(line.productId, line.quantity + 1))
                  }
                  data-testid="cart-line-increase"
                >
                  +
                </button>
              </td>
              <td className="price" data-testid="cart-line-total">
                {formatMoney(line.lineTotalCents)}
              </td>
              <td>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void mutate(() => api.removeItem(line.productId))}
                  data-testid="cart-line-remove"
                >
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <form onSubmit={(e) => void applyCoupon(e)} aria-label="Coupon">
        <label>
          Coupon code{' '}
          <input
            value={coupon}
            onChange={(e) => setCoupon(e.target.value)}
            maxLength={32}
            data-testid="coupon-input"
          />
        </label>{' '}
        <button
          type="submit"
          disabled={busy || coupon.trim() === ''}
          data-testid="coupon-apply-button"
        >
          Apply
        </button>
        {couponError && (
          <p role="alert" className="error" data-testid="coupon-error">
            {couponError}
          </p>
        )}
        {cart.coupon && (
          <p role="status" data-testid="coupon-applied">
            Coupon {cart.coupon} applied.
          </p>
        )}
      </form>

      <dl className="totals">
        <dt>Subtotal</dt>
        <dd data-testid="cart-subtotal">{formatMoney(cart.subtotalCents)}</dd>
        <dt>Discount</dt>
        <dd data-testid="cart-discount">{formatMoney(-cart.discountCents)}</dd>
        <dt>Tax (8%)</dt>
        <dd data-testid="cart-tax">{formatMoney(cart.taxCents)}</dd>
        <dt>Total</dt>
        <dd data-testid="cart-total">
          <strong>{formatMoney(cart.totalCents)}</strong>
        </dd>
      </dl>

      <button
        type="button"
        disabled={uiConfig.status !== 'ready'}
        onClick={() => void navigate('/checkout')}
        data-testid={checkoutTestId}
      >
        Proceed to checkout
      </button>
    </section>
  );
}
