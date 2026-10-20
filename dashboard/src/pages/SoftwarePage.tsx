import { useTranslation } from 'react-i18next';
import { newResource, type Resource } from '../api/resources';
import { P } from '../auth/permissions';
import { CrudPage } from '../components/CrudPage';

type Software = Resource<{ environment?: string; family?: string; resolver?: { type: string } }>;

export default function SoftwarePage() {
  const { t } = useTranslation();
  return (
    <CrudPage<Software>
      kind="software"
      title={t('nav.software')}
      newLabel={t('software.new')}
      writePermission={P.SOFTWARE_WRITE}
      blank={newResource('software', 'mon-logiciel', { environment: 'server', family: 'paper', resolver: { type: 'papermc', params: { project: 'paper' } } })}
      columns={[
        { label: t('tasks.environment'), value: (s) => s.spec.environment ?? '—' },
        { label: t('software.family'), value: (s) => s.spec.family ?? '—' },
        { label: t('software.resolver'), value: (s) => s.spec.resolver?.type ?? '—' },
      ]}
    />
  );
}
