import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { me, mockApi, renderApp } from '../test/utils';
import PlayersPage from './PlayersPage';
import ModulesPage from './ModulesPage';
import ModuleUiPage, { loader } from './ModuleUiPage';
import AccountsPage from './AccountsPage';
import AuditPage from './AuditPage';
import SoftwarePage from './SoftwarePage';

const steve = { uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', name: 'Steve', proxy: 'proxy-1', server: 'lobby-1', connectedAt: 1 };

describe('joueurs', () => {
  it('expulse Steve', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/players': { items: [steve] },
      [`POST /api/v1/players/${steve.uuid}/kick`]: { result: 'ok' },
    });
    vi.spyOn(window, 'prompt').mockReturnValue('triche');
    renderApp(<PlayersPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Expulser Steve' }));
    await waitFor(() => expect(calls.some((c) => c.path.endsWith('/kick'))).toBe(true));
    expect(calls.find((c) => c.path.endsWith('/kick'))!.body).toEqual({ reason: 'triche' });
  });

  it('pas d’actions sans ksmc.player.action', async () => {
    mockApi({ 'GET /api/v1/auth/me': me(['ksmc.player.read']), 'GET /api/v1/players': { items: [steve] } });
    renderApp(<PlayersPage />);
    expect(await screen.findByText('Steve')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Expulser Steve' })).toBeNull();
  });
});

describe('modules', () => {
  it('affiche l’état et désactive', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/modules': { items: [{ metadata: { name: 'hello' }, spec: { source: {} }, status: { phase: 'Enabled', loadedVersion: '1.0.0' } }] },
      'PATCH /api/v1/modules/hello': (r: { body: unknown }) => r.body,
    });
    renderApp(<ModulesPage />);
    expect(await screen.findByText('Actif')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Désactiver' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true));
    expect(calls.find((c) => c.method === 'PATCH')!.body).toEqual({ spec: { enabled: false } });
  });

  it('charge la page du module et lui passe un fetch authentifié', async () => {
    const { calls, fetchMock } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/ui/modules': [{ module: 'hello', title: 'Hello', bundle: '/modules/hello/ui.js' }],
      'GET /api/v1/modules/hello/hello': { message: 'Bonjour depuis hello' },
    });
    const load = vi.spyOn(loader, 'load').mockResolvedValue({
      mount(el, api) {
        el.innerHTML = '<p id="msg">…</p>';
        void api.fetch('hello').then((r) => r.json()).then((d) => (el.querySelector('#msg')!.textContent = d.message));
      },
    });
    renderApp(
      <Routes>
        <Route path="/ui/:module" element={<ModuleUiPage />} />
      </Routes>,
      { route: '/ui/hello' },
    );
    expect(await screen.findByText('Bonjour depuis hello')).toBeInTheDocument();
    expect(load).toHaveBeenCalledWith('/modules/hello/ui.js');
    expect(calls.some((c) => c.path === '/api/v1/modules/hello/hello')).toBe(true);
    const call = fetchMock.mock.calls.find((c) => String(c[0]).endsWith('/modules/hello/hello'))!;
    expect(new Headers((call[1] as RequestInit).headers).get('Authorization')).toBe('Bearer a1');
  });
});

describe('logiciels', () => {
  it('liste et ouvre l’éditeur YAML', async () => {
    mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/software': { items: [{ metadata: { name: 'paper', resourceVersion: '1' }, spec: { environment: 'server', family: 'paper', resolver: { type: 'papermc' } } }] },
    });
    renderApp(<SoftwarePage />);
    await userEvent.click(await screen.findByRole('button', { name: 'paper' }));
    expect((screen.getByLabelText('editor') as HTMLTextAreaElement).value).toContain('family: paper');
  });
});

describe('utilisateurs et jetons', () => {
  it('crée un jeton affiché une seule fois', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me(['ksmc.task.read']),
      'GET /api/v1/auth/tokens': [],
      'POST /api/v1/auth/tokens': { id: 't1', token: 'ksmc_abc123', expiresAt: null },
    });
    renderApp(<AccountsPage />);
    await userEvent.type(await screen.findByLabelText('Nom du jeton'), 'ci');
    await userEvent.click(screen.getByRole('button', { name: 'Créer un jeton' }));
    expect(await screen.findByTestId('new-token')).toHaveTextContent('ksmc_abc123');
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ name: 'ci' });
    expect(screen.queryByText('Utilisateurs')).toBeNull();
  });

  it('administre les comptes', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/auth/tokens': [],
      'GET /api/v1/accounts': [{ name: 'admin', roles: ['admin'], mustChangePassword: false }],
      'GET /api/v1/roles': { admin: ['*'], operator: [], viewer: [] },
      'POST /api/v1/accounts': { name: 'alice' },
    });
    renderApp(<AccountsPage />);
    await userEvent.type(await screen.findByLabelText('Utilisateur'), 'alice');
    await userEvent.type(screen.getByLabelText('Mot de passe'), 'motdepasse1');
    await userEvent.selectOptions(screen.getByLabelText('Rôle'), 'operator');
    await userEvent.click(screen.getByRole('button', { name: "Créer l'utilisateur" }));
    await waitFor(() => expect(calls.some((c) => c.path === '/api/v1/accounts' && c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ name: 'alice', password: 'motdepasse1', roles: ['operator'] });
  });
});

describe('audit', () => {
  it('filtre par utilisateur', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/audit': { items: [{ time: '2026-10-20T10:00:00Z', user: 'admin', action: 'tasks.create', target: 'lobby', result: 'ok' }] },
    });
    renderApp(<AuditPage />);
    expect(await screen.findByText('tasks.create')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Utilisateur'), 'bob');
    await waitFor(() => expect(calls.some((c) => c.path === '/api/v1/audit' && c.method === 'GET')).toBe(true));
    const last = vi.mocked(fetch).mock.calls.map((c) => String(c[0])).filter((u) => u.includes('/audit')).pop()!;
    expect(new URL(last, 'http://x').searchParams.get('user')).toBe('bob');
  });
});
