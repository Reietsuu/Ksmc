import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';

/** Événement du cloud tel que publié par /api/v1/events (CloudEvent). */
export interface CloudEvent {
  id: string;
  type: string;
  subject?: string;
  task?: string;
  environment?: string;
  data?: unknown;
  timestamp: number;
  sequence?: number;
}

const KEEP = 50;

/** Requêtes à invalider pour un type d'événement (préfixe avant le premier point). */
export function invalidationsFor(type: string): string[][] {
  switch (type.split('.')[0]) {
    case 'service':
      return [['services'], ['tasks'], ['overview']];
    case 'task':
      return [['tasks'], ['overview']];
    case 'group':
      return [['groups']];
    case 'player':
      return [['players'], ['services'], ['overview']];
    case 'module':
      return [['modules'], ['ui-modules']];
    default:
      return [];
  }
}

export function applyEvent(qc: QueryClient, e: CloudEvent) {
  invalidationsFor(e.type).forEach((queryKey) => qc.invalidateQueries({ queryKey }));
}

interface StreamState {
  events: CloudEvent[];
  connected: boolean;
}

const Ctx = createContext<StreamState>({ events: [], connected: false });

/**
 * Connexion WebSocket unique au flux d'événements : garde les derniers événements et invalide les requêtes
 * TanStack concernées. Reconnexion avec attente croissante, reprise depuis la dernière séquence reçue.
 */
export function EventStreamProvider({ children }: { children: ReactNode }) {
  const { api, session, can, me } = useSession();
  const qc = useQueryClient();
  const [state, setState] = useState<StreamState>({ events: [], connected: false });
  const lastSeq = useRef<number | undefined>(undefined);
  const allowed = !!session && !!me && can(P.EVENT_READ);

  useEffect(() => {
    if (!allowed) return;
    let ws: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let delay = 1000;
    let stopped = false;
    const open = () => {
      const params: Record<string, string> = {};
      if (lastSeq.current) params.since = String(lastSeq.current + 1);
      ws = new WebSocket(api.wsUrl('/api/v1/events', params));
      ws.onopen = () => {
        delay = 1000;
        setState((s) => ({ ...s, connected: true }));
      };
      ws.onmessage = (m) => {
        let e: CloudEvent;
        try {
          e = JSON.parse(m.data as string);
        } catch {
          return;
        }
        if (e.sequence) lastSeq.current = e.sequence;
        applyEvent(qc, e);
        setState((s) => ({ ...s, events: [e, ...s.events].slice(0, KEEP) }));
      };
      ws.onclose = () => {
        setState((s) => ({ ...s, connected: false }));
        if (stopped) return;
        timer = setTimeout(async () => {
          await api.refresh();
          if (!stopped) open();
        }, delay);
        delay = Math.min(delay * 2, 30_000);
      };
    };
    open();
    return () => {
      stopped = true;
      clearTimeout(timer);
      ws?.close();
    };
  }, [allowed, api, qc]);

  return <Ctx.Provider value={state}>{children}</Ctx.Provider>;
}

export function useEvents(): StreamState {
  return useContext(Ctx);
}
