import { join } from 'node:path';
import { env, isLocal } from '../src/env';
import { expect, test } from '../src/fixtures';
import { emailCode } from '../src/outbox';
import { NEW_PASSWORD } from '../src/signIn';
import { freshTotp } from '../src/totp';

/**
 * Journey 6 (S-154) — a business account from nothing: Create account in the Studio (name, email, password) → the code
 * from the verification email (the local outbox; the email itself is in Mailpit) → the authenticator app, required for a
 * business (QR code / key, a code from it) → the ten backup codes, saved → onboarding. Then Forgot password by email
 * and a sign-in with the new password, which still asks for the authenticator.
 */
test('a new business account: email + password, email code, authenticator, backup codes, forgot password', async ({ browser }) => {
  test.skip(!isLocal, 'reads the email code from the local outbox (docs/runbooks/e2e.md § What target mode skips)');
  const context = await browser.newContext({ locale: 'en-CA', timezoneId: env.data.market.timeZone });
  const page = await context.newPage();
  const address = `owner.e2e+${env.runId}@example.test`;
  const totpState = join(env.stateDir, `totp-new-${env.runId}.json`);
  let secret = '';

  await test.step('create account → email code', async () => {
    await page.goto(`${env.urls.studio}/register?next=%2Fonboarding%3Ftype%3Dprovider`);
    await page.getByLabel('First name').fill('Nadia');
    await page.getByLabel('Last name').fill(`E2e ${env.runId}`);
    await page.getByLabel('Email', { exact: true }).fill(address);
    await page.getByLabel('Password', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirm password', { exact: true }).fill(NEW_PASSWORD);
    await page.locator('label.nl-check:has(input[name=terms]) .nl-box').click();
    const sent = new Date();
    await page.getByRole('button', { name: 'Create account', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
    await page.getByLabel('Code from the email').fill(await emailCode(address, sent));
    await page.getByRole('button', { name: 'Confirm email' }).click();
  });

  await test.step('second factor (required) → backup codes', async () => {
    await expect(page.getByRole('heading', { name: 'Set up your second factor' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Skip for now' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    secret = (await page.locator('.nl-acct-key').innerText()).replace(/\s/g, '');
    await page.getByLabel('6-digit code from the app').fill(await freshTotp(secret, totpState));
    await page.getByRole('button', { name: 'Confirm code' }).click();
    await expect(page.getByRole('heading', { name: 'Save your backup codes' })).toBeVisible();
    await expect(page.locator('.nl-acct-codes li')).toHaveCount(10);
    await page.locator('label.nl-check:has(input[name=saved]) .nl-box').click();
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await expect(page.getByText('Account created.')).toBeVisible();
    await page.getByRole('button', { name: 'Continue to business onboarding' }).click();
    await expect(page).toHaveURL(/\/onboarding/);
  });

  await test.step('forgot password → new password → sign in still asks for the authenticator', async () => {
    const fresh = await browser.newContext({ locale: 'en-CA' });
    const other = await fresh.newPage();
    await other.goto(`${env.urls.studio}/forgot-password`);
    await other.getByLabel('Email', { exact: true }).fill(address);
    const sent = new Date();
    await other.getByRole('button', { name: 'Send code' }).click();
    await other.getByLabel('Code from the email').fill(await emailCode(address, sent));
    await other.getByLabel('New password').fill(`${NEW_PASSWORD} again`);
    await other.getByLabel('Confirm password').fill(`${NEW_PASSWORD} again`);
    await other.getByRole('button', { name: 'Set new password' }).click();
    await other.getByRole('button', { name: 'Sign in', exact: true }).click();
    await other.getByLabel('Password', { exact: true }).fill(`${NEW_PASSWORD} again`);
    await other.getByRole('button', { name: 'Sign in', exact: true }).click();
    await other.getByLabel('6-digit code').fill(await freshTotp(secret, totpState));
    await other.getByRole('button', { name: 'Verify code' }).click();
    await expect(other.getByText('Signed in.')).toBeVisible();
    await fresh.close();
  });
  await context.close();
});
