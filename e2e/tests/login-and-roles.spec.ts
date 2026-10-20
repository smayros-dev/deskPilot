import { expect, test } from '@playwright/test';

import { nav, signIn } from './helpers';

test.describe('sign-in and roles', () => {
  test('alice (user) signs in through Keycloak and sees only the user pages', async ({ page }) => {
    await signIn(page, 'alice');

    await expect(page.locator('.user-menu')).toContainText('Alice');
    await expect(nav(page).getByRole('link', { name: 'Chat' })).toBeVisible();
    await expect(nav(page).getByRole('link', { name: 'Tickets' })).toBeVisible();
    await expect(nav(page).getByRole('link', { name: 'Approvals' })).toHaveCount(0);
    await expect(nav(page).getByRole('link', { name: 'Evaluation' })).toHaveCount(0);
  });

  test('alice cannot open reviewer or admin pages, and settings are read-only', async ({ page }) => {
    await signIn(page, 'alice');

    // Pages behind a role the user lacks send them back to the chat.
    await page.goto('/approvals');
    await expect(page).toHaveURL(/\/chat$/);
    await expect(page.getByRole('heading', { name: 'Approvals' })).toHaveCount(0);
    await page.goto('/evaluation');
    await expect(page).toHaveURL(/\/chat$/);

    await page.goto('/settings');
    await expect(page.getByRole('note').filter({ hasText: 'Read-only' })).toBeVisible();
    await expect(page.locator('#chatModel')).toBeDisabled();
    await expect(page.locator('button[type=submit]')).toHaveCount(0);
  });

  test('bob (reviewer) sees the Approvals queue', async ({ page }) => {
    await signIn(page, 'bob', '/approvals');

    await expect(nav(page).getByRole('link', { name: 'Approvals' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Approvals' })).toBeVisible();
    await expect(nav(page).getByRole('link', { name: 'Evaluation' })).toHaveCount(0);
  });

  test('admin can edit the settings and reach the admin pages', async ({ page }) => {
    await signIn(page, 'admin', '/settings');

    await expect(page.locator('#chatModel')).toBeEnabled();
    await expect(page.locator('#chatModel')).toHaveValue('ollama|qwen3:8b');
    await expect(page.getByRole('note').filter({ hasText: 'Read-only' })).toHaveCount(0);
    await expect(nav(page).getByRole('link', { name: 'Evaluation' })).toBeVisible();
    await expect(nav(page).getByRole('link', { name: 'Audit' })).toBeVisible();
  });
});
