import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useList, type Service, type Task } from '../api/resources';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { useEvents } from '../events/EventStream';
import { ErrorBox, PhaseBadge } from '../components/ui';
import { taskState } from './TasksPage';

const PHASES = ['Running', 'Starting', 'Preparing', 'Pending', 'Stopping', 'Stopped', 'Failed'];

/** Compte des services par phase. */
export function countPhases(services: Service[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const s of services) {
    const p = s.status?.phase ?? 'Pending';
    out[p] = (out[p] ?? 0) + 1;
  }
  return out;
}

export default function OverviewPage() {
  const { t } = useTranslation();
  const { can } = useSession();
  const tasks = useList<Task>('tasks', {}, can(P.TASK_READ));
  const services = useList<Service>('services', {}, can(P.SERVICE_READ));
  const { events } = useEvents();
  const items = services.data?.items ?? [];
  const players = items.reduce((n, s) => n + (s.status?.onlinePlayers ?? 0), 0);
  const phases = countPhases(items);

  return (
    <>
      <h1>{t('nav.overview')}</h1>
      <ErrorBox error={tasks.error ?? services.error} />
      <div className="grid">
        <div className="card stat">
          <div className="value" data-testid="players-total">{players}</div>
          <div className="label">{t('overview.players')}</div>
        </div>
        <div className="card stat">
          <div className="value" data-testid="services-total">{items.length}</div>
          <div className="label">{t('overview.services')}</div>
        </div>
        <div className="card stat">
          <div className="value">{tasks.data?.items.length ?? 0}</div>
          <div className="label">{t('overview.tasks')}</div>
        </div>
      </div>

      {can(P.SERVICE_READ) && (
        <>
          <h2>{t('overview.byPhase')}</h2>
          <div className="row">
            {PHASES.filter((p) => phases[p]).map((p) => (
              <span key={p} data-testid={`phase-${p}`}>
                <PhaseBadge phase={p} /> {phases[p]}
              </span>
            ))}
            {items.length === 0 && <span className="muted">{t('common.none')}</span>}
          </div>
        </>
      )}

      {can(P.TASK_READ) && (
        <>
          <h2>{t('nav.tasks')}</h2>
          <table className="responsive">
            <tbody>
              {tasks.data?.items.map((task) => {
                const state = taskState(task);
                return (
                  <tr key={task.metadata.name}>
                    <td data-label={t('common.name')}>
                      <Link to={`/tasks/${task.metadata.name}`}>{task.metadata.name}</Link>
                    </td>
                    <td data-label={t('tasks.services')}>
                      {task.status?.readyServices ?? 0}/{task.status?.totalServices ?? 0}
                    </td>
                    <td data-label={t('tasks.players')}>{task.status?.onlinePlayers ?? 0}</td>
                    <td data-label={t('tasks.stateLabel')}>
                      <span className={`badge ${state.style}`}>{t(state.label)}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}

      {can(P.EVENT_READ) && (
        <>
          <h2>{t('overview.events')}</h2>
          <div className="card">
            <ul className="events">
              {events.slice(0, 15).map((e) => (
                <li key={e.id}>
                  <span className="muted">{new Date(e.timestamp).toLocaleTimeString()}</span> <strong>{e.type}</strong> {e.subject}
                </li>
              ))}
              {events.length === 0 && <li className="muted">{t('overview.noEvents')}</li>}
            </ul>
          </div>
        </>
      )}
    </>
  );
}
