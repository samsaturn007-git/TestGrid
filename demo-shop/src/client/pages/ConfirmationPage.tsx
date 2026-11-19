import { useParams } from 'react-router';
import { api, formatMoney } from '../api.ts';
import { useAsync } from '../useAsync.ts';

export function ConfirmationPage() {
  const { orderNumber = '' } = useParams();
  const state = useAsync(() => api.order(orderNumber), orderNumber);

  if (state.status === 'loading') {
    return <p data-testid="order-loading">Confirming your order…</p>;
  }
  if (state.status === 'error') {
    return (
      <p role="alert" className="error" data-testid="order-error">
        {state.message}
      </p>
    );
  }
  return (
    <section role="status" className="success" data-testid="order-confirmation-banner">
      <h1>Thank you!</h1>
      <p>
        Order number <strong data-testid="order-number">{state.data.orderNumber}</strong>
      </p>
      <p>
        Charged {formatMoney(state.data.totalCents)} to the card ending in {state.data.cardLast4}.
      </p>
    </section>
  );
}
