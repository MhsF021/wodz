import { useEffect, useRef, useState } from 'react';
import { loadGoogleIdentity, type GoogleButtonText } from '../lib/googleIdentity';

const LABEL: Record<GoogleButtonText, string> = {
  signin_with: 'Entrar com Google',
  signup_with: 'Cadastrar com Google',
  continue_with: 'Continuar com Google',
};

/**
 * Botão do Google com o visual do site (mesmo padrão dos campos). O botão oficial fica
 * invisível por cima e esticado até cobrir tudo: é ele que recebe o clique e devolve o ID token.
 */
export function GoogleButton({
  clientId,
  text = 'signin_with',
  onCredential,
}: {
  clientId: string;
  text?: GoogleButtonText;
  onCredential: (credential: string) => void;
}) {
  const slot = useRef<HTMLDivElement>(null);
  const callback = useRef(onCredential);
  callback.current = onCredential;
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadGoogleIdentity()
      .then((google) => {
        if (cancelled || !slot.current) return;
        google.accounts.id.initialize({
          client_id: clientId,
          callback: (response) => callback.current(response.credential),
          ux_mode: 'popup',
          auto_select: false,
          cancel_on_tap_outside: true,
          context: text === 'signup_with' ? 'signup' : 'signin',
        });
        // O Google aceita de 200 a 400 px de largura; o resto é coberto com scale.
        const full = slot.current.clientWidth;
        const width = Math.max(200, Math.min(400, Math.floor(full)));
        google.accounts.id.renderButton(slot.current, {
          type: 'standard',
          theme: 'filled_black',
          size: 'large',
          shape: 'rectangular',
          text,
          width,
          locale: 'pt-BR',
          logo_alignment: 'left',
        });
        slot.current.style.transform = `scale(${full / width}, ${slot.current.clientHeight / 40})`;
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [clientId, text]);

  if (error) {
    return <p className="note">{error}. Use e-mail e senha ou tente de novo mais tarde.</p>;
  }
  return (
    <div className="google-button">
      <span className="google-button__face" aria-hidden="true">
        <svg viewBox="0 0 48 48" width="18" height="18">
          <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
          <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
          <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
          <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
        </svg>
        {LABEL[text]}
      </span>
      <div ref={slot} className="google-button__hit" />
    </div>
  );
}
