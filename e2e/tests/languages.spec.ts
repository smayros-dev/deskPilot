import { expect, test } from '@playwright/test';

import { nav, signIn } from './helpers';

test.describe('languages', () => {
  test('switching to French and Arabic translates the UI, Arabic is right-to-left', async ({ page }) => {
    await signIn(page, 'alice', '/settings');
    const language = page.locator('.user-menu select');

    await language.selectOption('fr');
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
    await expect(page.getByRole('navigation').getByRole('link', { name: 'Paramètres' })).toBeVisible();
    await expect(page.getByRole('note').filter({ hasText: 'Lecture seule' })).toBeVisible();

    await language.selectOption('ar');
    await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.getByRole('navigation').getByRole('link', { name: 'الإعدادات' })).toBeVisible();

    // The choice survives a reload (kept in the browser), then English comes back on request.
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await language.selectOption('en');
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
    await expect(nav(page).getByRole('link', { name: 'Settings' })).toBeVisible();
  });
});
