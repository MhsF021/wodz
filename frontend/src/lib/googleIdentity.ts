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
        callback?: (response: GoogleCredentialResponse) => void;
        login_uri?: string;
        nonce?: string;
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
          state?: string;
          click_listener?: () => void;
        },
      ): void;
      disableAutoSelect(): void;
      revoke(loginHint: string, callback: (response: { successful: boolean; error?: string }) => void): void;
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

/** Só recebe uma credencial que o servidor já confirmou. O payload não autoriza ações. */
export function revokeGoogleGrant(clientId: string, serverConfirmedCredential: string): Promise<boolean> {
  if (!clientId) return Promise.resolve(false);
  let subject: string;
  try {
    const parts = serverConfirmedCredential.split('.');
    if (parts.length !== 3 || !/^[A-Za-z0-9_-]+$/.test(parts[1])) return Promise.resolve(false);
    const encoded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const payload: unknown = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=')));
    const sub = (payload as { sub?: unknown } | null)?.sub;
    if (typeof sub !== 'string' || !sub || sub.length > 255) return Promise.resolve(false);
    subject = sub;
  } catch {
    return Promise.resolve(false);
  }

  return new Promise((resolve) => {
    let settled = false;
    const finish = (successful: boolean) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve(successful);
    };
    const timeout = setTimeout(() => finish(false), 7000);
    loadGoogleIdentity().then((google) => {
      // Um carregamento tardio não pode alterar o próximo login após o timeout.
      if (settled) return;
      try {
        google.accounts.id.initialize({ client_id: clientId, callback: () => {}, auto_select: false });
        google.accounts.id.disableAutoSelect();
        google.accounts.id.revoke(subject, (response) => finish(response.successful === true));
      } catch {
        finish(false);
      }
    }).catch(() => finish(false));
  });
}
