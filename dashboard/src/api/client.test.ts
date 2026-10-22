import { ApiClient, ApiError } from './client';
import { mockApi, SESSION } from '../test/utils';

describe('ApiClient', () => {
  it('ajoute le jeton et le renouvelle une fois sur 401', async () => {
    let first = true;
    const { calls } = mockApi({
      'GET /api/v1/tasks': () => {
        if (first) {
          first = false;
          return new Response('{}', { status: 401 });
        }
        return { items: [] };
      },
      'POST /api/v1/auth/refresh': { ...SESSION, accessToken: 'a2' },
    });
    const api = new ApiClient();
    api.setSession(SESSION);
    await expect(api.json('/api/v1/tasks')).resolves.toEqual({ items: [] });
    expect(calls.map((c) => c.path)).toEqual(['/api/v1/tasks', '/api/v1/auth/refresh', '/api/v1/tasks']);
    expect(api.current()?.accessToken).toBe('a2');
    expect(JSON.parse(localStorage.getItem('ksmc.session')!).accessToken).toBe('a2');
  });

  it('ferme la session si le renouvellement échoue', async () => {
    mockApi({
      'GET /api/v1/tasks': new Response('{}', { status: 401 }),
      'POST /api/v1/auth/refresh': new Response('{}', { status: 401 }),
    });
    const api = new ApiClient();
    api.setSession(SESSION);
    await expect(api.json('/api/v1/tasks')).rejects.toBeInstanceOf(ApiError);
    expect(api.current()).toBeNull();
  });

  it('garde la session si le mot de passe actuel est incorrect', async () => {
    const { calls } = mockApi({
      'POST /api/v1/auth/password': new Response(
        JSON.stringify({ code: 'InvalidCredentials', message: 'mot de passe actuel incorrect' }),
        { status: 401 },
      ),
    });
    const api = new ApiClient();
    api.setSession(SESSION);
    const err = await api.json('/api/v1/auth/password', { method: 'POST', body: '{}' }).catch((e) => e);
    expect(err).toMatchObject({ status: 401, message: 'mot de passe actuel incorrect' });
    expect(calls.map((c) => c.path)).toEqual(['/api/v1/auth/password']);
    expect(api.current()).not.toBeNull();
  });

  it("convertit les erreurs de l'API", async () => {
    mockApi({
      'DELETE /api/v1/tasks/lobby': new Response(JSON.stringify({ code: 'Forbidden', message: 'non' }), { status: 403 }),
    });
    const api = new ApiClient();
    api.setSession(SESSION);
    const err = await api.json('/api/v1/tasks/lobby', { method: 'DELETE' }).catch((e) => e);
    expect(err).toMatchObject({ status: 403, code: 'Forbidden', message: 'non' });
  });

  it('authentifie les WebSocket par paramètre', () => {
    const api = new ApiClient();
    api.setSession(SESSION);
    const url = new URL(api.wsUrl('/api/v1/events', { since: '4' }));
    expect(url.protocol).toBe('ws:');
    expect(url.searchParams.get('access_token')).toBe('a1');
    expect(url.searchParams.get('since')).toBe('4');
  });

  it('expose un client typé partageant la session', async () => {
    const { calls } = mockApi({ 'GET /api/v1/auth/me': { name: 'admin', permissions: ['*'], mustChangePassword: false } });
    const api = new ApiClient();
    api.setSession(SESSION);
    const { data } = await api.typed().GET('/api/v1/auth/me');
    expect(data).toMatchObject({ name: 'admin' });
    expect(calls[0].path).toBe('/api/v1/auth/me');
  });
});
