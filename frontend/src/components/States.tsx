import type { ReactNode } from 'react';
import { CircleAlert } from 'lucide-react';
import { errorMessage } from '../api/client';
import { Link } from 'react-router';
import { useActivity } from '../lib/useActivity';

function ErrorMessage({ error }: { error: unknown }) {
  const message = errorMessage(error);
  if (/conecte sua conta mercado pago/i.test(message) && /minha conta/i.test(message)) {
    const [before, after] = message.split(/minha conta/i);
    return <>{before}<Link to="/minha-conta#recebimentos">Minha Conta</Link>{after}</>;
  }
  return <>{message}</>;
}

export function Loading({ label = 'Carregando…' }: { label?: string }) {
  useActivity(true);
  return (
    <div className="state state--loading" role="status" aria-label={label}>
      <span className="spinner" aria-hidden />
    </div>
  );
}

export function ErrorState({ error, action }: { error: unknown; action?: ReactNode }) {
  return (
    <div className="state state--error" role="alert">
      <CircleAlert size={20} aria-hidden />
      <div>
        <p><ErrorMessage error={error} /></p>
        {action}
      </div>
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="state state--empty">
      <p className="state__title">{title}</p>
      {children}
    </div>
  );
}

export function InlineError({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <p className="form-error" role="alert">
      <CircleAlert size={16} aria-hidden /> <ErrorMessage error={error} />
    </p>
  );
}
