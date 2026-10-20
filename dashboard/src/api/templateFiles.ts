import type { ApiClient } from './client';
import { toError } from './client';

export interface TemplateFile {
  path: string;
  size: number;
  modified: string;
}

const enc = (p: string) => p.split('/').map(encodeURIComponent).join('/');

/** Fichiers d'un template storage (chemin du Template Store, ex. lobby/default). */
export function templateFiles(api: ApiClient, storagePath: string) {
  const base = `/api/v1/templates/${enc(storagePath)}`;
  const check = async (r: Response) => {
    if (!r.ok) throw await toError(r);
    return r;
  };
  return {
    list: () => api.json<{ files: TemplateFile[] }>(`${base}/files`).then((r) => r.files),
    read: (file: string) => api.fetch(`${base}/files/${enc(file)}`).then(check).then((r) => r.arrayBuffer()),
    write: (file: string, body: BodyInit) =>
      api.fetch(`${base}/files/${enc(file)}`, { method: 'PUT', body, headers: { 'Content-Type': 'application/octet-stream' } }).then(check),
    remove: (file: string) => api.fetch(`${base}/files/${enc(file)}`, { method: 'DELETE' }).then(check),
    archive: () => api.fetch(`${base}/archive`).then(check).then((r) => r.blob()),
    uploadArchive: (body: Blob, merge = true) =>
      api.fetch(`${base}/archive${merge ? '?mode=merge' : ''}`, {
        method: 'PUT',
        body,
        headers: { 'Content-Type': 'application/octet-stream' },
      }).then(check),
  };
}

const TEXT_EXT = /\.(txt|properties|ya?ml|json|toml|cfg|conf|ini|xml|md|sh|js|mcmeta|sk|csv|log|lang|env)$/i;
const BINARY_EXT = /\.(jar|zip|gz|tar|png|jpe?g|gif|dat|mca|nbt|db|sqlite|class|so|dll|exe|ico)$/i;

/** Texte éditable : extension connue, sinon contenu sans octet nul ni UTF-8 invalide. */
export function isText(path: string, bytes?: ArrayBuffer): boolean {
  if (BINARY_EXT.test(path)) return false;
  if (TEXT_EXT.test(path)) return true;
  if (!bytes) return false;
  const view = new Uint8Array(bytes.slice(0, 8192));
  if (view.includes(0)) return false;
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(view);
    return true;
  } catch {
    return false;
  }
}

export function languageOf(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() ?? '';
  return (
    { yml: 'yaml', yaml: 'yaml', json: 'json', mcmeta: 'json', xml: 'xml', md: 'markdown', sh: 'shell', js: 'javascript', properties: 'ini', toml: 'ini', cfg: 'ini', conf: 'ini', ini: 'ini' } as Record<string, string>
  )[ext] ?? 'plaintext';
}

/** Propose un fichier téléchargé au navigateur. */
export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
