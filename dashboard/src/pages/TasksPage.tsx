import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { newResource, useList, useResourceMutations, type Task, type TaskSpec } from '../api/resources';
import { Can } from '../auth/session';
import { P } from '../auth/permissions';
import { ResourceEditor } from '../components/ResourceEditor';
import { ErrorBox, Loading } from '../components/ui';
import { TaskForm } from './TaskForm';

export function taskState(task: Task): { label: string; style: string } {
  if (task.spec.maintenance) return { label: 'tasks.state.maintenance', style: 'warn' };
  if (task.spec.enabled === false) return { label: 'tasks.state.disabled', style: '' };
  if (task.status?.conditions?.some((c) => c.type === 'Degraded' && c.status === 'True')) return { label: 'tasks.state.degraded', style: 'danger' };
  return { label: 'tasks.state.ok', style: 'ok' };
}

export default function TasksPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const tasks = useList<Task>('tasks');
  const { create } = useResourceMutations('tasks');
  const [creating, setCreating] = useState(false);
  const blank = newResource<TaskSpec>('tasks', '', { environment: 'server', software: { name: 'paper', version: 'latest' }, memoryMb: 1024, minServiceCount: 1 });

  const save = (task: Task) => create.mutate(task, { onSuccess: () => navigate(`/tasks/${task.metadata.name}`) });

  return (
    <>
      <div className="row between">
        <h1>{t('nav.tasks')}</h1>
        <Can permission={P.TASK_WRITE}>
          <button className="primary" onClick={() => setCreating((c) => !c)}>
            {creating ? t('common.cancel') : t('tasks.new')}
          </button>
        </Can>
      </div>
      {creating && (
        <div className="card" style={{ marginBottom: '1rem' }}>
          <ResourceEditor
            resource={blank}
            form={<TaskForm task={blank} creating onSubmit={save} saving={create.isPending} />}
            onSave={(r) => save(r as Task)}
            saving={create.isPending}
            error={create.error}
          />
        </div>
      )}
      <ErrorBox error={tasks.error} />
      <Loading when={tasks.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('common.name')}</th>
              <th>{t('tasks.environment')}</th>
              <th>{t('tasks.software')}</th>
              <th>{t('tasks.services')}</th>
              <th>{t('tasks.players')}</th>
              <th>{t('tasks.stateLabel')}</th>
            </tr>
          </thead>
          <tbody>
            {tasks.data?.items.map((task) => {
              const state = taskState(task);
              return (
                <tr key={task.metadata.name}>
                  <td data-label={t('common.name')}>
                    <Link to={`/tasks/${task.metadata.name}`}>{task.metadata.name}</Link>
                  </td>
                  <td data-label={t('tasks.environment')}>{task.spec.environment}</td>
                  <td data-label={t('tasks.software')}>
                    {task.spec.software?.name} {task.spec.software?.version}
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
            {tasks.data?.items.length === 0 && (
              <tr>
                <td colSpan={6} className="muted">{t('common.none')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
    </>
  );
}
