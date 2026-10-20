import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api as defaultApi, type ApiClient, type Session } from '../api/client';
import { grants } from './permissions';

export interface Me {
  name: string;
  permissions: string[];
  mustChangePassword: boolean;
}

interface SessionState {
  api: ApiClient;
  session: Session | null;
  me: Me | undefined;
  loading: boolean;
  can: (permission: string | undefined) => boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<SessionState | null>(null);

/** Session courante : jetons, utilisateur (GET /auth/me) et contrôle des permissions. */
export function SessionProvider({ api = defaultApi, children }: { api?: ApiClient; children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(api.current());
  const qc = useQueryClient();
  useEffect(() => api.onChange(setSession), [api]);

  const me = useQuery({
    queryKey: ['me', session?.accessToken ? 'auth' : 'anon'],
    queryFn: () => api.json<Me>('/api/v1/auth/me'),
    enabled: !!session,
    staleTime: 60_000,
  });

  const login = useCallback(
    async (username: string, password: string) => {
      await api.login(username, password);
      await qc.invalidateQueries();
    },
    [api, qc],
  );
  const logout = useCallback(() => {
    api.logout();
    qc.clear();
  }, [api, qc]);

  const value = useMemo<SessionState>(() => {
    const perms = new Set(me.data?.permissions ?? []);
    return {
      api,
      session,
      me: session ? me.data : undefined,
      loading: !!session && me.isLoading,
      can: (p) => grants(perms, p),
      login,
      logout,
    };
  }, [api, session, me.data, me.isLoading, login, logout]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): SessionState {
  const v = useContext(Ctx);
  if (!v) throw new Error('SessionProvider manquant');
  return v;
}

export function useApi(): ApiClient {
  return useSession().api;
}

/** Affiche les enfants seulement si l'utilisateur a la permission (sinon {@code fallback}). */
export function Can({ permission, children, fallback = null }: { permission: string; children: ReactNode; fallback?: ReactNode }) {
  return <>{useSession().can(permission) ? children : fallback}</>;
}
