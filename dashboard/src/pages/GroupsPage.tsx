import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { newResource, useList, useResourceMutations, type Resource } from '../api/resources';
import { Can } from '../auth/session';
import { P } from '../auth/permissions';
import { ResourceEditor } from '../components/ResourceEditor';
import { ErrorBox, Loading } from '../components/ui';

export interface GroupSpec {
  targetEnvironments?: string[];
  templates?: { name: string; optional?: boolean }[];
  [key: string]: unknown;
}
export type Group = Resource<GroupSpec>;

/** Formulaire de groupe : environnements ciblés et templates ; le reste de la spec est conservé. */
export function GroupForm({ group, creating, onSubmit, saving }: { group: Group; creating?: boolean; onSubmit: (g: Group) => void; saving?: boolean }) {
  const { t } = useTranslation();
  const [name, setName] = useState(group.metadata.name);
  const [envs, setEnvs] = useState(new Set(group.spec.targetEnvironments ?? []));
  const [templates, setTemplates] = useState((group.spec.templates ?? []).map((x) => x.name).join(', '));
  const toggle = (env: string) => setEnvs((s) => {
    const n = new Set(s);
    if (n.has(env)) n.delete(env);
    else n.add(env);
    return n;
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const previous = new Map((group.spec.templates ?? []).map((x) => [x.name, x]));
    const spec: GroupSpec = {
      ...group.spec,
      targetEnvironments: [...envs],
      templates: templates.split(',').map((x) => x.trim()).filter(Boolean).map((n) => previous.get(n) ?? { name: n }),
    };
    if (!spec.targetEnvironments!.length) delete spec.targetEnvironments;
    if (!spec.templates!.length) delete spec.templates;
    onSubmit({ ...group, metadata: { ...group.metadata, name: name.trim() }, spec });
  };
  return (
    <form className="stack" onSubmit={submit}>
      <div className="form-grid">
        <label>
          {t('common.name')}
          <input name="name" value={name} onChange={(e) => setName(e.target.value)} required disabled={!creating} />
        </label>
        <label>
          {t('tasks.templates')}
          <input name="templates" value={templates} onChange={(e) => setTemplates(e.target.value)} />
        </label>
      </div>
      <div className="row">
        <span className="muted">{t('groups.targets')}</span>
        {['server', 'proxy'].map((env) => (
          <label className="inline" key={env}>
            <input type="checkbox" checked={envs.has(env)} onChange={() => toggle(env)} /> {env}
          </label>
        ))}
      </div>
      <div className="row">
        <button className="primary" type="submit" disabled={saving}>
          {creating ? t('common.create') : t('common.save')}
        </button>
      </div>
    </form>
  );
}

export default function GroupsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const groups = useList<Group>('groups');
  const { create } = useResourceMutations('groups');
  const [creating, setCreating] = useState(false);
  const blank = newResource<GroupSpec>('groups', '', {});
  const save = (g: Group) => create.mutate(g, { onSuccess: () => navigate(`/groups/${g.metadata.name}`) });

  return (
    <>
      <div className="row between">
        <h1>{t('nav.groups')}</h1>
        <Can permission={P.GROUP_WRITE}>
          <button className="primary" onClick={() => setCreating((c) => !c)}>
            {creating ? t('common.cancel') : t('groups.new')}
          </button>
        </Can>
      </div>
      {creating && (
        <div className="card" style={{ marginBottom: '1rem' }}>
          <ResourceEditor resource={blank} form={<GroupForm group={blank} creating onSubmit={save} saving={create.isPending} />}
            onSave={(r) => save(r as Group)} saving={create.isPending} error={create.error} />
        </div>
      )}
      <ErrorBox error={groups.error} />
      <Loading when={groups.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('common.name')}</th>
              <th>{t('groups.targets')}</th>
              <th>{t('tasks.templates')}</th>
            </tr>
          </thead>
          <tbody>
            {groups.data?.items.map((g) => (
              <tr key={g.metadata.name}>
                <td data-label={t('common.name')}>
                  <Link to={`/groups/${g.metadata.name}`}>{g.metadata.name}</Link>
                </td>
                <td data-label={t('groups.targets')}>{(g.spec.targetEnvironments ?? []).join(', ') || '—'}</td>
                <td data-label={t('tasks.templates')}>{(g.spec.templates ?? []).map((x) => x.name).join(', ') || '—'}</td>
              </tr>
            ))}
            {groups.data?.items.length === 0 && (
              <tr>
                <td colSpan={3} className="muted">{t('common.none')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
    </>
  );
}
