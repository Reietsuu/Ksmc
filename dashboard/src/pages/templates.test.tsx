import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { isText, languageOf } from '../api/templateFiles';
import { me, mockApi, renderApp } from '../test/utils';
import TemplatePage from './TemplatePage';

const tpl = { metadata: { name: 'lobby-default', resourceVersion: '3' }, spec: { storage: { path: 'lobby/default' } } };
const base = '/api/v1/templates/lobby/default';

function renderTemplate(perms = ['*']) {
  const api = mockApi({
    'GET /api/v1/auth/me': me(perms),
    'GET /api/v1/templates/lobby-default': tpl,
    [`GET ${base}/files`]: {
      template: 'lobby/default',
      files: [
        { path: 'server.properties', size: 20, modified: '2026-10-20T00:00:00Z' },
        { path: 'plugins/Hello.jar', size: 4096, modified: '2026-10-20T00:00:00Z' },
      ],
    },
    [`GET ${base}/files/server.properties`]: () => new Response('motd=Bonjour\n'),
    [`GET ${base}/files/plugins/Hello.jar`]: () => new Response(new Uint8Array([80, 75, 3, 4, 0, 0])),
    [`PUT ${base}/files/server.properties`]: () => new Response(null, { status: 204 }),
    [`PUT ${base}/files/plugins/extra.yml`]: () => new Response(null, { status: 201 }),
  });
  renderApp(
    <Routes>
      <Route path="/templates/:name" element={<TemplatePage />} />
    </Routes>,
    { route: '/templates/lobby-default' },
  );
  return api;
}

describe('éditeur de templates', () => {
  it('modifie server.properties et enregistre', async () => {
    const { calls } = renderTemplate();
    await userEvent.click(await screen.findByRole('button', { name: 'server.properties' }));
    const editors = await screen.findAllByLabelText('editor');
    const editor = editors.find((e) => (e as HTMLTextAreaElement).value.startsWith('motd='))!;
    fireEvent.change(editor, { target: { value: 'motd=Salut\n' } });
    const save = screen.getAllByRole('button', { name: 'Enregistrer' })[0];
    await userEvent.click(save);
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')).toMatchObject({ path: `${base}/files/server.properties`, body: 'motd=Salut\n' });
    expect(await screen.findByRole('status')).toHaveTextContent('Enregistré');
  });

  it("n'édite pas les binaires", async () => {
    renderTemplate();
    await userEvent.click(await screen.findByRole('button', { name: 'plugins/Hello.jar' }));
    expect(await screen.findByText(/Fichier binaire/)).toBeInTheDocument();
  });

  it('téléverse dans le dossier cible', async () => {
    const { calls } = renderTemplate();
    await screen.findByRole('button', { name: 'server.properties' });
    await userEvent.type(screen.getByLabelText('Dossier cible'), 'plugins');
    await userEvent.upload(screen.getByTestId('upload'), new File(['a: 1'], 'extra.yml'));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')!.path).toBe(`${base}/files/plugins/extra.yml`);
    expect(await screen.findByRole('status')).toHaveTextContent('1 fichier(s) téléversé(s)');
  });

  it('lecture seule sans ksmc.template.write', async () => {
    renderTemplate(['ksmc.template.read']);
    await screen.findByRole('button', { name: 'server.properties' });
    expect(screen.queryByRole('button', { name: 'Téléverser des fichiers' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Supprimer' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Télécharger (zip)' })).toBeInTheDocument();
  });

  it('détecte texte et langage', () => {
    expect(isText('server.properties')).toBe(true);
    expect(isText('a.jar', new ArrayBuffer(4))).toBe(false);
    expect(isText('eula', new TextEncoder().encode('eula=true').buffer)).toBe(true);
    expect(isText('blob', new Uint8Array([1, 0, 2]).buffer)).toBe(false);
    expect(languageOf('config.yml')).toBe('yaml');
  });
});
