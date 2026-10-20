import { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useOne, useResourceMutations } from '../api/resources';
import { isText, languageOf, saveBlob, templateFiles } from '../api/templateFiles';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { CodeEditor } from '../components/CodeEditor';
import { ResourceEditor } from '../components/ResourceEditor';
import { ConfirmButton, ErrorBox, Loading } from '../components/ui';
import { sourceOf, type Template } from './TemplatesPage';

function formatSize(n: number) {
  return n < 1024 ? `${n} o` : n < 1048576 ? `${(n / 1024).toFixed(1)} Ko` : `${(n / 1048576).toFixed(1)} Mo`;
}

/** Explorateur et éditeur des fichiers d'un template storage. */
export function FileBrowser({ storagePath }: { storagePath: string }) {
  const { t } = useTranslation();
  const { api, can } = useSession();
  const qc = useQueryClient();
  const files = templateFiles(api, storagePath);
  const writable = can(P.TEMPLATE_WRITE);
  const list = useQuery({ queryKey: ['template-files', storagePath], queryFn: files.list });
  const [open, setOpen] = useState<{ path: string; text: string | null; original: string } | null>(null);
  const [dir, setDir] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [status, setStatus] = useState<string | null>(null);
  const upload = useRef<HTMLInputElement>(null);
  const uploadArchive = useRef<HTMLInputElement>(null);

  const run = async (f: () => Promise<unknown>, done?: string) => {
    setError(null);
    setStatus(null);
    try {
      await f();
      if (done) setStatus(done);
      await qc.invalidateQueries({ queryKey: ['template-files', storagePath] });
    } catch (e) {
      setError(e);
    }
  };

  const openFile = (path: string) =>
    run(async () => {
      const bytes = await files.read(path);
      const text = isText(path, bytes) ? new TextDecoder().decode(bytes) : null;
      setOpen({ path, text, original: text ?? '' });
    });

  const download = (path: string) => run(async () => saveBlob(new Blob([await files.read(path)]), path.split('/').pop()!));
  const save = () => open && open.text !== null && run(async () => {
    await files.write(open.path, open.text!);
    setOpen({ ...open, original: open.text! });
  }, t('common.saved'));
  const newFile = () => {
    const name = window.prompt(t('templates.newFilePrompt'), dir ? `${dir}/` : '');
    if (name) run(async () => {
      await files.write(name, '');
      setOpen({ path: name, text: '', original: '' });
    });
  };
  const onUpload = (list: FileList | null) => list && run(async () => {
    for (const f of Array.from(list)) await files.write(dir ? `${dir}/${f.name}` : f.name, f);
  }, t('templates.uploaded', { count: list.length }));
  const onUploadArchive = (list: FileList | null) => list?.[0] && run(() => files.uploadArchive(list[0]), t('templates.uploaded', { count: 1 }));

  const sorted = [...(list.data ?? [])].sort((a, b) => a.path.localeCompare(b.path));
  return (
    <div className="stack">
      <div className="row">
        <input aria-label={t('templates.directory')} placeholder={t('templates.directory')} value={dir}
          onChange={(e) => setDir(e.target.value.replace(/^\/+|\/+$/g, ''))} style={{ maxWidth: '16rem' }} />
        {writable && (
          <>
            <button onClick={newFile}>{t('templates.newFile')}</button>
            <button onClick={() => upload.current?.click()}>{t('templates.upload')}</button>
            <button onClick={() => uploadArchive.current?.click()}>{t('templates.uploadArchive')}</button>
          </>
        )}
        <button onClick={() => run(async () => saveBlob(await files.archive(), `${storagePath.replace(/\//g, '-')}.zip`))}>
          {t('templates.downloadArchive')}
        </button>
        <input ref={upload} type="file" multiple hidden data-testid="upload" onChange={(e) => onUpload(e.target.files)} />
        <input ref={uploadArchive} type="file" accept=".zip,.tar.gz,.tgz" hidden data-testid="upload-archive"
          onChange={(e) => onUploadArchive(e.target.files)} />
      </div>
      <ErrorBox error={error ?? list.error} />
      {status && <p className="muted" role="status">{status}</p>}
      <Loading when={list.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('templates.file')}</th>
              <th>{t('templates.size')}</th>
              <th>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((f) => (
              <tr key={f.path}>
                <td data-label={t('templates.file')}>
                  <button className="link" onClick={() => openFile(f.path)}>{f.path}</button>
                </td>
                <td data-label={t('templates.size')}>{formatSize(f.size)}</td>
                <td data-label={t('common.actions')}>
                  <div className="row">
                    <button onClick={() => download(f.path)} aria-label={`${t('templates.download')} ${f.path}`}>{t('templates.download')}</button>
                    {writable && (
                      <ConfirmButton message={t('common.confirmDelete', { name: f.path })} onConfirm={() => run(() => files.remove(f.path))}>
                        {t('common.delete')}
                      </ConfirmButton>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {sorted.length === 0 && (
              <tr>
                <td colSpan={3} className="muted">{t('templates.empty')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
      {open && (
        <div className="card stack" data-testid="file-editor">
          <div className="row between">
            <strong>{open.path}{open.text !== null && open.text !== open.original ? ' •' : ''}</strong>
            <div className="row">
              {open.text !== null && writable && (
                <button className="primary" onClick={save} disabled={open.text === open.original}>{t('common.save')}</button>
              )}
              <button onClick={() => setOpen(null)}>{t('templates.close')}</button>
            </div>
          </div>
          {open.text === null ? (
            <p className="muted">{t('templates.binary')}</p>
          ) : (
            <CodeEditor key={open.path} path={open.path} value={open.text} language={languageOf(open.path)} readOnly={!writable}
              onChange={(text) => setOpen((o) => (o ? { ...o, text } : o))} />
          )}
        </div>
      )}
    </div>
  );
}

export default function TemplatePage() {
  const { name } = useParams();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { can } = useSession();
  const template = useOne<Template>('templates', name);
  const { replace, remove } = useResourceMutations('templates');
  if (!template.data) return <Loading when={template.isLoading}><ErrorBox error={template.error} /></Loading>;
  const data = template.data;
  const src = sourceOf(data);
  return (
    <>
      <div className="row between">
        <h1>
          {data.metadata.name} <span className="badge">{src.type}</span>
        </h1>
        {can(P.TEMPLATE_WRITE) && (
          <ConfirmButton message={t('common.confirmDelete', { name: data.metadata.name })}
            onConfirm={() => remove.mutate(data.metadata.name, { onSuccess: () => navigate('/templates') })}>
            {t('common.delete')}
          </ConfirmButton>
        )}
      </div>
      <ErrorBox error={remove.error} />
      {data.spec.storage ? (
        <>
          <h2>{t('templates.files')} <span className="muted">({data.spec.storage.path})</span></h2>
          <FileBrowser storagePath={data.spec.storage.path} />
        </>
      ) : (
        <p className="muted">{t('templates.external', { source: src.detail })}</p>
      )}
      <h2>{t('services.definition')}</h2>
      <ResourceEditor key={data.metadata.resourceVersion} resource={data} readOnly={!can(P.TEMPLATE_WRITE)} saving={replace.isPending}
        error={replace.error} onSave={(r) => replace.mutate({ ...r, metadata: { ...r.metadata, resourceVersion: data.metadata.resourceVersion } })} />
    </>
  );
}
