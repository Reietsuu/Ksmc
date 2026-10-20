import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useOne, useResourceMutations, type Service } from '../api/resources';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { Console } from '../components/Console';
import { CodeEditor } from '../components/CodeEditor';
import { toYaml } from '../components/ResourceEditor';
import { ConfirmButton, ErrorBox, Loading, PhaseBadge } from '../components/ui';

export default function ServicePage() {
  const { name } = useParams();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { can } = useSession();
  const service = useOne<Service>('services', name);
  const { action, remove } = useResourceMutations('services');
  if (!service.data) return <Loading when={service.isLoading}><ErrorBox error={service.error} /></Loading>;
  const s = service.data;
  const st = s.status ?? {};
  const stopped = s.spec.desiredState === 'Stopped';
  const writable = can(P.SERVICE_WRITE);
  const run = (a: string) => action.mutate({ name: s.metadata.name, action: a });

  const facts: [string, React.ReactNode][] = [
    [t('services.task'), s.spec.task ? <Link to={`/tasks/${s.spec.task}`}>{s.spec.task}</Link> : '—'],
    [t('tasks.players'), `${st.onlinePlayers ?? 0}${st.maxPlayers ? '/' + st.maxPlayers : ''}`],
    [t('tasks.version'), st.softwareVersion ?? s.spec.software?.version ?? '—'],
    [t('services.address'), st.address ?? '—'],
    [t('services.pod'), st.podName ?? '—'],
    [t('services.started'), st.startedAt ? new Date(st.startedAt).toLocaleString() : '—'],
    [t('services.restarts'), st.restartCount ?? 0],
  ];
  if (st.tps !== undefined) facts.push(['TPS', st.tps.toFixed(1)]);
  if (st.message) facts.push([t('services.message'), st.message]);

  return (
    <>
      <div className="row between">
        <h1>
          {s.metadata.name} <PhaseBadge phase={st.phase} />
        </h1>
        <div className="row">
          {stopped ? (
            <button disabled={!writable} onClick={() => run('start')}>{t('services.start')}</button>
          ) : (
            <button disabled={!writable} onClick={() => run('stop')}>{t('services.stop')}</button>
          )}
          <button disabled={!writable} onClick={() => run('restart')}>{t('services.restart')}</button>
          <ConfirmButton
            disabled={!writable}
            message={t('common.confirmDelete', { name: s.metadata.name })}
            onConfirm={() => remove.mutate(s.metadata.name, { onSuccess: () => navigate('/services') })}
          >
            {t('common.delete')}
          </ConfirmButton>
        </div>
      </div>
      <ErrorBox error={action.error ?? remove.error} />
      <div className="card">
        <table className="responsive" style={{ border: 'none' }}>
          <tbody>
            {facts.map(([k, v]) => (
              <tr key={k}>
                <th style={{ width: '12rem' }}>{k}</th>
                <td data-label={k}>{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {can(P.SERVICE_CONSOLE) && (
        <>
          <h2>{t('console.title')}</h2>
          <Console service={s.metadata.name} />
        </>
      )}
      <h2>{t('services.definition')}</h2>
      <CodeEditor value={toYaml(s)} language="yaml" readOnly path={`${s.metadata.name}.yaml`} />
    </>
  );
}
