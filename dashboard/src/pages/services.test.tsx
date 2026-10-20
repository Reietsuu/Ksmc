import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { FakeWebSocket, me, mockApi, renderApp } from '../test/utils';
import ServicesPage, { filterServices } from './ServicesPage';
import ServicePage from './ServicePage';

const svc = (name: string, task: string, phase: string) => ({ metadata: { name }, spec: { task }, status: { phase, onlinePlayers: 1, maxPlayers: 20 } });
const items = [svc('lobby-1', 'lobby', 'Running'), svc('lobby-2', 'lobby', 'Starting'), svc('proxy-1', 'proxy', 'Running')];

beforeEach(() => FakeWebSocket.reset());

function renderService(perms: string[]) {
  mockApi({
    'GET /api/v1/auth/me': me(perms),
    'GET /api/v1/services/lobby-1': { ...svc('lobby-1', 'lobby', 'Running'), spec: { task: 'lobby', desiredState: 'Running' } },
    'POST /api/v1/services/lobby-1/stop': {},
  });
  return renderApp(
    <Routes>
      <Route path="/services/:name" element={<ServicePage />} />
    </Routes>,
    { route: '/services/lobby-1' },
  );
}

describe('services', () => {
  it('filtre par task, phase et nom', () => {
    expect(filterServices(items as never, { task: 'lobby' }).map((s) => s.metadata.name)).toEqual(['lobby-1', 'lobby-2']);
    expect(filterServices(items as never, { phase: 'Running' })).toHaveLength(2);
    expect(filterServices(items as never, { q: 'PROXY' })).toHaveLength(1);
  });

  it('liste avec filtre de task', async () => {
    mockApi({ 'GET /api/v1/auth/me': me(), 'GET /api/v1/services': { items } });
    renderApp(<ServicesPage />);
    expect(await screen.findByRole('link', { name: 'proxy-1' })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Task'), 'lobby');
    expect(screen.queryByRole('link', { name: 'proxy-1' })).toBeNull();
    expect(screen.getByRole('link', { name: 'lobby-2' })).toBeInTheDocument();
  });

  it('console : logs en direct et commande', async () => {
    renderService(['*']);
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0));
    const ws = FakeWebSocket.instances.find((w) => w.url.includes('/console'))!;
    expect(new URL(ws.url).pathname).toBe('/api/v1/services/lobby-1/console');
    expect(new URL(ws.url).searchParams.get('access_token')).toBe('a1');
    act(() => ws.emit({ type: 'log', line: '[Server] Done (3.2s)!' }));
    expect(await screen.findByTestId('xterm')).toHaveTextContent('Done (3.2s)!');
    const input = screen.getByLabelText('Commande');
    await waitFor(() => expect(input).toBeEnabled());
    await userEvent.type(input, 'list{Enter}');
    expect(ws.sent).toEqual(['list']);
    act(() => ws.emit({ type: 'command', command: 'list', result: 'There are 0 of a max of 20 players online' }));
    expect(screen.getByTestId('xterm')).toHaveTextContent('There are 0 of a max of 20 players online');
  });

  it('arrête le service', async () => {
    renderService(['*']);
    await userEvent.click(await screen.findByRole('button', { name: 'Arrêter' }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some((c) => String(c[0]).endsWith('/stop'))).toBe(true));
  });

  it('viewer : ni arrêt ni saisie de console', async () => {
    renderService(['ksmc.service.read', 'ksmc.service.console']);
    expect(await screen.findByRole('button', { name: 'Arrêter' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Supprimer' })).toBeDisabled();
    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0));
    expect(screen.getByLabelText('Commande')).toBeDisabled();
  });
});
