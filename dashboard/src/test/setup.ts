import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import '../i18n';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({ matches: false, media: query, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList;
}

// Monaco ne fonctionne pas dans jsdom : zone de texte à la place.
vi.mock('../components/CodeEditor', async () => {
  const { createElement } = await import('react');
  return {
    CodeEditor: (p: { value: string; onChange?: (v: string) => void; readOnly?: boolean }) =>
      createElement('textarea', {
        'aria-label': 'editor',
        value: p.value,
        readOnly: p.readOnly,
        onChange: (e: { target: { value: string } }) => p.onChange?.(e.target.value),
      }),
  };
});

// xterm.js a besoin d'un canvas : rendu texte des lignes à la place.
vi.mock('../components/XTerm', async () => {
  const { createElement } = await import('react');
  return {
    default: ({ lines }: { lines: string[] }) => createElement('pre', { 'data-testid': 'xterm' }, lines.join('\n')),
  };
});
