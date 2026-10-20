import fr from './locales/fr.json';

// Sources du dashboard (hors tests), chargées en texte par Vite.
const sources = import.meta.glob(['../**/*.ts', '../**/*.tsx', '!../**/*.test.*', '!../**/*.d.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

function has(key: string): boolean {
  let node: unknown = fr;
  for (const part of key.split('.')) {
    if (!node || typeof node !== 'object' || !(part in node)) return false;
    node = (node as Record<string, unknown>)[part];
  }
  return typeof node === 'string';
}

describe('traductions', () => {
  it('chaque clé littérale utilisée existe en français', () => {
    const missing: string[] = [];
    let checked = 0;
    for (const [file, src] of Object.entries(sources)) {
      for (const m of src.matchAll(/\bt\(\s*'([a-zA-Z0-9_.]+)'|key: '([a-z]+\.[a-zA-Z.]+)'/g)) {
        const key = m[1] ?? m[2];
        checked++;
        if (!has(key)) missing.push(`${key} (${file})`);
      }
    }
    expect(checked).toBeGreaterThan(100);
    expect(missing).toEqual([]);
  });
});
