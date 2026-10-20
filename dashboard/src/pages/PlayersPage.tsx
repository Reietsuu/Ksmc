import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { ErrorBox, Loading } from '../components/ui';

export interface Player {
  uuid: string;
  name: string;
  proxy?: string;
  server?: string;
  connectedAt?: number;
}

export default function PlayersPage() {
  const { t } = useTranslation();
  const { api, can } = useSession();
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const players = useQuery({
    queryKey: ['players'],
    queryFn: () => api.json<{ items: Player[] }>('/api/v1/players'),
    refetchInterval: 10_000,
  });
  const act = useMutation({
    mutationFn: ({ player, action, body }: { player: Player; action: string; body: unknown }) =>
      api.json(`/api/v1/players/${encodeURIComponent(player.uuid || player.name)}/${action}`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => setTimeout(() => qc.invalidateQueries({ queryKey: ['players'] }), 500),
  });
  const ask = (player: Player, action: string, prompt: string, field: string, optional = false) => {
    const value = window.prompt(t(prompt, { name: player.name }));
    if (value === null || (!value && !optional)) return;
    act.mutate({ player, action, body: value ? { [field]: value } : {} });
  };
  const shown = (players.data?.items ?? []).filter((p) => !q || p.name.toLowerCase().includes(q.toLowerCase()));
  const canAct = can(P.PLAYER_ACTION);

  return (
    <>
      <h1>
        {t('nav.players')} <span className="badge">{players.data?.items.length ?? 0}</span>
      </h1>
      <label style={{ maxWidth: '20rem', marginBottom: '1rem' }}>
        {t('services.search')}
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} />
      </label>
      <ErrorBox error={players.error ?? act.error} />
      <Loading when={players.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('players.name')}</th>
              <th>{t('players.server')}</th>
              <th>{t('players.proxy')}</th>
              <th>{t('players.since')}</th>
              {canAct && <th>{t('common.actions')}</th>}
            </tr>
          </thead>
          <tbody>
            {shown.map((p) => (
              <tr key={p.uuid || p.name}>
                <td data-label={t('players.name')}>{p.name}</td>
                <td data-label={t('players.server')}>{p.server ? <Link to={`/services/${p.server}`}>{p.server}</Link> : '—'}</td>
                <td data-label={t('players.proxy')}>{p.proxy ?? '—'}</td>
                <td data-label={t('players.since')}>{p.connectedAt ? new Date(p.connectedAt).toLocaleTimeString() : '—'}</td>
                {canAct && (
                  <td data-label={t('common.actions')}>
                    <div className="row">
                      <button onClick={() => ask(p, 'send', 'players.sendPrompt', 'service')} aria-label={`${t('players.send')} ${p.name}`}>{t('players.send')}</button>
                      <button onClick={() => ask(p, 'message', 'players.messagePrompt', 'text')} aria-label={`${t('players.message')} ${p.name}`}>{t('players.message')}</button>
                      <button className="danger" onClick={() => ask(p, 'kick', 'players.kickPrompt', 'reason', true)} aria-label={`${t('players.kick')} ${p.name}`}>
                        {t('players.kick')}
                      </button>
                    </div>
                  </td>
                )}
              </tr>
            ))}
            {shown.length === 0 && (
              <tr>
                <td colSpan={5} className="muted">{t('players.none')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
    </>
  );
}
