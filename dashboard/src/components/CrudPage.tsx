import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useList, useResourceMutations, type Kind, type Resource } from '../api/resources';
import { useSession } from '../auth/session';
import { ResourceEditor } from './ResourceEditor';
import { ConfirmButton, ErrorBox, Loading } from './ui';

export interface Column<T> {
  label: string;
  value: (r: T) => ReactNode;
}

/**
 * Page générique liste + création/édition YAML (logiciels, modules) : un clic sur un nom ouvre son éditeur.
 * {@code actions} ajoute des boutons par ligne.
 */
export function CrudPage<T extends Resource<unknown, unknown>>({ kind, title, columns, blank, writePermission, actions, newLabel }: {
  kind: Kind;
  title: string;
  columns: Column<T>[];
  blank: Resource<unknown, unknown>;
  writePermission: string;
  newLabel: string;
  actions?: (r: T) => ReactNode;
}) {
  const { t } = useTranslation();
  const { can } = useSession();
  const list = useList<T>(kind);
  const { create, replace, remove } = useResourceMutations(kind);
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const writable = can(writePermission);
  const current = list.data?.items.find((r) => r.metadata.name === selected);

  return (
    <>
      <div className="row between">
        <h1>{title}</h1>
        {writable && (
          <button className="primary" onClick={() => { setCreating((c) => !c); setSelected(null); }}>
            {creating ? t('common.cancel') : newLabel}
          </button>
        )}
      </div>
      {creating && (
        <div className="card" style={{ marginBottom: '1rem' }}>
          <ResourceEditor resource={blank} saving={create.isPending} error={create.error}
            onSave={(r) => create.mutate(r, { onSuccess: () => { setCreating(false); setSelected(r.metadata.name); } })} />
        </div>
      )}
      <ErrorBox error={list.error ?? remove.error} />
      <Loading when={list.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('common.name')}</th>
              {columns.map((c) => <th key={c.label}>{c.label}</th>)}
              <th>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {list.data?.items.map((r) => (
              <tr key={r.metadata.name}>
                <td data-label={t('common.name')}>
                  <button className="link" onClick={() => { setSelected(selected === r.metadata.name ? null : r.metadata.name); setCreating(false); }}>
                    {r.metadata.name}
                  </button>
                </td>
                {columns.map((c) => <td key={c.label} data-label={c.label}>{c.value(r)}</td>)}
                <td data-label={t('common.actions')}>
                  <div className="row">
                    {actions?.(r)}
                    {writable && (
                      <ConfirmButton message={t('common.confirmDelete', { name: r.metadata.name })}
                        onConfirm={() => remove.mutate(r.metadata.name)}>
                        {t('common.delete')}
                      </ConfirmButton>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {list.data?.items.length === 0 && (
              <tr>
                <td colSpan={columns.length + 2} className="muted">{t('common.none')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
      {current && (
        <div className="card" style={{ marginTop: '1rem' }}>
          <h2 style={{ marginTop: 0 }}>{current.metadata.name}</h2>
          <ResourceEditor key={current.metadata.resourceVersion} resource={current} readOnly={!writable} saving={replace.isPending}
            error={replace.error}
            onSave={(r) => replace.mutate({ ...r, metadata: { ...r.metadata, resourceVersion: current.metadata.resourceVersion } })} />
          {replace.isSuccess && <p className="muted">{t('common.saved')}</p>}
        </div>
      )}
    </>
  );
}
