import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useSession } from '../auth/session';
import { P } from '../auth/permissions';

const XTerm = lazy(() => import('./XTerm'));
const MAX_LINES = 5000;

type Message =
  | { type: 'log'; line: string }
  | { type: 'command'; command: string; result: string }
  | { type: 'error'; message: string }
  | { type: 'end'; message: string };

/**
 * Console d'un service : WebSocket /api/v1/services/<nom>/console (logs en direct), saisie de commandes
 * si l'utilisateur a ksmc.service.command.
 */
export function Console({ service }: { service: string }) {
  const { t } = useTranslation();
  const { api, can } = useSession();
  const [lines, setLines] = useState<string[]>([]);
  const [connected, setConnected] = useState(false);
  const [command, setCommand] = useState('');
  const ws = useRef<WebSocket | null>(null);
  const canType = can(P.SERVICE_COMMAND);

  useEffect(() => {
    setLines([]);
    const socket = new WebSocket(api.wsUrl(`/api/v1/services/${encodeURIComponent(service)}/console`, { lines: '200' }));
    ws.current = socket;
    const push = (...l: string[]) => setLines((prev) => [...prev, ...l].slice(-MAX_LINES));
    socket.onopen = () => setConnected(true);
    socket.onclose = () => setConnected(false);
    socket.onmessage = (m) => {
      let msg: Message;
      try {
        msg = JSON.parse(m.data as string);
      } catch {
        return;
      }
      if (msg.type === 'log') push(msg.line);
      else if (msg.type === 'command') push(`\x1b[36m> ${msg.command}\x1b[0m`, ...(msg.result ? [msg.result] : []));
      else push(`\x1b[31m${msg.message}\x1b[0m`);
    };
    return () => socket.close();
  }, [api, service]);

  const send = (e: FormEvent) => {
    e.preventDefault();
    const c = command.trim();
    if (!c || ws.current?.readyState !== 1) return;
    ws.current.send(c);
    setCommand('');
  };

  return (
    <div className="stack">
      <div className="console">
        <Suspense fallback={null}>
          <XTerm lines={lines} />
        </Suspense>
      </div>
      <form className="row" onSubmit={send}>
        <input
          aria-label={t('console.command')}
          placeholder={canType ? t('console.placeholder') : t('console.readOnly')}
          value={command}
          onChange={(e) => setCommand(e.target.value)}
          disabled={!canType || !connected}
          style={{ flex: 1, fontFamily: 'monospace' }}
        />
        <button type="submit" disabled={!canType || !connected}>
          {t('console.send')}
        </button>
      </form>
      <span className="muted">{connected ? t('console.connected') : t('console.disconnected')}</span>
    </div>
  );
}
