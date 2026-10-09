import { Check, CircleAlert, Clock3 } from 'lucide-react';

export function ActionFeedback({ status, label }: { status: 'success' | 'pending' | 'error'; label: string }) {
  const Icon = status === 'success' ? Check : status === 'pending' ? Clock3 : CircleAlert;
  return (
    <p className={`action-feedback action-feedback--${status}`} role="status" aria-live="polite">
      <Icon className="action-feedback__icon" size={16} strokeWidth={1.7} aria-hidden />
      <span>{label}</span>
    </p>
  );
}
