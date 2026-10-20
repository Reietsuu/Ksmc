import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useList, type Resource, type Task, type TaskSpec } from '../api/resources';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';

/** Formulaire de task : champs courants ; le reste de la spec est conservé (éditable en YAML). */
export function TaskForm({ task, creating, onSubmit, saving }: {
  task: Task;
  creating?: boolean;
  onSubmit: (t: Task) => void;
  saving?: boolean;
}) {
  const { t } = useTranslation();
  const { can } = useSession();
  const software = useList<Resource<{ environment?: string }>>('software', {}, can(P.SOFTWARE_READ));
  const s = task.spec;
  const [name, setName] = useState(task.metadata.name);
  const [environment, setEnvironment] = useState(s.environment ?? 'server');
  const [softwareName, setSoftwareName] = useState(s.software?.name ?? '');
  const [version, setVersion] = useState(s.software?.version ?? 'latest');
  const [memory, setMemory] = useState(String(s.memoryMb ?? 1024));
  const [min, setMin] = useState(String(s.minServiceCount ?? 1));
  const [max, setMax] = useState(s.maxServiceCount === undefined ? '' : String(s.maxServiceCount));
  const [templates, setTemplates] = useState((s.templates ?? []).map((x) => x.name).join(', '));
  const [maintenance, setMaintenance] = useState(!!s.maintenance);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const names = templates.split(',').map((x) => x.trim()).filter(Boolean);
    const previous = new Map((s.templates ?? []).map((x) => [x.name, x]));
    const spec: TaskSpec = {
      ...s,
      environment: environment as TaskSpec['environment'],
      software: { ...s.software, name: softwareName.trim(), version: version.trim() || undefined },
      memoryMb: Number(memory),
      minServiceCount: Number(min),
      maintenance,
      templates: names.map((n) => previous.get(n) ?? { name: n }),
    };
    if (max.trim()) spec.maxServiceCount = Number(max);
    else delete spec.maxServiceCount;
    if (!names.length) delete spec.templates;
    onSubmit({ ...task, metadata: { ...task.metadata, name: name.trim() }, spec });
  };

  const choices = (software.data?.items ?? []).filter((x) => !x.spec.environment || x.spec.environment === environment);
  return (
    <form className="stack" onSubmit={submit} aria-label={t('tasks.form')}>
      <div className="form-grid">
        <label>
          {t('common.name')}
          <input name="name" value={name} onChange={(e) => setName(e.target.value)} required disabled={!creating}
            pattern="[a-z0-9]([-a-z0-9]*[a-z0-9])?" />
        </label>
        <label>
          {t('tasks.environment')}
          <select name="environment" value={environment} onChange={(e) => setEnvironment(e.target.value as TaskSpec['environment'])}>
            <option value="server">server</option>
            <option value="proxy">proxy</option>
          </select>
        </label>
        <label>
          {t('tasks.software')}
          <input name="software" list="software-choices" value={softwareName} onChange={(e) => setSoftwareName(e.target.value)} required />
          <datalist id="software-choices">
            {choices.map((x) => (
              <option key={x.metadata.name} value={x.metadata.name} />
            ))}
          </datalist>
        </label>
        <label>
          {t('tasks.version')}
          <input name="version" value={version} onChange={(e) => setVersion(e.target.value)} />
        </label>
        <label>
          {t('tasks.memory')}
          <input name="memoryMb" type="number" min={128} step={128} value={memory} onChange={(e) => setMemory(e.target.value)} required />
        </label>
        <label>
          {t('tasks.min')}
          <input name="minServiceCount" type="number" min={0} value={min} onChange={(e) => setMin(e.target.value)} required />
        </label>
        <label>
          {t('tasks.max')}
          <input name="maxServiceCount" type="number" min={0} value={max} onChange={(e) => setMax(e.target.value)} />
        </label>
        <label>
          {t('tasks.templates')}
          <input name="templates" value={templates} onChange={(e) => setTemplates(e.target.value)} placeholder="lobby-default, plugins" />
        </label>
      </div>
      <label className="inline">
        <input type="checkbox" checked={maintenance} onChange={(e) => setMaintenance(e.target.checked)} /> {t('tasks.maintenance')}
      </label>
      <div className="row">
        <button className="primary" type="submit" disabled={saving}>
          {creating ? t('common.create') : t('common.save')}
        </button>
      </div>
    </form>
  );
}
