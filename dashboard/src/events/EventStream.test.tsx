import { act, screen, waitFor } from '@testing-library/react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../auth/session';
import { FakeWebSocket, me, mockApi, renderApp } from '../test/utils';
import { invalidationsFor, useEvents } from './EventStream';

function Probe() {
  const { api } = useSession();
  const services = useQuery({ queryKey: ['services'], queryFn: () => api.json<{ items: unknown[] }>('/api/v1/services') });
  const { events, connected } = useEvents();
  return (
    <div>
      <span>services:{services.data?.items.length ?? '-'}</span>
      <span>events:{events.map((e) => e.type).join(',')}</span>
      <span>{connected ? 'connecté' : 'déconnecté'}</span>
    </div>
  );
}

beforeEach(() => FakeWebSocket.reset());

describe('flux d’événements', () => {
  it('invalide les requêtes concernées et garde les derniers événements', async () => {
    let count = 1;
    mockApi({
      'GET /api/v1/auth/me': me(),
      'GET /api/v1/services': () => ({ items: Array.from({ length: count }) }),
    });
    renderApp(<Probe />);
    expect(await screen.findByText('services:1')).toBeInTheDocument();
    await waitFor(() => expect(FakeWebSocket.instances.length).toBe(1));
    expect(new URL(FakeWebSocket.last().url).searchParams.get('access_token')).toBe('a1');
    expect(await screen.findByText('connecté')).toBeInTheDocument();
    count = 2;
    act(() => FakeWebSocket.last().emit({ id: '1', type: 'service.ready', subject: 'lobby-2', timestamp: 1, sequence: 7 }));
    expect(await screen.findByText('services:2')).toBeInTheDocument();
    expect(screen.getByText('events:service.ready')).toBeInTheDocument();
  });

  it("ne s'abonne pas sans la permission ksmc.event.read", async () => {
    mockApi({ 'GET /api/v1/auth/me': me(['ksmc.service.read']), 'GET /api/v1/services': { items: [] } });
    renderApp(<Probe />);
    expect(await screen.findByText('services:0')).toBeInTheDocument();
    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it('associe les types aux requêtes', () => {
    expect(invalidationsFor('player.login')).toContainEqual(['players']);
    expect(invalidationsFor('task.scaled')).toContainEqual(['tasks']);
    expect(invalidationsFor('autre')).toEqual([]);
  });
});
