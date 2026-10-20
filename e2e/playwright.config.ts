import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests of the whole stack: Keycloak login, the Angular app, the backend (profile "e2e") and the scripted
 * fake Ollama. The stack must be running; see README.md in this folder.
 *   E2E_BASE_URL  frontend under test (default http://localhost:4300)
 *   PW_CHANNEL    browser channel, e.g. "chrome" to use the installed Chrome instead of Playwright's Chromium
 */
export default defineConfig({
  testDir: './tests',
  // The tests share one backend and database; running them one at a time keeps the approval queue predictable.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4300',
    locale: 'en-US',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], ...(process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {}) },
    },
  ],
});
