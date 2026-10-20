import { expect, test } from '@playwright/test';

import { ask, signIn, signedInPage } from './helpers';

test.describe('chat', () => {
  test('a question is answered from the knowledge base with a cited article', async ({ page }) => {
    await signIn(page, 'alice');
    await ask(page, 'How do I connect to the VPN?');

    const answer = page.locator('app-answer').last();
    await expect(answer).toContainText('Connecting to the corporate VPN');
    // The [n] marker becomes a citation linking to the article, listed again under the sources.
    const citation = answer.locator('a.cite').first();
    await expect(citation).toBeVisible();
    await expect(citation).toHaveAttribute('href', /\/helpdesk\/knowledge\?article=\d+/);
    await expect(answer.getByRole('link', { name: 'Connecting to the corporate VPN' }).first()).toBeVisible();
  });

  test('a ticket request waits for a reviewer, bob approves it and alice gets her ticket', async ({ browser }) => {
    const alice = await signedInPage(browser, 'alice');
    await ask(alice, 'My laptop screen is cracked, please open a ticket.');
    await expect(alice.getByText('Waiting for a reviewer')).toBeVisible();

    const bob = await signedInPage(browser, 'bob', '/approvals');
    const request = bob.locator('app-approval-card').filter({ hasText: 'create_ticket' }).filter({ hasText: 'Cracked laptop screen' }).last();
    await expect(request).toBeVisible();
    await request.getByRole('button', { name: 'Approve' }).click();
    await expect(bob.getByText(/Approved; the run is now/)).toBeVisible();

    // Alice's chat updates by itself (live events) with the agent's confirmation.
    const confirmation = alice.locator('app-answer').filter({ hasText: /Your ticket #\d+ has been created/ }).first();
    await expect(confirmation).toBeVisible();
    const ticketId = (await confirmation.textContent())!.match(/#(\d+)/)![1];

    await alice.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Tickets' }).click();
    await expect(alice.getByRole('list', { name: 'Tickets' })).toContainText(`#${ticketId}`);
    await expect(alice.getByRole('list', { name: 'Tickets' })).toContainText('Cracked laptop screen');

    await alice.context().close();
    await bob.context().close();
  });
});
