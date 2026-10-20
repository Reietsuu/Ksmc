import { useEffect, useRef } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';

/** Terminal xterm.js en lecture : affiche les lignes ajoutées depuis le dernier rendu (liste à ajout seul). */
export default function XTerm({ lines }: { lines: string[] }) {
  const el = useRef<HTMLDivElement>(null);
  const term = useRef<Terminal | null>(null);
  const written = useRef(0);

  useEffect(() => {
    const t = new Terminal({ convertEol: true, disableStdin: true, fontSize: 13, scrollback: 5000, theme: { background: '#111111' } });
    const fit = new FitAddon();
    t.loadAddon(fit);
    t.open(el.current!);
    fit.fit();
    term.current = t;
    written.current = 0;
    const onResize = () => fit.fit();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      t.dispose();
      term.current = null;
    };
  }, []);

  useEffect(() => {
    const t = term.current;
    if (!t) return;
    if (lines.length < written.current) {
      t.clear();
      written.current = 0;
    }
    for (let i = written.current; i < lines.length; i++) t.writeln(lines[i]);
    written.current = lines.length;
  }, [lines]);

  return <div ref={el} style={{ height: '100%' }} data-testid="xterm" />;
}
