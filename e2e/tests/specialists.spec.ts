import { expect, test } from '@playwright/test';

import { nav, signIn } from './helpers';

test.describe('specialists', () => {
  test('admin adds a keyword to a specialist and the route preview follows at once', async ({ page }) => {
    // Confirmation dialogs (restore the built-in specialists) are accepted.
    page.on('dialog', (dialog) => dialog.accept());
    await signIn(page, 'admin', '/specialists');
    await expect(nav(page).getByRole('link', { name: 'Specialists' })).toBeVisible();
    const tryRouting = async (message: string) => {
      await page.getByRole('textbox', { name: 'Message to route' }).fill(message);
      await page.getByRole('button', { name: 'Route', exact: true }).click();
    };
    const chosen = page.getByTestId('preview-specialist');

    // A word no specialist knows goes to the general agent.
    await tryRouting('My zorbulator is smoking');
    await expect(chosen).toHaveText('General IT');

    const hardware = page.locator('table.list tr[data-id=hardware]');
    await hardware.getByRole('button', { name: 'Edit' }).click();
    const keywords = page.locator('textarea[formControlName=keywords]');
    await keywords.fill(`${await keywords.inputValue()}, zorbulator`);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.locator('.editor')).toHaveCount(0);
    await expect(hardware).toContainText('zorbulator');

    await tryRouting('My zorbulator is smoking');
    await expect(chosen).toHaveText('Hardware');
    await expect(page.locator('.result')).toContainText('by keywords');
    await expect(page.locator('table.scores tr.chosen')).toContainText('zorbulator');

    // Back to the built-in definitions, so the test can run again on the same database.
    await page.getByRole('button', { name: 'Restore built-in' }).click();
    await expect(hardware).not.toContainText('zorbulator');
    await tryRouting('My zorbulator is smoking');
    await expect(chosen).toHaveText('General IT');
  });

  test('alice cannot open the Specialists page', async ({ page }) => {
    await signIn(page, 'alice');

    await expect(nav(page).getByRole('link', { name: 'Specialists' })).toHaveCount(0);
    await page.goto('/specialists');
    await expect(page).toHaveURL(/\/chat$/);
  });
});
