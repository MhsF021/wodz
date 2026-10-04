import { useEffect, useRef, useState } from 'react';
import { loadGoogleIdentity, type GoogleButtonText } from '../lib/googleIdentity';

/** Botão oficial do Google; chama onCredential com o ID token depois do login no popup. */
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
        // O Google aceita de 200 a 400 px de largura.
        const width = Math.max(200, Math.min(400, Math.floor(slot.current.clientWidth)));
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
  return <div ref={slot} className="google-button" />;
}
