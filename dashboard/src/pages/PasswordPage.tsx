import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSession } from '../auth/session';

/** Changement de mot de passe (obligatoire au premier login de l'admin). */
export function PasswordPage() {
  const { t } = useTranslation();
  const { api } = useSession();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const s = await api.json<import('../api/client').Session>('/api/v1/auth/password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword: current, newPassword: next }),
      });
      if (s && s.accessToken) api.setSession(s);
      await qc.invalidateQueries({ queryKey: ['me'] });
      navigate('/', { replace: true });
    } catch (err) {
      setError((err as Error).message);
    }
  };

  return (
    <div className="login">
      <form className="card stack" onSubmit={submit}>
        <h1>{t('auth.changePassword')}</h1>
        <p className="muted">{t('auth.mustChange')}</p>
        <label>
          {t('auth.currentPassword')}
          <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
        </label>
        <label>
          {t('auth.newPassword')}
          <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" type="submit">{t('common.save')}</button>
      </form>
    </div>
  );
}
