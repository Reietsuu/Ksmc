import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './schema';

/** Jetons de session renvoyés par POST /api/v1/auth/login et /refresh. */
export interface Session {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  mustChangePassword: boolean;
}

/** Erreur uniforme de l'API ({"code","message","details"}). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

const STORAGE_KEY = 'ksmc.session';

type Listener = (s: Session | null) => void;

/**
 * Client HTTP du dashboard : ajoute le jeton d'accès, le renouvelle une fois sur 401 et convertit les erreurs.
 * La session est conservée dans localStorage pour survivre au rechargement.
 */
export class ApiClient {
  private session: Session | null;
  private refreshing: Promise<boolean> | null = null;
  private listeners = new Set<Listener>();

  constructor(private readonly base = '') {
    this.session = load();
  }

  current(): Session | null {
    return this.session;
  }

  onChange(l: Listener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  setSession(s: Session | null) {
    this.session = s;
    try {
      if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
      else localStorage.removeItem(STORAGE_KEY);
    } catch {
      // stockage indisponible : la session reste en mémoire
    }
    this.listeners.forEach((l) => l(s));
  }

  async login(username: string, password: string): Promise<Session> {
    const s = (await this.json('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
      auth: false,
    })) as Session;
    this.setSession(s);
    return s;
  }

  logout() {
    this.setSession(null);
  }

  /** fetch authentifié ; {@code path} est absolu (/api/v1/...). */
  async fetch(path: string, init: RequestInit & { auth?: boolean } = {}): Promise<Response> {
    const { auth = true, ...rest } = init;
    const send = () => {
      const headers = new Headers(rest.headers);
      if (rest.body && typeof rest.body === 'string' && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
      }
      if (auth && this.session) headers.set('Authorization', `Bearer ${this.session.accessToken}`);
      return fetch(this.base + path, { ...rest, headers });
    };
    let res = await send();
    if (res.status === 401 && auth && this.session && (await this.refresh())) {
      res = await send();
    }
    if (res.status === 401 && auth) this.setSession(null);
    return res;
  }

  /** Requête JSON ; lève {@link ApiError} si le statut n'est pas 2xx. */
  async json<T = unknown>(path: string, init: RequestInit & { auth?: boolean } = {}): Promise<T> {
    const res = await this.fetch(path, init);
    if (!res.ok) throw await toError(res);
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  /** URL WebSocket authentifiée (les navigateurs ne peuvent pas envoyer d'en-tête Authorization). */
  wsUrl(path: string, params: Record<string, string> = {}): string {
    const loc = window.location;
    const url = new URL(this.base + path, loc.href);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));
    if (this.session) url.searchParams.set('access_token', this.session.accessToken);
    return url.toString();
  }

  /** Renouvelle le jeton d'accès (une seule requête en vol). */
  refresh(): Promise<boolean> {
    if (!this.session) return Promise.resolve(false);
    if (!this.refreshing) {
      const refreshToken = this.session.refreshToken;
      this.refreshing = fetch(this.base + '/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
        .then(async (r) => {
          if (!r.ok) return false;
          this.setSession((await r.json()) as Session);
          return true;
        })
        .catch(() => false)
        .finally(() => {
          this.refreshing = null;
        });
    }
    return this.refreshing;
  }

  /** Client typé (openapi-fetch) généré depuis openapi.json, partageant la session et les erreurs. */
  typed() {
    const client = createClient<paths>({
      baseUrl: window.location.origin + this.base,
      fetch: async (req: Request) => {
        const url = new URL(req.url);
        const body = req.method === 'GET' || req.method === 'HEAD' ? undefined : await req.text();
        return this.fetch(url.pathname.slice(this.base.length) + url.search, {
          method: req.method,
          headers: req.headers,
          body: body || undefined,
        });
      },
    });
    const errors: Middleware = {
      async onResponse({ response }) {
        if (!response.ok) throw await toError(response.clone());
        return response;
      },
    };
    client.use(errors);
    return client;
  }
}

export async function toError(res: Response): Promise<ApiError> {
  try {
    const body = await res.json();
    return new ApiError(res.status, body.code ?? String(res.status), body.message ?? res.statusText, body.details);
  } catch {
    return new ApiError(res.status, String(res.status), res.statusText);
  }
}

function load(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export const api = new ApiClient();
