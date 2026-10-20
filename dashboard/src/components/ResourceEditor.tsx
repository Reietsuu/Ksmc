import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import YAML from 'yaml';
import type { Resource } from '../api/resources';
import { CodeEditor } from './CodeEditor';
import { ErrorBox } from './ui';

/** Objet → YAML éditable (sans statut ni champs gérés par Kubernetes). */
export function toYaml(r: Resource<unknown, unknown>): string {
  const { status: _status, ...rest } = r as Resource<unknown, unknown> & { status?: unknown };
  const meta = { ...rest.metadata } as Record<string, unknown>;
  for (const k of ['uid', 'creationTimestamp', 'generation', 'managedFields', 'namespace', 'ownerReferences']) delete meta[k];
  return YAML.stringify({ ...rest, metadata: meta });
}

/** YAML → ressource, avec les erreurs de syntaxe et de structure en français. */
export function parseResource(text: string): Resource<unknown, unknown> {
  let doc: unknown;
  try {
    doc = YAML.parse(text);
  } catch (e) {
    throw new Error(`YAML invalide : ${(e as Error).message}`);
  }
  if (!doc || typeof doc !== 'object') throw new Error('YAML invalide : un objet est attendu');
  const r = doc as Resource<unknown, unknown>;
  if (!r.metadata?.name) throw new Error('metadata.name est obligatoire');
  if (!r.spec || typeof r.spec !== 'object') throw new Error('spec est obligatoire');
  return r;
}

/**
 * Édition d'une ressource : onglet formulaire (fourni par la page) ou YAML complet.
 * {@code form} reçoit la ressource et doit appeler {@code onSave} ; le YAML est validé avant envoi.
 */
export function ResourceEditor({ resource, form, onSave, saving, error, readOnly }: {
  resource: Resource<unknown, unknown>;
  form?: ReactNode;
  onSave: (r: Resource<unknown, unknown>) => void;
  saving?: boolean;
  error?: unknown;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [tab, setTab] = useState<'form' | 'yaml'>(form ? 'form' : 'yaml');
  const [text, setText] = useState(() => toYaml(resource));
  const [invalid, setInvalid] = useState<string | null>(null);

  const saveYaml = () => {
    try {
      const r = parseResource(text);
      setInvalid(null);
      onSave(r);
    } catch (e) {
      setInvalid((e as Error).message);
    }
  };

  return (
    <div>
      {form && (
        <div className="tabs" role="tablist">
          <button role="tab" aria-selected={tab === 'form'} className={tab === 'form' ? 'active' : ''} onClick={() => setTab('form')}>
            {t('editor.form')}
          </button>
          <button
            role="tab"
            aria-selected={tab === 'yaml'}
            className={tab === 'yaml' ? 'active' : ''}
            onClick={() => {
              setText(toYaml(resource));
              setTab('yaml');
            }}
          >
            YAML
          </button>
        </div>
      )}
      {tab === 'form' && form}
      {tab === 'yaml' && (
        <div className="stack">
          <CodeEditor value={text} onChange={setText} language="yaml" readOnly={readOnly} path={`${resource.metadata.name || 'new'}.yaml`} />
          {invalid && <p className="error" role="alert">{invalid}</p>}
          {!readOnly && (
            <div className="row">
              <button className="primary" onClick={saveYaml} disabled={saving}>
                {t('common.save')}
              </button>
            </div>
          )}
        </div>
      )}
      <ErrorBox error={error} />
    </div>
  );
}
