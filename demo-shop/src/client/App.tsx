import { Link, Route, Routes } from 'react-router';
import { CartProvider, useCart } from './CartContext.tsx';
import { CartPage } from './pages/CartPage.tsx';
import { CatalogPage } from './pages/CatalogPage.tsx';
import { CheckoutPage } from './pages/CheckoutPage.tsx';
import { ConfirmationPage } from './pages/ConfirmationPage.tsx';
import { ProductPage } from './pages/ProductPage.tsx';

function Header() {
  const { cart } = useCart();
  const count = cart?.lines.reduce((n, l) => n + l.quantity, 0) ?? 0;
  return (
    <header>
      <Link to="/" className="brand" data-testid="home-link">
        Demo Shop
      </Link>
      <Link to="/cart" data-testid="cart-link">
        Cart (<span data-testid="cart-count">{count}</span>)
      </Link>
    </header>
  );
}

export function App() {
  return (
    <CartProvider>
      <Header />
      <main>
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/products/:slug" element={<ProductPage />} />
          <Route path="/cart" element={<CartPage />} />
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/orders/:orderNumber" element={<ConfirmationPage />} />
          <Route path="*" element={<p data-testid="not-found">Page not found.</p>} />
        </Routes>
      </main>
    </CartProvider>
  );
}
