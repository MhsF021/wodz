import { Children, cloneElement, isValidElement, type ChangeEvent, type ReactNode } from 'react';

type TextControl = HTMLInputElement | HTMLTextAreaElement;
type TextControlProps = {
  value?: unknown;
  defaultValue?: unknown;
  autoCapitalize?: string;
  onChange?: (event: ChangeEvent<TextControl>) => void;
};

/** Converte o valor real, incluindo colagem, sem deslocar o cursor ao editar no meio do texto. */
function uppercaseControl(child: ReactNode) {
  if (!isValidElement<TextControlProps>(child) || (child.type !== 'input' && child.type !== 'textarea')) return child;
  const { value, defaultValue, onChange } = child.props;
  return cloneElement(child, {
    autoCapitalize: 'characters',
    ...(typeof value === 'string' ? { value: value.toUpperCase() } : {}),
    ...(typeof defaultValue === 'string' ? { defaultValue: defaultValue.toUpperCase() } : {}),
    onChange(event: ChangeEvent<TextControl>) {
      const input = event.target;
      const original = input.value;
      const normalized = original.toUpperCase();
      if (normalized !== original) {
        const start = input.selectionStart;
        const end = input.selectionEnd;
        input.value = normalized;
        if (start !== null && end !== null) {
          input.setSelectionRange(original.slice(0, start).toUpperCase().length, original.slice(0, end).toUpperCase().length);
        }
      }
      onChange?.(event);
    },
  });
}

/** Rótulo visível + dica + erro junto do campo. */
export function Field({
  label,
  htmlFor,
  hint,
  error,
  children,
  className,
  uppercase = false,
}: {
  label: string;
  htmlFor: string;
  hint?: string;
  error?: string | null;
  children: ReactNode;
  className?: string;
  /** Apenas nomes e descrições: nunca use em e-mail, senha, URL ou identificadores. */
  uppercase?: boolean;
}) {
  return (
    <div className={`field${error ? ' field--invalid' : ''}${className ? ` ${className}` : ''}`}>
      <label className="field__label" htmlFor={htmlFor}>
        {label}
      </label>
      {uppercase ? Children.map(children, uppercaseControl) : children}
      {hint && !error && <p className="field__hint">{hint}</p>}
      {error && (
        <p className="field__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
