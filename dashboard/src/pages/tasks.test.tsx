import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { FakeWebSocket, me, mockApi, renderApp } from '../test/utils';
import OverviewPage from './OverviewPage';
import TasksPage from './TasksPage';
import TaskPage from './TaskPage';
import GroupsPage from './GroupsPage';

const lobby = {
  apiVersion: 'ksmc.io/v1alpha1',
  kind: 'ServiceTask',
  metadata: { name: 'lobby', resourceVersion: '12' },
  spec: { environment: 'server', software: { name: 'paper', version: 'latest' }, memoryMb: 1024, minServiceCount: 2, fallback: { enabled: true } },
  status: { readyServices: 2, totalServices: 2, onlinePlayers: 5 },
};
const service = (name: string, phase: string, players = 0) => ({ metadata: { name }, spec: { task: 'lobby' }, status: { phase, onlinePlayers: players } });

beforeEach(() => FakeWebSocket.reset());

describe('vue d’ensemble', () => {
  it('affiche joueurs et phases et se met à jour sur événement', async () => {
    let services = [service('lobby-1', 'Running', 3), service('lobby-2', 'Starting')];
    mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/tasks': { items: [lobby] },
      'GET /api/v1/services': () => ({ items: services }),
    });
    renderApp(<OverviewPage />);
    await waitFor(() => expect(screen.getByTestId('players-total')).toHaveTextContent('3'));
    expect(screen.getByTestId('phase-Starting')).toHaveTextContent('1');
    await waitFor(() => expect(FakeWebSocket.instances.length).toBe(1));
    services = [service('lobby-1', 'Running', 3), service('lobby-2', 'Running', 4)];
    act(() => FakeWebSocket.last().emit({ id: 'e1', type: 'service.ready', subject: 'lobby-2', timestamp: Date.now() }));
    await waitFor(() => expect(screen.getByTestId('players-total')).toHaveTextContent('7'));
    expect(screen.getByTestId('phase-Running')).toHaveTextContent('2');
    expect(screen.getByText('service.ready')).toBeInTheDocument();
  });
});

describe('tasks', () => {
  it('crée une task par formulaire', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/tasks': { items: [] },
      'GET /api/v1/software': { items: [{ metadata: { name: 'paper' }, spec: { environment: 'server' } }] },
      'POST /api/v1/tasks': (r: { body: unknown }) => r.body,
    });
    renderApp(<TasksPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Nouvelle task' }));
    await userEvent.type(screen.getByLabelText('Nom'), 'bedwars');
    await userEvent.clear(screen.getByLabelText('Mémoire (Mo)'));
    await userEvent.type(screen.getByLabelText('Mémoire (Mo)'), '2048');
    await userEvent.clear(screen.getByLabelText('Version'));
    await userEvent.type(screen.getByLabelText('Version'), '1.21.4');
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({
      kind: 'ServiceTask',
      metadata: { name: 'bedwars' },
      spec: { environment: 'server', software: { name: 'paper', version: '1.21.4' }, memoryMb: 2048, minServiceCount: 1 },
    });
  });

  it('valide le YAML avant envoi', async () => {
    const { calls } = mockApi({ 'GET /api/v1/auth/me': me(), 'GET /api/v1/tasks': { items: [] }, 'GET /api/v1/software': { items: [] } });
    renderApp(<TasksPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Nouvelle task' }));
    await userEvent.click(screen.getByRole('tab', { name: 'YAML' }));
    fireEvent.change(screen.getByLabelText('editor'), { target: { value: 'metadata: [' } });
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('YAML invalide');
    fireEvent.change(screen.getByLabelText('editor'), { target: { value: 'spec: {}' } });
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('metadata.name est obligatoire');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('modifie en YAML avec If-Match, démarre et met en maintenance', async () => {
    const { calls, fetchMock } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/tasks/lobby': lobby,
      'GET /api/v1/services': { items: [service('lobby-1', 'Running', 5)] },
      'PUT /api/v1/tasks/lobby': (r: { body: unknown }) => r.body,
      'POST /api/v1/tasks/lobby/start': {},
      'POST /api/v1/tasks/lobby/maintenance': {},
    });
    renderApp(
      <Routes>
        <Route path="/tasks/:name" element={<TaskPage />} />
      </Routes>,
      { route: '/tasks/lobby' },
    );
    expect(await screen.findByRole('link', { name: 'lobby-1' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Démarrer' }));
    await userEvent.click(screen.getByRole('button', { name: 'Mettre en maintenance' }));
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2));
    expect(calls.find((c) => c.path.endsWith('/start'))!.body).toEqual({ count: 1 });
    expect(calls.find((c) => c.path.endsWith('/maintenance'))!.body).toEqual({ enabled: true });

    await userEvent.click(screen.getByRole('tab', { name: 'YAML' }));
    const editor = screen.getByLabelText('editor') as HTMLTextAreaElement;
    expect(editor.value).toContain('fallback:');
    expect(editor.value).not.toContain('readyServices');
    fireEvent.change(editor, { target: { value: editor.value.replace('minServiceCount: 2', 'minServiceCount: 3') } });
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({ spec: { minServiceCount: 3, fallback: { enabled: true } } });
    const put = fetchMock.mock.calls.find((c) => (c[1] as RequestInit | undefined)?.method === 'PUT')!;
    expect(new Headers((put[1] as RequestInit).headers).get('If-Match')).toBe('"12"');
  });

  it('lecture seule pour un viewer', async () => {
    mockApi({
      'GET /api/v1/auth/me': me(['ksmc.task.read', 'ksmc.service.read'], 'viewer'),
      'GET /api/v1/tasks/lobby': lobby,
      'GET /api/v1/services': { items: [] },
    });
    renderApp(
      <Routes>
        <Route path="/tasks/:name" element={<TaskPage />} />
      </Routes>,
      { route: '/tasks/lobby' },
    );
    expect(await screen.findByRole('heading', { name: /lobby/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Démarrer' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Supprimer' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Enregistrer' })).toBeNull();
    expect(screen.getByLabelText('editor')).toHaveAttribute('readonly');
  });
});

describe('groupes', () => {
  it('crée un groupe', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/groups': { items: [] },
      'POST /api/v1/groups': (r: { body: unknown }) => r.body,
    });
    renderApp(<GroupsPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Nouveau groupe' }));
    await userEvent.type(screen.getByLabelText('Nom'), 'global');
    await userEvent.click(screen.getByLabelText('server'));
    await userEvent.type(screen.getByLabelText('Templates (séparés par des virgules)'), 'plugins, config');
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({
      kind: 'ServiceGroup',
      metadata: { name: 'global' },
      spec: { targetEnvironments: ['server'], templates: [{ name: 'plugins' }, { name: 'config' }] },
    });
  });
});
