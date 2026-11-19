import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { api } from './api.ts';
import type { Cart } from './api.ts';

interface CartContextValue {
  cart: Cart | null;
  setCart: (cart: Cart) => void;
  refresh: () => Promise<void>;
}

const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const [cart, setCart] = useState<Cart | null>(null);
  const refresh = useCallback(async () => {
    setCart(await api.cart());
  }, []);
  useEffect(() => {
    let active = true;
    api.cart().then(
      (c) => {
        if (active) setCart(c);
      },
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, []);
  return <CartContext value={{ cart, setCart, refresh }}>{children}</CartContext>;
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) throw new Error('useCart outside CartProvider');
  return value;
}
