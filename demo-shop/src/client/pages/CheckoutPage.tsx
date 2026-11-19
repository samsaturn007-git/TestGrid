import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, formatMoney } from '../api.ts';
import { useCart } from '../CartContext.tsx';

export function CheckoutPage() {
  const { cart, refresh } = useCart();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!cart) return <p data-testid="checkout-loading">Loading…</p>;
  if (cart.lines.length === 0) {
    return (
      <p data-testid="checkout-empty">
        Nothing to check out. <Link to="/">Browse the catalog</Link>
      </p>
    );
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const field = (name: string) => {
      const value = form.get(name);
      return typeof value === 'string' ? value : '';
    };
    setSubmitting(true);
    setError(null);
    try {
      const { orderNumber } = await api.placeOrder({
        name: field('name'),
        email: field('email'),
        address: field('address'),
        cardNumber: field('cardNumber').replace(/[\s-]/g, ''),
      });
      await refresh();
      void navigate(`/orders/${orderNumber}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Payment failed');
      setSubmitting(false);
    }
  }

  return (
    <section data-testid="checkout-page">
      <h1>Checkout</h1>
      <p>
        Order total: <span data-testid="checkout-total">{formatMoney(cart.totalCents)}</span>
      </p>
      <p>This shop is a test fixture. Payment is simulated and no card data leaves the page.</p>
      <form className="checkout" onSubmit={(e) => void submit(e)}>
        <label>
          Full name
          <input
            name="name"
            required
            maxLength={100}
            autoComplete="off"
            data-testid="checkout-name"
          />
        </label>
        <label>
          Email
          <input
            name="email"
            type="email"
            required
            maxLength={200}
            autoComplete="off"
            data-testid="checkout-email"
          />
        </label>
        <label>
          Shipping address
          <input
            name="address"
            required
            maxLength={200}
            autoComplete="off"
            data-testid="checkout-address"
          />
        </label>
        <label>
          Card number (fake)
          <input
            name="cardNumber"
            inputMode="numeric"
            required
            pattern="[0-9 \-]{12,23}"
            autoComplete="off"
            data-testid="checkout-card-number"
          />
        </label>
        <button type="submit" disabled={submitting} data-testid="place-order-button">
          {submitting ? 'Placing order…' : 'Place order'}
        </button>
        {error && (
          <p role="alert" className="error" data-testid="payment-error">
            {error}
          </p>
        )}
      </form>
    </section>
  );
}
