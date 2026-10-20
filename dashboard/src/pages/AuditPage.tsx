import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSession } from '../auth/session';
import { ErrorBox, Loading } from '../components/ui';

interface Entry {
  time: string;
  user: string;
  action: string;
  target?: string;
  result: string;
  detail?: string;
}

export default function AuditPage() {
  const { t } = useTranslation();
  const { api } = useSession();
  const [filters, setFilters] = useState({ user: '', action: '', target: '' });
  const params = new URLSearchParams({ limit: '200' });
  Object.entries(filters).forEach(([k, v]) => v && params.set(k, v));
  const audit = useQuery({ queryKey: ['audit', filters], queryFn: () => api.json<Entry[] | { items: Entry[] }>(`/api/v1/audit?${params}`) });
  const items = Array.isArray(audit.data) ? audit.data : audit.data?.items ?? [];
  const field = (k: keyof typeof filters) => (
    <label key={k}>
      {t(`audit.${k}`)}
      <input value={filters[k]} onChange={(e) => setFilters((f) => ({ ...f, [k]: e.target.value }))} />
    </label>
  );
  return (
    <>
      <h1>{t('nav.audit')}</h1>
      <div className="form-grid" style={{ marginBottom: '1rem' }}>
        {field('user')}
        {field('action')}
        {field('target')}
      </div>
      <ErrorBox error={audit.error} />
      <Loading when={audit.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('audit.time')}</th>
              <th>{t('audit.user')}</th>
              <th>{t('audit.action')}</th>
              <th>{t('audit.target')}</th>
              <th>{t('audit.result')}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((e, i) => (
              <tr key={`${e.time}-${i}`}>
                <td data-label={t('audit.time')}>{new Date(e.time).toLocaleString()}</td>
                <td data-label={t('audit.user')}>{e.user}</td>
                <td data-label={t('audit.action')}>{e.action}</td>
                <td data-label={t('audit.target')} title={e.detail}>{e.target ?? ''}</td>
                <td data-label={t('audit.result')}>
                  <span className={`badge ${e.result === 'ok' ? 'ok' : 'danger'}`}>{e.result}</span>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={5} className="muted">{t('common.none')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
    </>
  );
}
