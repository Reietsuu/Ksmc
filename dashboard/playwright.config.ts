import { defineConfig, devices } from '@playwright/test';

// Parcours navigateur du dashboard sur un controller réel (lancé par DashboardE2eTest, cluster kind).
export default defineConfig({
  testDir: 'e2e',
  outputDir: 'test-results',
  timeout: 120_000,
  expect: { timeout: 20_000 },
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.KSMC_URL ?? 'http://localhost:8080',
    locale: 'fr-FR',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
