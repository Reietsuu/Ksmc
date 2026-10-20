import { useTranslation } from 'react-i18next';
import { newResource, useResourceMutations, type Resource } from '../api/resources';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { CrudPage } from '../components/CrudPage';

type Module = Resource<
  { enabled?: boolean; source?: Record<string, unknown>; config?: Record<string, unknown> },
  { phase?: string; loadedVersion?: string; message?: string; moduleId?: string }
>;

const PHASE_STYLE: Record<string, string> = { Enabled: 'ok', Failed: 'danger', Disabled: '', Pending: 'warn' };

export default function ModulesPage() {
  const { t } = useTranslation();
  const { can } = useSession();
  const { patch } = useResourceMutations('modules');
  return (
    <CrudPage<Module>
      kind="modules"
      title={t('nav.modules')}
      newLabel={t('modules.install')}
      writePermission={P.MODULE_WRITE}
      blank={newResource('modules', 'mon-module', { source: { url: 'https://exemple.org/module.jar', sha256: '' }, config: {} })}
      columns={[
        {
          label: t('modules.state'),
          value: (m) => (
            <span className={`badge ${PHASE_STYLE[m.status?.phase ?? ''] ?? 'warn'}`} title={m.status?.message}>
              {t(`modules.phase.${m.status?.phase ?? 'Pending'}`, m.status?.phase ?? 'Pending')}
            </span>
          ),
        },
        { label: t('tasks.version'), value: (m) => m.status?.loadedVersion ?? '—' },
        { label: t('services.message'), value: (m) => m.status?.message ?? '' },
      ]}
      actions={(m) =>
        can(P.MODULE_WRITE) && (
          <button onClick={() => patch.mutate({ name: m.metadata.name, body: { spec: { enabled: m.spec.enabled === false } } })}>
            {m.spec.enabled === false ? t('modules.enable') : t('modules.disable')}
          </button>
        )
      }
    />
  );
}
