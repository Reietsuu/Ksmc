import { expect, test, type Page } from '@playwright/test';

// Données préparées par DashboardE2eTest : service e2ed-proxy (Velocity, Running), template storage
// e2ed-tpl contenant server.properties, module hello actif. Les captures vont dans e2e/screenshots (doc).
const user = process.env.KSMC_USER ?? 'admin';
const password = process.env.KSMC_PASSWORD ?? 'admin';
const shots = 'e2e/screenshots';

async function login(page: Page) {
  await page.goto('/');
  await page.getByLabel('Utilisateur').fill(user);
  await page.getByLabel('Mot de passe').fill(password);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page.getByRole('heading', { name: "Vue d'ensemble" })).toBeVisible();
}

test.describe.configure({ mode: 'serial' });

test('connexion et vue d’ensemble', async ({ page }) => {
  await login(page);
  await expect(page.getByTestId('services-total')).not.toHaveText('0');
  await expect(page.getByTitle('Temps réel connecté')).toBeVisible();
  await page.screenshot({ path: `${shots}/overview.png`, fullPage: true });
});

test('création d’une task par formulaire', async ({ page }) => {
  await login(page);
  await page.getByRole('navigation').getByRole('link', { name: 'Tasks' }).click();
  await page.getByRole('button', { name: 'Nouvelle task' }).click();
  const form = page.getByRole('form', { name: 'Formulaire de task' });
  await form.getByLabel('Nom').fill('e2ed-web');
  await form.getByLabel('Logiciel').fill('paper');
  await form.getByLabel('Version').fill('latest');
  await form.getByLabel('Mémoire (Mo)').fill('1024');
  await form.getByLabel('Minimum de services').fill('0');
  await form.getByRole('button', { name: 'Créer' }).click();
  await expect(page).toHaveURL(/\/tasks\/e2ed-web$/);
  await expect(page.getByRole('heading', { name: /e2ed-web/ })).toBeVisible();
  await page.goto('/tasks');
  await expect(page.getByRole('link', { name: 'e2ed-web' })).toBeVisible();
  await page.screenshot({ path: `${shots}/tasks.png`, fullPage: true });
});

test('console web d’un service', async ({ page }) => {
  await login(page);
  await page.goto('/services/e2ed-proxy');
  await expect(page.getByText('Connecté', { exact: true })).toBeVisible();
  const input = page.getByLabel('Commande');
  await expect(input).toBeEnabled();
  await input.fill('glist');
  await input.press('Enter');
  const terminal = page.getByTestId('xterm');
  await expect(terminal).toContainText('> glist');
  await expect(terminal).toContainText(/player/i);
  await page.screenshot({ path: `${shots}/console.png`, fullPage: true });
});

test('édition de server.properties dans un template', async ({ page }) => {
  await login(page);
  await page.goto('/templates/e2ed-tpl');
  await page.getByRole('button', { name: 'server.properties', exact: true }).click();
  const card = page.getByTestId('file-editor');
  const editor = card.locator('.monaco-editor');
  await expect(editor).toBeVisible();
  await expect(editor).toContainText('motd');
  await editor.click();
  await page.keyboard.press('ControlOrMeta+End');
  await page.keyboard.type('\nmax-players=42');
  await card.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(page.getByRole('status')).toHaveText('Enregistré');
  await page.screenshot({ path: `${shots}/template-editor.png`, fullPage: true });
});

test('page du module hello', async ({ page }) => {
  await login(page);
  const link = page.getByRole('navigation').getByRole('link', { name: 'Hello' });
  await expect(link).toBeVisible();
  await link.click();
  await expect(page.locator('#hello-message')).not.toHaveText('…');
  await expect(page.locator('#hello-message')).toContainText(`Salut, ${user} !`);
  await page.screenshot({ path: `${shots}/module-hello.png`, fullPage: true });
});

test('mobile 375 px et thème sombre', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 375, height: 800 }, colorScheme: 'dark', locale: 'fr-FR' });
  const page = await context.newPage();
  await login(page);
  const noHorizontalScroll = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  expect(await noHorizontalScroll()).toBe(true);
  const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(background).toBe('rgb(18, 21, 26)');
  await page.screenshot({ path: `${shots}/mobile-dark.png`, fullPage: true });
  await page.getByRole('button', { name: 'Menu' }).click();
  await page.getByRole('navigation').getByRole('link', { name: 'Services' }).click();
  await expect(page.getByRole('heading', { name: 'Services' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'e2ed-proxy' })).toBeVisible();
  expect(await noHorizontalScroll()).toBe(true);
  await page.screenshot({ path: `${shots}/mobile-services.png`, fullPage: true });
  await context.close();
});
