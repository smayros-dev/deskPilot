import { Browser, Page, expect } from '@playwright/test';

/** Demo users of backend/keycloak/deskpilot-realm.json: the password is the user name. */
export type DemoUser = 'alice' | 'bob' | 'admin';

/** Opens the app, signs in on Keycloak's page and waits until the app shows the signed-in user. */
export async function signIn(page: Page, user: DemoUser, path = '/chat'): Promise<void> {
  await page.goto(path);
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(user);
  await page.locator('#kc-login').click();
  await expect(page.locator('.user-menu .user')).toBeVisible();
  // Keycloak sends the browser back to the app's start page; then go where the test wants to be.
  if (!page.url().endsWith(path)) {
    await page.goto(path);
  }
}

/** A separate browser context per user, so two people can work at the same time (requester and reviewer). */
export async function signedInPage(browser: Browser, user: DemoUser, path = '/chat'): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, user, path);
  return page;
}

/** Starts a new conversation and sends a message (the reply is checked by the caller). */
export async function ask(page: Page, message: string): Promise<void> {
  await page.getByRole('button', { name: 'New conversation' }).click();
  const input = page.getByRole('textbox', { name: 'Message' });
  await input.fill(message);
  await input.press('Enter');
}

export function nav(page: Page) {
  return page.getByRole('navigation', { name: 'Main' });
}
