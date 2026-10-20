import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

export function ErrorBox({ error }: { error: unknown }) {
  const { t } = useTranslation();
  if (!error) return null;
  return (
    <p className="error" role="alert">
      {t('common.error', { message: (error as Error).message ?? String(error) })}
    </p>
  );
}

export function Loading({ when, children }: { when: boolean; children: ReactNode }) {
  const { t } = useTranslation();
  return <>{when ? <p className="muted">{t('common.loading')}</p> : children}</>;
}

const PHASE_STYLE: Record<string, string> = {
  Running: 'ok',
  Starting: 'warn',
  Preparing: 'warn',
  Pending: 'warn',
  Stopping: 'warn',
  Failed: 'danger',
  Stopped: '',
};

export function PhaseBadge({ phase }: { phase?: string }) {
  const { t } = useTranslation();
  if (!phase) return <span className="badge">—</span>;
  return <span className={`badge ${PHASE_STYLE[phase] ?? ''}`}>{t(`phase.${phase}`, phase)}</span>;
}

/** Bouton qui demande confirmation avant d'agir. */
export function ConfirmButton({ message, onConfirm, children, className = 'danger', disabled }: {
  message: string;
  onConfirm: () => void;
  children: ReactNode;
  className?: string;
  disabled?: boolean;
}) {
  return (
    <button className={className} disabled={disabled} onClick={() => window.confirm(message) && onConfirm()}>
      {children}
    </button>
  );
}
