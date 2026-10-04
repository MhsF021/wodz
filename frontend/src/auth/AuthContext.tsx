import {
  createContext,
  startTransition,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '../api/endpoints';
import { LOGOUT_EVENT, tokenStore } from '../api/client';
import type { PendingGoogleSignup, RegisterInput, Role, UpdateProfileInput, User } from '../api/types';

/** Resultado do login com Google: entrou, ou é o primeiro acesso e falta escolher o perfil. */
export type GoogleLoginOutcome = { user: User; pending?: undefined } | { user?: undefined; pending: PendingGoogleSignup };

interface AuthContextValue {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<User>;
  signup: (input: RegisterInput) => Promise<User>;
  /** role só é usado no primeiro acesso, quando a conta ainda não existe. */
  loginWithGoogle: (credential: string, role?: Role) => Promise<GoogleLoginOutcome>;
  /** Recarrega o usuário da API (ex.: depois de criar a primeira senha). */
  refreshUser: () => Promise<void>;
  updateProfile: (input: UpdateProfileInput) => Promise<User>;
  /** Encerra a sessão e volta para a página inicial. */
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(() => Boolean(tokenStore.get()));

  useEffect(() => {
    if (!tokenStore.get()) return;
    authApi
      .me()
      .then(setUser)
      .catch(() => tokenStore.clear())
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const onLogout = () => setUser(null);
    window.addEventListener(LOGOUT_EVENT, onLogout);
    return () => window.removeEventListener(LOGOUT_EVENT, onLogout);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login(email, password);
    tokenStore.set(res.token);
    setUser(res.user);
    return res.user;
  }, []);

  const signup = useCallback(async (input: RegisterInput) => {
    const res = await authApi.register(input);
    tokenStore.set(res.token);
    setUser(res.user);
    return res.user;
  }, []);

  const loginWithGoogle = useCallback(async (credential: string, role?: Role): Promise<GoogleLoginOutcome> => {
    const res = await authApi.google(credential, role);
    if (res.auth) {
      tokenStore.set(res.auth.token);
      setUser(res.auth.user);
      return { user: res.auth.user };
    }
    return { pending: res.pending! };
  }, []);

  const refreshUser = useCallback(async () => {
    setUser(await authApi.me());
  }, []);

  const updateProfile = useCallback(async (input: UpdateProfileInput) => {
    const updated = await authApi.updateMe(input);
    setUser(updated);
    return updated;
  }, []);

  const logout = useCallback(() => {
    tokenStore.clear();
    window.google?.accounts.id.disableAutoSelect();
    // O React Router troca a rota dentro de uma transição. Limpar o usuário na mesma
    // transição evita um render com usuário nulo na página protegida, em que o
    // RequireRole mandaria para /entrar em vez da página inicial.
    startTransition(() => {
      setUser(null);
      navigate('/', { replace: true });
    });
  }, [navigate]);

  // Sessão encerrada (Sair, conta excluída ou expirada): descarta tudo que foi
  // carregado com a conta anterior, para nada dela aparecer na próxima.
  const previousUserId = useRef<number | null>(null);
  useEffect(() => {
    const id = user?.id ?? null;
    if (previousUserId.current !== null && previousUserId.current !== id) queryClient.clear();
    previousUserId.current = id;
  }, [user, queryClient]);

  const value = useMemo(
    () => ({ user, loading, login, signup, loginWithGoogle, refreshUser, updateProfile, logout }),
    [user, loading, login, signup, loginWithGoogle, refreshUser, updateProfile, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth precisa estar dentro de <AuthProvider>');
  return ctx;
}
