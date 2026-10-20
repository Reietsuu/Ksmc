import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import { Can } from './auth/session';
import { FakeWebSocket, me, mockApi, renderApp, SESSION } from './test/utils';

beforeEach(() => FakeWebSocket.reset());

describe('authentification', () => {
  it('redirige vers la connexion sans session puis connecte', async () => {
    const { calls } = mockApi({
      'POST /api/v1/auth/login': SESSION,
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/ui/modules': [],
    });
    renderApp(<App />, { route: '/tasks', session: null });
    await userEvent.type(await screen.findByLabelText('Utilisateur'), 'admin');
    await userEvent.type(screen.getByLabelText('Mot de passe'), 'secret');
    await userEvent.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(await screen.findByRole('link', { name: 'Tasks' })).toBeInTheDocument();
    expect(calls.find((c) => c.path === '/api/v1/auth/login')?.body).toEqual({ username: 'admin', password: 'secret' });
  });

  it('affiche une erreur pour des identifiants invalides', async () => {
    mockApi({ 'POST /api/v1/auth/login': new Response('{"code":"Unauthorized","message":"x"}', { status: 401 }) });
    renderApp(<App />, { session: null });
    await userEvent.type(await screen.findByLabelText('Utilisateur'), 'admin');
    await userEvent.type(screen.getByLabelText('Mot de passe'), 'faux');
    await userEvent.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Identifiants invalides');
  });

  it('impose le changement de mot de passe', async () => {
    mockApi({
      'GET /api/v1/auth/me': { ...me(), mustChangePassword: true },
      'GET /api/v1/ui/modules': [],
    });
    renderApp(<App />);
    expect(await screen.findByRole('heading', { name: 'Changer le mot de passe' })).toBeInTheDocument();
  });

  it('déconnecte', async () => {
    mockApi({ 'GET /api/v1/auth/me': me(), 'GET /api/v1/ui/modules': [] });
    const { api } = renderApp(<App />);
    await userEvent.click(await screen.findByRole('button', { name: 'Déconnexion' }));
    expect(api.current()).toBeNull();
    expect(await screen.findByRole('button', { name: 'Se connecter' })).toBeInTheDocument();
  });
});

describe('permissions', () => {
  it('masque les entrées de menu non autorisées', async () => {
    mockApi({
      'GET /api/v1/auth/me': me(['ksmc.task.read', 'ksmc.service.read', 'ksmc.event.read'], 'viewer'),
      'GET /api/v1/ui/modules': [{ module: 'hello', title: 'Hello', bundle: '/modules/hello/ui.js' }],
    });
    renderApp(<App />);
    expect(await screen.findByRole('link', { name: 'Services' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Templates' })).toBeNull();
    expect(await screen.findByRole('link', { name: 'Hello' })).toHaveAttribute('href', '/ui/hello');
  });

  it('Can affiche selon la permission', async () => {
    mockApi({ 'GET /api/v1/auth/me': me(['ksmc.service.*']) });
    renderApp(
      <>
        <Can permission="ksmc.service.write">
          <span>arrêter</span>
        </Can>
        <Can permission="ksmc.task.write" fallback={<span>lecture seule</span>}>
          <span>modifier</span>
        </Can>
      </>,
    );
    expect(await screen.findByText('arrêter')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('lecture seule')).toBeInTheDocument());
    expect(screen.queryByText('modifier')).toBeNull();
  });
});
