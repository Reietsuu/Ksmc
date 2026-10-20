import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { useEvents } from '../events/EventStream';
import { applyTheme, initialTheme, type Theme } from '../theme/theme';

/** Page de module déclarée par GET /api/v1/ui/modules. */
export interface ModulePage {
  module: string;
  title: string;
  icon?: string;
  bundle: string;
}

const ITEMS: { to: string; key: string; permission?: string }[] = [
  { to: '/', key: 'nav.overview' },
  { to: '/tasks', key: 'nav.tasks', permission: P.TASK_READ },
  { to: '/groups', key: 'nav.groups', permission: P.GROUP_READ },
  { to: '/services', key: 'nav.services', permission: P.SERVICE_READ },
  { to: '/templates', key: 'nav.templates', permission: P.TEMPLATE_READ },
  { to: '/players', key: 'nav.players', permission: P.PLAYER_READ },
  { to: '/software', key: 'nav.software', permission: P.SOFTWARE_READ },
  { to: '/modules', key: 'nav.modules', permission: P.MODULE_READ },
  { to: '/accounts', key: 'nav.accounts' },
  { to: '/audit', key: 'nav.audit', permission: P.AUDIT_READ },
];

export function useModulePages() {
  const { api } = useSession();
  return useQuery({
    queryKey: ['ui-modules'],
    queryFn: () => api.json<ModulePage[]>('/api/v1/ui/modules'),
    staleTime: 60_000,
  });
}

export function Layout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { me, can, logout } = useSession();
  const { connected } = useEvents();
  const [open, setOpen] = useState(false);
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const location = useLocation();
  const pages = useModulePages();

  useEffect(() => setOpen(false), [location.pathname]);
  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    setTheme(next);
  };

  return (
    <div className="layout">
      <header className="topbar">
        <strong>Ksmc</strong>
        <button aria-label={t('nav.menu')} aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          ☰
        </button>
      </header>
      <nav className={open ? 'nav open' : 'nav'} aria-label={t('nav.menu')}>
        <div className="brand">Ksmc</div>
        {ITEMS.filter((i) => can(i.permission)).map((i) => (
          <NavLink key={i.to} to={i.to} end={i.to === '/'}>
            {t(i.key)}
          </NavLink>
        ))}
        {pages.data && pages.data.length > 0 && <div className="section">{t('nav.modulePages')}</div>}
        {pages.data?.map((p) => (
          <NavLink key={p.module} to={`/ui/${p.module}`}>
            {p.title}
          </NavLink>
        ))}
        <div className="spacer" />
        <div className="user">
          <span>
            {me?.name} · <span title={t(connected ? 'events.connected' : 'events.disconnected')}>{connected ? '●' : '○'}</span>
          </span>
          <div className="row">
            <button onClick={toggleTheme}>{t(theme === 'dark' ? 'theme.light' : 'theme.dark')}</button>
            <button onClick={logout}>{t('auth.logout')}</button>
          </div>
        </div>
      </nav>
      <main className="main">{children}</main>
    </div>
  );
}
