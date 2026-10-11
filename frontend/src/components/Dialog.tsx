import { useId, useLayoutEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

export function Dialog({ title, subtitle, onClose, children }: {
  title: string; subtitle?: string; onClose: () => void; children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const subtitleId = useId();
  const close = useRef(onClose);
  close.current = onClose;

  useLayoutEffect(() => {
    const element = dialog.current;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    element?.showModal();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      element?.close();
      document.body.style.overflow = previousOverflow;
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, []);

  return createPortal(
    <dialog ref={dialog} className="details-dialog" aria-labelledby={titleId} aria-describedby={subtitle ? subtitleId : undefined}
      onCancel={(event) => { event.preventDefault(); close.current(); }}
      onClick={(event) => { if (event.target === event.currentTarget) close.current(); }}>
      <div className="details-dialog__panel">
        <header className="details-dialog__header">
          <div><h2 id={titleId}>{title}</h2>{subtitle && <p id={subtitleId}>{subtitle}</p>}</div>
          <button type="button" className="details-dialog__close" aria-label="Fechar detalhes da equipe" onClick={onClose}><X size={20} aria-hidden /></button>
        </header>
        <div className="details-dialog__body">{children}</div>
      </div>
    </dialog>, document.body,
  );
}
