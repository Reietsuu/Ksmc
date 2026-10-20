import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { ConfirmButton, ErrorBox, Loading } from '../components/ui';

interface Token {
  id: string;
  name: string;
  user: string;
  createdAt?: string;
  expiresAt?: string | null;
}
interface Account {
  name: string;
  roles: string[];
  mustChangePassword: boolean;
  createdAt?: string;
}

function Tokens() {
  const { t } = useTranslation();
  const { api } = useSession();
  const qc = useQueryClient();
  const tokens = useQuery({ queryKey: ['tokens'], queryFn: () => api.json<Token[]>('/api/v1/auth/tokens') });
  const [name, setName] = useState('');
  const [days, setDays] = useState('');
  const [created, setCreated] = useState<string | null>(null);
  const done = () => qc.invalidateQueries({ queryKey: ['tokens'] });
  const create = useMutation({
    mutationFn: () =>
      api.json<{ token: string }>('/api/v1/auth/tokens', {
        method: 'POST',
        body: JSON.stringify(days ? { name, expiresInDays: Number(days) } : { name }),
      }),
    onSuccess: (r) => {
      setCreated(r.token);
      setName('');
      setDays('');
      done();
    },
  });
  const revoke = useMutation({
    mutationFn: (id: string) => api.json(`/api/v1/auth/tokens/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    onSuccess: done,
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };
  return (
    <>
      <h2>{t('accounts.tokens')}</h2>
      <form className="row" onSubmit={submit} style={{ marginBottom: '1rem' }}>
        <input aria-label={t('accounts.tokenName')} placeholder={t('accounts.tokenName')} value={name} onChange={(e) => setName(e.target.value)} required style={{ maxWidth: '16rem' }} />
        <input aria-label={t('accounts.expiresInDays')} placeholder={t('accounts.expiresInDays')} type="number" min={1} value={days}
          onChange={(e) => setDays(e.target.value)} style={{ maxWidth: '10rem' }} />
        <button className="primary" type="submit" disabled={create.isPending}>{t('accounts.createToken')}</button>
      </form>
      {created && (
        <div className="card" role="status">
          <p>{t('accounts.tokenOnce')}</p>
          <code style={{ overflowWrap: 'anywhere' }} data-testid="new-token">{created}</code>
        </div>
      )}
      <ErrorBox error={tokens.error ?? create.error ?? revoke.error} />
      <Loading when={tokens.isLoading}>
        <table className="responsive">
          <tbody>
            {tokens.data?.map((tk) => (
              <tr key={tk.id}>
                <td data-label={t('common.name')}>{tk.name}</td>
                <td data-label={t('accounts.expires')}>{tk.expiresAt ? new Date(tk.expiresAt).toLocaleDateString() : t('accounts.never')}</td>
                <td data-label={t('common.actions')}>
                  <ConfirmButton message={t('accounts.confirmRevoke', { name: tk.name })} onConfirm={() => revoke.mutate(tk.id)}>
                    {t('accounts.revoke')}
                  </ConfirmButton>
                </td>
              </tr>
            ))}
            {tokens.data?.length === 0 && (
              <tr>
                <td className="muted">{t('common.none')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </Loading>
    </>
  );
}

function Accounts() {
  const { t } = useTranslation();
  const { api, me } = useSession();
  const qc = useQueryClient();
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: () => api.json<Account[]>('/api/v1/accounts') });
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api.json<Record<string, string[]>>('/api/v1/roles') });
  const [form, setForm] = useState({ name: '', password: '', role: 'viewer' });
  const done = () => qc.invalidateQueries({ queryKey: ['accounts'] });
  const call = useMutation({
    mutationFn: ({ path, method, body }: { path: string; method: string; body?: unknown }) =>
      api.json(path, { method, body: body === undefined ? undefined : JSON.stringify(body) }),
    onSuccess: done,
  });
  const user = (name: string) => `/api/v1/accounts/${encodeURIComponent(name)}`;
  const roleNames = Object.keys(roles.data ?? { admin: [], operator: [], viewer: [] });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    call.mutate(
      { path: '/api/v1/accounts', method: 'POST', body: { name: form.name, password: form.password, roles: [form.role] } },
      { onSuccess: () => setForm({ name: '', password: '', role: 'viewer' }) },
    );
  };
  const resetPassword = (name: string) => {
    const password = window.prompt(t('accounts.newPasswordPrompt', { name }));
    if (password) call.mutate({ path: `${user(name)}/password`, method: 'PUT', body: { password } });
  };
  return (
    <>
      <h2>{t('accounts.users')}</h2>
      <form className="form-grid" onSubmit={submit} style={{ marginBottom: '1rem', alignItems: 'end' }}>
        <label>
          {t('auth.username')}
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
        </label>
        <label>
          {t('auth.password')}
          <input type="password" autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required />
        </label>
        <label>
          {t('accounts.role')}
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
            {roleNames.map((r) => <option key={r}>{r}</option>)}
          </select>
        </label>
        <button className="primary" type="submit">{t('accounts.createUser')}</button>
      </form>
      <ErrorBox error={accounts.error ?? call.error} />
      <Loading when={accounts.isLoading}>
        <table className="responsive">
          <thead>
            <tr>
              <th>{t('common.name')}</th>
              <th>{t('accounts.role')}</th>
              <th>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {accounts.data?.map((a) => (
              <tr key={a.name}>
                <td data-label={t('common.name')}>
                  {a.name} {a.mustChangePassword && <span className="badge warn">{t('accounts.mustChange')}</span>}
                </td>
                <td data-label={t('accounts.role')}>
                  <select aria-label={`${t('accounts.role')} ${a.name}`} value={a.roles[0] ?? ''} disabled={a.name === me?.name}
                    onChange={(e) => call.mutate({ path: `${user(a.name)}/roles`, method: 'PUT', body: { roles: [e.target.value] } })}>
                    {[...new Set([...roleNames, ...a.roles])].map((r) => <option key={r}>{r}</option>)}
                  </select>
                </td>
                <td data-label={t('common.actions')}>
                  <div className="row">
                    <button onClick={() => resetPassword(a.name)}>{t('accounts.resetPassword')}</button>
                    {a.name !== me?.name && (
                      <ConfirmButton message={t('common.confirmDelete', { name: a.name })} onConfirm={() => call.mutate({ path: user(a.name), method: 'DELETE' })}>
                        {t('common.delete')}
                      </ConfirmButton>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Loading>
    </>
  );
}

export default function AccountsPage() {
  const { t } = useTranslation();
  const { can } = useSession();
  return (
    <>
      <h1>{t('nav.accounts')}</h1>
      <Tokens />
      {can(P.ACCOUNT_ADMIN) && <Accounts />}
    </>
  );
}
