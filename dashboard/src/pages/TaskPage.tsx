import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useList, useOne, useResourceMutations, type Service, type Task } from '../api/resources';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { ResourceEditor } from '../components/ResourceEditor';
import { ConfirmButton, ErrorBox, Loading, PhaseBadge } from '../components/ui';
import { TaskForm } from './TaskForm';
import { taskState } from './TasksPage';

export default function TaskPage() {
  const { name } = useParams();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { can } = useSession();
  const task = useOne<Task>('tasks', name);
  const services = useList<Service>('services', { task: name! }, can(P.SERVICE_READ));
  const { replace, remove, action } = useResourceMutations('tasks');
  const [count, setCount] = useState('1');

  if (task.isLoading || !task.data) return <Loading when={task.isLoading}><ErrorBox error={task.error} /></Loading>;
  const data = task.data;
  const state = taskState(data);
  const save = (r: Task) => replace.mutate({ ...r, metadata: { ...r.metadata, resourceVersion: data.metadata.resourceVersion } });

  return (
    <>
      <div className="row between">
        <h1>
          {data.metadata.name} <span className={`badge ${state.style}`}>{t(state.label)}</span>
        </h1>
        <div className="row">
          {can(P.TASK_START) && (
            <>
              <input aria-label={t('tasks.count')} type="number" min={1} value={count} onChange={(e) => setCount(e.target.value)} style={{ width: '5rem' }} />
              <button onClick={() => action.mutate({ name: data.metadata.name, action: 'start', body: { count: Number(count) } })}>
                {t('tasks.start')}
              </button>
            </>
          )}
          {can(P.TASK_WRITE) && (
            <>
              <button onClick={() => action.mutate({ name: data.metadata.name, action: 'maintenance', body: { enabled: !data.spec.maintenance } })}>
                {data.spec.maintenance ? t('tasks.maintenanceOff') : t('tasks.maintenanceOn')}
              </button>
              <ConfirmButton
                message={t('common.confirmDelete', { name: data.metadata.name })}
                onConfirm={() => remove.mutate(data.metadata.name, { onSuccess: () => navigate('/tasks') })}
              >
                {t('common.delete')}
              </ConfirmButton>
            </>
          )}
        </div>
      </div>
      <ErrorBox error={action.error ?? remove.error} />
      <div className="grid">
        <div className="card stat">
          <div className="value">{data.status?.readyServices ?? 0}/{data.status?.totalServices ?? 0}</div>
          <div className="label">{t('tasks.services')}</div>
        </div>
        <div className="card stat">
          <div className="value">{data.status?.onlinePlayers ?? 0}</div>
          <div className="label">{t('tasks.players')}</div>
        </div>
        <div className="card stat">
          <div className="value">{data.spec.minServiceCount ?? 0}–{data.spec.maxServiceCount ?? '∞'}</div>
          <div className="label">{t('tasks.range')}</div>
        </div>
      </div>
      {can(P.SERVICE_READ) && (
        <>
          <h2>{t('nav.services')}</h2>
          <table className="responsive">
            <tbody>
              {services.data?.items.map((s) => (
                <tr key={s.metadata.name}>
                  <td data-label={t('common.name')}>
                    <Link to={`/services/${s.metadata.name}`}>{s.metadata.name}</Link>
                  </td>
                  <td data-label={t('services.phase')}>
                    <PhaseBadge phase={s.status?.phase} />
                  </td>
                  <td data-label={t('tasks.players')}>{s.status?.onlinePlayers ?? 0}</td>
                </tr>
              ))}
              {services.data?.items.length === 0 && (
                <tr>
                  <td className="muted">{t('common.none')}</td>
                </tr>
              )}
            </tbody>
          </table>
        </>
      )}
      <h2>{t('common.edit')}</h2>
      <div className="card">
        <ResourceEditor
          key={data.metadata.resourceVersion}
          resource={data}
          readOnly={!can(P.TASK_WRITE)}
          form={can(P.TASK_WRITE) ? <TaskForm task={data} onSubmit={save} saving={replace.isPending} /> : undefined}
          onSave={(r) => save(r as Task)}
          saving={replace.isPending}
          error={replace.error}
        />
        {replace.isSuccess && <p className="muted">{t('common.saved')}</p>}
      </div>
    </>
  );
}
