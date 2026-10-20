import { lazy, Suspense } from 'react';

export interface CodeEditorProps {
  value: string;
  onChange?: (value: string) => void;
  language?: string;
  readOnly?: boolean;
  path?: string;
}

// Monaco (plusieurs Mo) n'est chargé qu'à l'ouverture d'un éditeur.
const Monaco = lazy(() => import('./MonacoEditor'));

/** Éditeur de code (Monaco), avec une zone de texte le temps du chargement. */
export function CodeEditor(props: CodeEditorProps) {
  return (
    <div className="editor" data-testid="code-editor">
      <Suspense
        fallback={
          <textarea
            aria-label="editor"
            style={{ width: '100%', height: '100%', fontFamily: 'monospace', border: 'none' }}
            value={props.value}
            readOnly={props.readOnly}
            onChange={(e) => props.onChange?.(e.target.value)}
          />
        }
      >
        <Monaco {...props} />
      </Suspense>
    </div>
  );
}
