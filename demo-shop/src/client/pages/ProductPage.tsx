import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { api, formatMoney } from '../api.ts';
import { useCart } from '../CartContext.tsx';
import { useAsync } from '../useAsync.ts';

export function ProductPage() {
  const { slug = '' } = useParams();
  const state = useAsync(() => api.product(slug), slug);
  const { setCart } = useCart();
  const [adding, setAdding] = useState(false);
  const [added, setAdded] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (state.status === 'loading') return <p data-testid="product-loading">Loading…</p>;
  if (state.status === 'error') {
    return (
      <p role="alert" className="error" data-testid="product-error">
        {state.message}
      </p>
    );
  }
  const product = state.data;

  async function add() {
    setAdding(true);
    setError(null);
    try {
      setCart(await api.addItem(product.id, 1));
      setAdded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not add to cart');
    } finally {
      setAdding(false);
    }
  }

  return (
    <article className="card" data-testid="product-detail">
      <h1 data-testid="product-detail-name">{product.name}</h1>
      <p>{product.description}</p>
      <p className="price" data-testid="product-detail-price">
        {formatMoney(product.priceCents)}
      </p>
      <button
        type="button"
        onClick={() => void add()}
        disabled={adding}
        data-testid="add-to-cart-button"
      >
        Add to cart
      </button>
      {added && (
        <p role="status" data-testid="added-to-cart">
          Added to cart. <Link to="/cart">View cart</Link>
        </p>
      )}
      {error && (
        <p role="alert" className="error" data-testid="add-to-cart-error">
          {error}
        </p>
      )}
    </article>
  );
}
