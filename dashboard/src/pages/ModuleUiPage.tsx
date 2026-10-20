import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { ApiClient } from '../api/client';
import { useSession } from '../auth/session';
import { useModulePages } from '../components/Layout';
import { ErrorBox, Loading } from '../components/ui';

/** API passée au bundle du module : fetch authentifié relatif à /api/v1/modules/<id>/. */
export interface ModuleApi {
  module: string;
  fetch: (path: string, init?: RequestInit) => Promise<Response>;
  /** fetch authentifié sur un chemin absolu de l'API (/api/v1/...). */
  fetchApi: (path: string, init?: RequestInit) => Promise<Response>;
}

export function moduleApi(api: ApiClient, module: string): ModuleApi {
  return {
    module,
    fetch: (path, init) => api.fetch(`/api/v1/modules/${encodeURIComponent(module)}/${path.replace(/^\/+/, '')}`, init),
    fetchApi: (path, init) => api.fetch(path, init),
  };
}

type Bundle = { mount: (el: HTMLElement, api: ModuleApi) => void | (() => void); unmount?: (el: HTMLElement) => void };

/** Importe le bundle ES du module (sans reconstruire le dashboard) ; remplaçable dans les tests. */
export const loader = { load: (url: string): Promise<Bundle> => import(/* @vite-ignore */ url) };

/** Page déclarée par un module : charge /modules/<id>/ui.js et appelle mount(élément, api). */
export default function ModuleUiPage() {
  const { module } = useParams();
  const { t } = useTranslation();
  const { api } = useSession();
  const pages = useModulePages();
  const el = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<unknown>(null);
  const page = pages.data?.find((p) => p.module === module);

  useEffect(() => {
    if (!page || !el.current) return;
    const target = el.current;
    let cleanup: (() => void) | undefined;
    let cancelled = false;
    setError(null);
    loader
      .load(page.bundle)
      .then((b) => {
        if (cancelled) return;
        const r = b.mount(target, moduleApi(api, page.module));
        cleanup = typeof r === 'function' ? r : b.unmount ? () => b.unmount!(target) : undefined;
      })
      .catch((e) => !cancelled && setError(e));
    return () => {
      cancelled = true;
      cleanup?.();
      target.innerHTML = '';
    };
  }, [page, api]);

  if (!page) return <Loading when={pages.isLoading}><p>{t('modules.noPage', { module })}</p></Loading>;
  return (
    <>
      <ErrorBox error={error} />
      <div ref={el} className="module-page" data-module={page.module} />
    </>
  );
}
