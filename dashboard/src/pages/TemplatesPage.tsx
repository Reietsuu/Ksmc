import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { newResource, useList, useResourceMutations, type Resource } from '../api/resources';
import { Can } from '../auth/session';
import { P } from '../auth/permissions';
import { ResourceEditor } from '../components/ResourceEditor';
import { ErrorBox, Loading } from '../components/ui';

export type TemplateSpec = {
  storage?: { path: string };
  git?: { url: string; ref?: string; path?: string };
  http?: { url: string };
  image?: { image: string; path?: string };
  configMap?: { name: string };
  [key: string]: unknown;
};
export type Template = Resource<TemplateSpec>;

export function sourceOf(t: Template): { type: string; detail: string } {
  const s = t.spec;
  if (s.storage) return { type: 'storage', detail: s.storage.path };
  if (s.git) return { type: 'git', detail: `${s.git.url}${s.git.ref ? '@' + s.git.ref : ''}` };
  if (s.http) return { type: 'http', detail: s.http.url };
  if (s.image) return { type: 'image', detail: s.image.image };
  if (s.configMap) return { type: 'configMap', detail: s.configMap.name };
  return { type: '—', detail: '' };
}

export default function TemplatesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const templates = useList<Template>('templates');
  const { create } = useResourceMutations('templates');
  const [creating, setCreating] = useState(false);
  const blank = newResource<TemplateSpec>('templates', 'nouveau-template', { storage: { path: 'nouveau/default' } });

  return (
    <>
      <div className="row between">
        <h1>{t('nav.templates')}</h1>
        <Can permission={P.TEMPLATE_WRITE}>
          <button className="primary" onClick={() => setCreating((c) => !c)}>
            {creating ? t('common.cancel') : t('templates.new')}
          </button>
        </Can>
      </div>
      {creating && (
        <div className="card" style={{ marginBottom: '1rem' }}>
          <ResourceEditor resource={blank} saving={create.isPending} error={create.error}
            onSave={(r) => create.mutate(r, { onSuccess: () => navigate(`/templates/${r.metadata.name}`) })} />
        </div>
      )}
      <ErrorBox error={templates.error} />
      <Loading when={templates.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('common.name')}</th>
              <th>{t('templates.source')}</th>
              <th>{t('templates.location')}</th>
            </tr>
          </thead>
          <tbody>
            {templates.data?.items.map((tpl) => {
              const src = sourceOf(tpl);
              return (
                <tr key={tpl.metadata.name}>
                  <td data-label={t('common.name')}>
                    <Link to={`/templates/${tpl.metadata.name}`}>{tpl.metadata.name}</Link>
                  </td>
                  <td data-label={t('templates.source')}>
                    <span className="badge">{src.type}</span>
                  </td>
                  <td data-label={t('templates.location')}>{src.detail}</td>
                </tr>
              );
            })}
            {templates.data?.items.length === 0 && (
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
