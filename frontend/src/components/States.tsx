import type { ReactNode } from 'react';
import { CircleAlert } from 'lucide-react';
import { errorMessage } from '../api/client';

export function Loading({ label = 'Carregando…' }: { label?: string }) {
  return (
    <div className="state state--loading" role="status" aria-live="polite">
      <span className="spinner" aria-hidden />
      {label}
    </div>
  );
}

export function ErrorState({ error, action }: { error: unknown; action?: ReactNode }) {
  return (
    <div className="state state--error" role="alert">
      <CircleAlert size={20} aria-hidden />
      <div>
        <p>{errorMessage(error)}</p>
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
      <CircleAlert size={16} aria-hidden /> {errorMessage(error)}
    </p>
  );
}
