import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  Fragment,
  useState,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '../api/endpoints';
import { LOGOUT_EVENT, tokenStore, revokeRemoteSession } from '../api/client';
import { clearGoogleRedirect } from '../lib/googleRedirect';
import type { Gender, PendingGoogleSignup, RegisterInput, Role, UpdateProfileInput, User } from '../api/types';

/** Resultado do login com Google: entrou, ou é o primeiro acesso e falta escolher o perfil. */
export type GoogleLoginOutcome = { user: User; pending?: undefined } | { user?: undefined; pending: PendingGoogleSignup };

interface AuthContextValue {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<User>;
  signup: (input: RegisterInput) => Promise<User>;
  /** role só é usado no primeiro acesso, quando a conta ainda não existe. */
  loginWithGoogle: (credential: string, role?: Role, gender?: Gender) => Promise<GoogleLoginOutcome>;
  /** Recarrega o usuário da API (ex.: depois de criar a primeira senha). */
  refreshUser: () => Promise<void>;
  updateProfile: (input: UpdateProfileInput) => Promise<User>;
  /** Encerra a sessão e volta para a página inicial. */
  logout: (destination?: string) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(() => Boolean(tokenStore.get()));
  const [sessionEpoch, setSessionEpoch] = useState(0);

  const discardSessionData = useCallback(() => {
    // Cancela antes de remover: nenhuma consulta da conta anterior pode repovoar
    // o cache enquanto a troca de sessão ainda está sendo renderizada.
    void queryClient.cancelQueries();
    queryClient.clear();
    clearGoogleRedirect();
    setSessionEpoch(epoch => epoch + 1);
  }, [queryClient]);

  const adoptSession = useCallback((token: string, nextUser: User) => {
    tokenStore.set(token);
    discardSessionData();
    setUser(nextUser);
    setLoading(false);
  }, [discardSessionData]);

  useEffect(() => {
    const initialToken = tokenStore.get();
    if (!initialToken) return;
    let cancelled = false;
    authApi
      .me()
      .then((nextUser) => {
        if (!cancelled && tokenStore.get() === initialToken) setUser(nextUser);
      })
      .catch(() => {
        if (!cancelled && tokenStore.get() === initialToken) {
          tokenStore.clear();
          discardSessionData();
          setUser(null);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [discardSessionData]);

  useEffect(() => {
    const onLogout = () => {
      tokenStore.clear();
      discardSessionData();
      setUser(null);
      setLoading(false);
      navigate('/', { replace: true });
    };
    window.addEventListener(LOGOUT_EVENT, onLogout);
    return () => window.removeEventListener(LOGOUT_EVENT, onLogout);
  }, [discardSessionData, navigate]);

  useEffect(() => {
    // Outras abas não recebem tokens: somente uma notificação efêmera de saída.
    const channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('wodz.session');
    if (channel) channel.onmessage = event => {
      if (event.data?.type === 'logout' && event.data.userId === user?.id) {
        window.dispatchEvent(new Event(LOGOUT_EVENT));
      }
    };
    const onRestore = (event: PageTransitionEvent) => {
      if (!event.persisted) { document.documentElement.removeAttribute('data-session-suspended'); return; }
      const version = tokenStore.version();
      setLoading(true);
      void authApi.me().then(next => {
        if (version === tokenStore.version() && tokenStore.get()) setUser(next);
      }).catch(() => {
        if (version === tokenStore.version()) window.dispatchEvent(new Event(LOGOUT_EVENT));
      }).finally(() => {
        setLoading(false);
        document.documentElement.removeAttribute('data-session-suspended');
      });
    };
    const onPageHide = () => { document.documentElement.setAttribute('data-session-suspended', 'true'); };
    window.addEventListener('pageshow', onRestore);
    window.addEventListener('pagehide', onPageHide);
    return () => { channel?.close(); window.removeEventListener('pageshow', onRestore); window.removeEventListener('pagehide', onPageHide); };
  }, [user?.id]);

  const login = useCallback(async (email: string, password: string) => {
    const version = tokenStore.version();
    const res = await authApi.login(email, password);
    if (version !== tokenStore.version()) throw new Error('Esta tentativa de login foi encerrada. Entre novamente.');
    adoptSession(res.token, res.user);
    return res.user;
  }, [adoptSession]);

  const signup = useCallback(async (input: RegisterInput) => {
    const version = tokenStore.version();
    const res = await authApi.register(input);
    if (version !== tokenStore.version()) throw new Error('Esta tentativa foi encerrada. Entre novamente.');
    adoptSession(res.token, res.user);
    return res.user;
  }, [adoptSession]);

  const loginWithGoogle = useCallback(async (credential: string, role?: Role, gender?: Gender): Promise<GoogleLoginOutcome> => {
    const version = tokenStore.version();
    const res = await authApi.google(credential, role, gender);
    if (version !== tokenStore.version()) throw new Error('Esta tentativa de login foi encerrada. Entre novamente.');
    if (res.auth) {
      adoptSession(res.auth.token, res.auth.user);
      return { user: res.auth.user };
    }
    return { pending: res.pending! };
  }, [adoptSession]);

  const refreshUser = useCallback(async () => {
    const version = tokenStore.version();
    const next = await authApi.me();
    if (version === tokenStore.version() && tokenStore.get()) setUser(next);
  }, []);

  const updateProfile = useCallback(async (input: UpdateProfileInput) => {
    const version = tokenStore.version();
    const updated = await authApi.updateMe(input);
    if (version !== tokenStore.version() || !tokenStore.get()) throw new Error('A sessão foi encerrada.');
    setUser(updated);
    return updated;
  }, []);

  const logout = useCallback((destination = '/') => {
    const revocation = revokeRemoteSession();
    if (typeof BroadcastChannel !== 'undefined') {
      const channel = new BroadcastChannel('wodz.session');
      channel.postMessage({ type: 'logout', userId: user?.id });
      channel.close();
    }
    tokenStore.clear();
    const version = tokenStore.version();
    discardSessionData();
    setLoading(false);
    try { window.google?.accounts.id.disableAutoSelect(); } catch { /* A saída local não depende do Google. */ }
    // A identidade é removida imediatamente, inclusive se a próxima rota suspender.
    setUser(null);
    navigate(destination, { replace: true });
    void revocation.then(confirmed => {
      if (!confirmed && version === tokenStore.version()) navigate('/entrar?saida=local', { replace: true });
    });
  }, [navigate, discardSessionData, user?.id]);

  const value = useMemo(
    () => ({ user, loading, login, signup, loginWithGoogle, refreshUser, updateProfile, logout }),
    [user, loading, login, signup, loginWithGoogle, refreshUser, updateProfile, logout],
  );
  return <AuthContext.Provider value={value}><Fragment key={sessionEpoch}>{children}</Fragment></AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth precisa estar dentro de <AuthProvider>');
  return ctx;
}
