/**
 * Google Identity Services (botão "Entrar com Google"). O script oficial é
 * carregado sob demanda; o botão entrega um ID token (credential) que a API
 * valida em POST /api/auth/google.
 */

export interface GoogleCredentialResponse {
  credential: string;
}

export type GoogleButtonText = 'signin_with' | 'signup_with' | 'continue_with';

interface GoogleIdentity {
  accounts: {
    id: {
      initialize(config: {
        client_id: string;
        callback: (response: GoogleCredentialResponse) => void;
        ux_mode?: 'popup' | 'redirect';
        auto_select?: boolean;
        cancel_on_tap_outside?: boolean;
        context?: 'signin' | 'signup' | 'use';
      }): void;
      renderButton(
        parent: HTMLElement,
        options: {
          type?: 'standard' | 'icon';
          theme?: 'outline' | 'filled_blue' | 'filled_black';
          size?: 'large' | 'medium' | 'small';
          text?: GoogleButtonText;
          shape?: 'rectangular' | 'pill' | 'circle' | 'square';
          logo_alignment?: 'left' | 'center';
          width?: number;
          locale?: string;
        },
      ): void;
      disableAutoSelect(): void;
    };
  };
}

declare global {
  interface Window {
    google?: GoogleIdentity;
  }
}

let scriptPromise: Promise<GoogleIdentity> | null = null;

export function loadGoogleIdentity(): Promise<GoogleIdentity> {
  if (window.google?.accounts?.id) return Promise.resolve(window.google);
  if (!scriptPromise) {
    scriptPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://accounts.google.com/gsi/client';
      script.async = true;
      script.onload = () => (window.google ? resolve(window.google) : reject(new Error('Google indisponível')));
      script.onerror = () => {
        scriptPromise = null;
        reject(new Error('Não foi possível carregar o login com Google'));
      };
      document.head.appendChild(script);
    });
  }
  return scriptPromise;
}
