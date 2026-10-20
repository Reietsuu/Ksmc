import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useOne, useResourceMutations } from '../api/resources';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';
import { ResourceEditor } from '../components/ResourceEditor';
import { ConfirmButton, ErrorBox, Loading } from '../components/ui';
import { GroupForm, type Group } from './GroupsPage';

export default function GroupPage() {
  const { name } = useParams();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { can } = useSession();
  const group = useOne<Group>('groups', name);
  const { replace, remove } = useResourceMutations('groups');
  if (!group.data) return <Loading when={group.isLoading}><ErrorBox error={group.error} /></Loading>;
  const data = group.data;
  const save = (g: Group) => replace.mutate({ ...g, metadata: { ...g.metadata, resourceVersion: data.metadata.resourceVersion } });
  return (
    <>
      <div className="row between">
        <h1>{data.metadata.name}</h1>
        {can(P.GROUP_WRITE) && (
          <ConfirmButton message={t('common.confirmDelete', { name: data.metadata.name })}
            onConfirm={() => remove.mutate(data.metadata.name, { onSuccess: () => navigate('/groups') })}>
            {t('common.delete')}
          </ConfirmButton>
        )}
      </div>
      <ErrorBox error={remove.error} />
      <div className="card">
        <ResourceEditor key={data.metadata.resourceVersion} resource={data} readOnly={!can(P.GROUP_WRITE)}
          form={can(P.GROUP_WRITE) ? <GroupForm group={data} onSubmit={save} saving={replace.isPending} /> : undefined}
          onSave={(r) => save(r as Group)} saving={replace.isPending} error={replace.error} />
        {replace.isSuccess && <p className="muted">{t('common.saved')}</p>}
      </div>
    </>
  );
}
