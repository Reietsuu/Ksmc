import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { vi } from 'vitest';
import { ApiClient, type Session } from '../api/client';
import { Providers } from '../App';

type Handler = (req: { method: string; path: string; body: unknown; url: URL }) => unknown | Response;

/**
 * Faux serveur : {@code "GET /api/v1/tasks": handler}. Une valeur est renvoyée en JSON (200),
 * une Response telle quelle ; route absente : 404.
 */
export function mockApi(routes: Record<string, Handler | unknown>) {
  const calls: { method: string; path: string; body: unknown }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    let body: unknown = init?.body;
    if (typeof body === 'string') {
      try {
        body = JSON.parse(body);
      } catch {
        // texte brut
      }
    }
    calls.push({ method, path: url.pathname, body });
    const key = `${method} ${url.pathname}`;
    const route = routes[key];
    if (route === undefined) {
      return new Response(JSON.stringify({ code: 'NotFound', message: key }), { status: 404 });
    }
    const out = typeof route === 'function' ? (route as Handler)({ method, path: url.pathname, body, url }) : route;
    if (out instanceof Response) return out;
    return new Response(out === undefined ? '' : JSON.stringify(out), {
      status: out === undefined ? 204 : 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}

export const SESSION: Session = { accessToken: 'a1', refreshToken: 'r1', expiresIn: 900, mustChangePassword: false };

export function me(permissions: string[] = ['*'], name = 'admin') {
  return { name, permissions, mustChangePassword: false };
}

/** WebSocket factice : les tests poussent des messages avec {@code FakeWebSocket.last().emit(obj)}. */
export class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((m: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  sent: string[] = [];
  readyState = 0;
  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this);
    setTimeout(() => {
      this.readyState = 1;
      this.onopen?.();
    }, 0);
  }
  static last() {
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
  }
  static reset() {
    FakeWebSocket.instances = [];
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.readyState = 3;
  }
  emit(data: unknown) {
    this.onmessage?.({ data: typeof data === 'string' ? data : JSON.stringify(data) });
  }
}

export function renderApp(ui: ReactElement, { route = '/', session = SESSION as Session | null } = {}) {
  vi.stubGlobal('WebSocket', FakeWebSocket);
  const api = new ApiClient();
  if (session) api.setSession(session);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const result = render(
    <Providers api={api} client={client}>
      <MemoryRouter initialEntries={[route]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{ui}</MemoryRouter>
    </Providers>,
  );
  return { ...result, api, client };
}
