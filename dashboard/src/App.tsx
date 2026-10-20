import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api as defaultApi, ApiError, type ApiClient } from './api/client';
import { SessionProvider, useSession } from './auth/session';
import { EventStreamProvider } from './events/EventStream';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { PasswordPage } from './pages/PasswordPage';

const OverviewPage = lazy(() => import('./pages/OverviewPage'));
const TasksPage = lazy(() => import('./pages/TasksPage'));
const TaskPage = lazy(() => import('./pages/TaskPage'));
const GroupsPage = lazy(() => import('./pages/GroupsPage'));
const GroupPage = lazy(() => import('./pages/GroupPage'));
const ServicesPage = lazy(() => import('./pages/ServicesPage'));
const ServicePage = lazy(() => import('./pages/ServicePage'));
const TemplatesPage = lazy(() => import('./pages/TemplatesPage'));
const TemplatePage = lazy(() => import('./pages/TemplatePage'));
const PlayersPage = lazy(() => import('./pages/PlayersPage'));
const SoftwarePage = lazy(() => import('./pages/SoftwarePage'));
const ModulesPage = lazy(() => import('./pages/ModulesPage'));
const ModuleUiPage = lazy(() => import('./pages/ModuleUiPage'));
const AccountsPage = lazy(() => import('./pages/AccountsPage'));
const AuditPage = lazy(() => import('./pages/AuditPage'));

export function newQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5_000,
        retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      },
    },
  });
}

export function Providers({ api = defaultApi, client, children }: { api?: ApiClient; client?: QueryClient; children: ReactNode }) {
  return (
    <QueryClientProvider client={client ?? newQueryClient()}>
      <SessionProvider api={api}>
        <EventStreamProvider>{children}</EventStreamProvider>
      </SessionProvider>
    </QueryClientProvider>
  );
}

/** Redirige vers /login sans session, vers /password si le mot de passe doit être changé. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { session, me, loading } = useSession();
  const location = useLocation();
  const { t } = useTranslation();
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (loading || !me) return <p className="main muted">{t('common.loading')}</p>;
  if (me.mustChangePassword && location.pathname !== '/password') return <Navigate to="/password" replace />;
  return <>{children}</>;
}

export function App() {
  const { t } = useTranslation();
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/password"
        element={
          <RequireAuth>
            <PasswordPage />
          </RequireAuth>
        }
      />
      <Route
        path="*"
        element={
          <RequireAuth>
            <Layout>
              <Suspense fallback={<p className="muted">{t('common.loading')}</p>}>
                <Routes>
                  <Route path="/" element={<OverviewPage />} />
                  <Route path="/tasks" element={<TasksPage />} />
                  <Route path="/tasks/:name" element={<TaskPage />} />
                  <Route path="/groups" element={<GroupsPage />} />
                  <Route path="/groups/:name" element={<GroupPage />} />
                  <Route path="/services" element={<ServicesPage />} />
                  <Route path="/services/:name" element={<ServicePage />} />
                  <Route path="/templates" element={<TemplatesPage />} />
                  <Route path="/templates/:name" element={<TemplatePage />} />
                  <Route path="/players" element={<PlayersPage />} />
                  <Route path="/software" element={<SoftwarePage />} />
                  <Route path="/modules" element={<ModulesPage />} />
                  <Route path="/ui/:module" element={<ModuleUiPage />} />
                  <Route path="/accounts" element={<AccountsPage />} />
                  <Route path="/audit" element={<AuditPage />} />
                  <Route path="*" element={<p>{t('common.notFound')}</p>} />
                </Routes>
              </Suspense>
            </Layout>
          </RequireAuth>
        }
      />
    </Routes>
  );
}
