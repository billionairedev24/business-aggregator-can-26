import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, type Page } from '@playwright/test';
import { env, type Persona } from './env';
import { hydrated } from './hydration';
import { smsCode } from './outbox';
import { VirtualAuthenticator } from './passkey';
import { freshTotp } from './totp';

const totpState = () => join(env.stateDir, 'totp-steps.json');
const backupState = () => join(env.stateDir, 'backup-codes-used.json');

/** A code for the persona's authenticator app that northline-auth hasn't seen yet. */
export function totpFor(persona: Persona): Promise<string> {
  if (!persona.totpSecret) throw new Error(`${persona.identifier} has no authenticator key`);
  return freshTotp(persona.totpSecret, totpState());
}

function usedBackupCodes(): string[] {
  try { return JSON.parse(readFileSync(backupState(), 'utf8')) as string[]; } catch { return []; }
}

function markBackupCodeUsed(code: string) {
  mkdirSync(env.stateDir, { recursive: true });
  writeFileSync(backupState(), JSON.stringify([...usedBackupCodes(), code]));
}

/**
 * The second-factor step of the Studio and console sign-in pages (same component copy): the authenticator app when
 * the persona has a key, else the next unused backup code.
 */
async function secondFactor(page: Page, persona: Persona) {
  if (persona.totpSecret) {
    await page.getByRole('radio', { name: /Authenticator app/ }).click();
    await page.getByLabel('6-digit code').fill(await totpFor(persona));
    await page.getByRole('button', { name: 'Verify code' }).click();
    return;
  }
  await page.getByRole('radio', { name: /Backup code/ }).click();
  for (const code of (persona.backupCodes ?? []).filter(c => !usedBackupCodes().includes(c))) {
    await page.getByLabel('Backup code').fill(code);
    await page.getByRole('button', { name: 'Verify backup code' }).click();
    markBackupCodeUsed(code); // single use whether it worked now or was spent before
    const signedIn = page.getByText('Signed in.');
    await expect(signedIn.or(page.getByRole('alert'))).toBeVisible();
    if (await signedIn.isVisible()) return;
  }
  throw new Error(`${persona.identifier}: no unused backup code left (give the persona an authenticator key)`);
}

/** Studio sign-in (design 02): identifier → second factor → "Continue" → the Studio. */
export async function studioSignIn(page: Page, persona: Persona) {
  await page.goto(`${env.urls.studio}/sign-in`);
  await page.getByLabel('Email or mobile').fill(persona.identifier);
  await page.getByRole('button', { name: 'Continue' }).click();
  await secondFactor(page, persona);
  await expect(page.getByText('Signed in.')).toBeVisible();
  await page.getByRole('button', { name: /^Continue/ }).click();
  await expect(page).toHaveURL(/\/(b\/|onboarding)/);
}

/** Console sign-in (S-90): staff with a second factor. */
export async function consoleSignIn(page: Page, persona: Persona) {
  await page.goto(`${env.urls.console}/sign-in`);
  await page.getByLabel('Work email or mobile').fill(persona.identifier);
  await page.getByRole('button', { name: 'Continue' }).click();
  await secondFactor(page, persona);
  await expect(page.getByText('Signed in.')).toBeVisible();
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
}

export interface NewConsumer { firstName: string; lastName: string; phone: string; email: string }

/** A new customer each run: unique phone and email, so reruns against the same database never collide. */
export function newConsumer(firstName: 'Erin' | 'Kai' = 'Erin'): NewConsumer {
  // the run's minute and second (mmss) under an exchange per role: unique for every run within an hour
  const exchange = firstName === 'Erin' ? 555 : 556;
  const handle = firstName.toLowerCase();
  return { firstName, lastName: `E2e ${env.runId}`, phone: `+1 587 ${exchange} ${env.runId.slice(-4)}`, email: `${handle}.e2e+${env.runId}@example.test` };
}

/**
 * Consumer sign-up (design 06): name, mobile, email, terms → the 6-digit code from the local SMS outbox → a passkey
 * (virtual authenticator) → "Account created". Then the consumer-bff session. Local only: a deployed environment
 * texts a real phone.
 */
export async function consumerRegister(page: Page, person: NewConsumer, authenticator: VirtualAuthenticator) {
  await page.goto(`${env.urls.consumer}/register`, { waitUntil: 'networkidle' });
  await hydrated(page.getByLabel('Full name'));
  await page.getByLabel('Full name').fill(`${person.firstName} ${person.lastName}`);
  await page.getByLabel('Mobile number').fill(person.phone);
  await page.getByLabel('Email (receipts)').fill(person.email);
  await page.locator('label.nl-check:has(input[name=terms]) .nl-box').click();
  const sent = new Date();
  await page.getByRole('button', { name: 'Send code' }).click();
  await page.getByRole('textbox', { name: /6-digit code sent to/ }).fill(await smsCode(person.phone, sent));
  await page.getByRole('button', { name: 'Verify', exact: true }).click();
  await page.getByRole('radio', { name: /Passkey/ }).click();
  await page.getByRole('button', { name: 'Create passkey' }).click();
  await expect(page.getByText('Account created.')).toBeVisible();
  expect(await authenticator.credentials()).toHaveLength(1);
  await consumerSession(page);
}

/** Consumer sign-in with a passkey (consumers need no second factor; a passkey gives acr=mfa for payments). */
export async function consumerPasskeySignIn(page: Page) {
  await page.goto(`${env.urls.consumer}/sign-in`, { waitUntil: 'networkidle' });
  const button = await hydrated(page.getByRole('button', { name: 'Sign in with a passkey' }));
  await button.click();
  await expect(page.getByText('Signed in.')).toBeVisible();
  await consumerSession(page);
}

/** northline-auth already knows the person: the consumer-bff's login completes without a page. */
async function consumerSession(page: Page) {
  await page.goto(`${env.urls.consumer}/bff/login?next=%2F`);
  await expect.poll(async () => (await (await page.request.get(`${env.urls.consumer}/bff/session`)).json() as { user: unknown }).user, { message: 'consumer-bff session' }).toBeTruthy();
}
