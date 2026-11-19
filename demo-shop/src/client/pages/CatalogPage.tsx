import { Link } from 'react-router';
import { api, formatMoney } from '../api.ts';
import { useAsync } from '../useAsync.ts';

export function CatalogPage() {
  const state = useAsync(api.products, 'catalog');
  if (state.status === 'loading') return <p data-testid="catalog-loading">Loading products…</p>;
  if (state.status === 'error') {
    return (
      <p role="alert" className="error" data-testid="catalog-error">
        {state.message}
      </p>
    );
  }
  return (
    <section data-testid="catalog-page">
      <h1>Catalog</h1>
      <ul className="grid">
        {state.data.map((p) => (
          <li key={p.id} className="card" data-testid="product-card" data-product-slug={p.slug}>
            <h2 data-testid="product-name">{p.name}</h2>
            <p>{p.description}</p>
            <p className="price" data-testid="product-price">
              {formatMoney(p.priceCents)}
            </p>
            <Link to={`/products/${p.slug}`} data-testid="product-link">
              View {p.name}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
