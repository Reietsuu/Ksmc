import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useList, type Service } from '../api/resources';
import { ErrorBox, Loading, PhaseBadge } from '../components/ui';

/** Filtre texte (nom), task et phase. */
export function filterServices(items: Service[], { q, task, phase }: { q?: string; task?: string; phase?: string }) {
  return items.filter(
    (s) =>
      (!q || s.metadata.name.includes(q.toLowerCase())) &&
      (!task || s.spec.task === task) &&
      (!phase || (s.status?.phase ?? 'Pending') === phase),
  );
}

export default function ServicesPage() {
  const { t } = useTranslation();
  const services = useList<Service>('services');
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  const task = params.get('task') ?? '';
  const phase = params.get('phase') ?? '';
  const items = services.data?.items ?? [];
  const tasks = useMemo(() => [...new Set(items.map((s) => s.spec.task).filter(Boolean))].sort() as string[], [items]);
  const phases = useMemo(() => [...new Set(items.map((s) => s.status?.phase ?? 'Pending'))].sort(), [items]);
  const shown = filterServices(items, { q, task, phase });
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  return (
    <>
      <h1>{t('nav.services')}</h1>
      <div className="form-grid" style={{ marginBottom: '1rem' }}>
        <label>
          {t('services.search')}
          <input type="search" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <label>
          {t('services.task')}
          <select value={task} onChange={(e) => set('task', e.target.value)}>
            <option value="">{t('services.all')}</option>
            {tasks.map((x) => (
              <option key={x}>{x}</option>
            ))}
          </select>
        </label>
        <label>
          {t('services.phase')}
          <select value={phase} onChange={(e) => set('phase', e.target.value)}>
            <option value="">{t('services.all')}</option>
            {phases.map((x) => (
              <option key={x} value={x}>
                {t(`phase.${x}`, x)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorBox error={services.error} />
      <Loading when={services.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('common.name')}</th>
              <th>{t('services.task')}</th>
              <th>{t('services.phase')}</th>
              <th>{t('tasks.players')}</th>
              <th>{t('tasks.version')}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((s) => (
              <tr key={s.metadata.name}>
                <td data-label={t('common.name')}>
                  <Link to={`/services/${s.metadata.name}`}>{s.metadata.name}</Link>
                </td>
                <td data-label={t('services.task')}>{s.spec.task ?? '—'}</td>
                <td data-label={t('services.phase')}>
                  <PhaseBadge phase={s.status?.phase} />
                </td>
                <td data-label={t('tasks.players')}>
                  {s.status?.onlinePlayers ?? 0}
                  {s.status?.maxPlayers ? `/${s.status.maxPlayers}` : ''}
                </td>
                <td data-label={t('tasks.version')}>{s.status?.softwareVersion ?? s.spec.software?.version ?? '—'}</td>
              </tr>
            ))}
            {shown.length === 0 && (
              <tr>
                <td colSpan={5} className="muted">{t('common.none')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
    </>
  );
}
