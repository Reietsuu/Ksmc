import Editor, { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import type { CodeEditorProps } from './CodeEditor';

// Monaco empaqueté avec le dashboard (pas de CDN : le controller peut être hors ligne).
self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

export default function MonacoEditor({ value, onChange, language = 'plaintext', readOnly, path }: CodeEditorProps) {
  const dark = document.documentElement.dataset.theme === 'dark';
  return (
    <Editor
      value={value}
      path={path}
      language={language}
      theme={dark ? 'vs-dark' : 'light'}
      onChange={(v) => onChange?.(v ?? '')}
      options={{ readOnly, minimap: { enabled: false }, fontSize: 13, scrollBeyondLastLine: false, automaticLayout: true }}
    />
  );
}
