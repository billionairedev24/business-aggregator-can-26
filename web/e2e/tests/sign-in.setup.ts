import { mkdirSync, writeFileSync } from 'node:fs';
import { expect, test as setup } from '@playwright/test';
import { env, isLocal } from '../src/env';
import { consumerOptions, consumerPasskeyPath, consumerProfilePath, statePath } from '../src/fixtures';
import { VirtualAuthenticator } from '../src/passkey';
import { consoleSignIn, consumerPasskeySignIn, consumerRegister, newConsumer, studioSignIn } from '../src/signIn';

/**
 * Journey 1 — sign-in, once per persona per run, through the real pages and northline-auth (no dev auth, no token
 * shortcut). The signed-in states are what the journeys start from (tests/*.spec.ts use them per actor).
 */
setup.beforeAll(() => mkdirSync(env.stateDir, { recursive: true }));

setup('business owner signs in to the Studio with an authenticator app', async ({ page }) => {
  const owner = env.personas.owner;
  await studioSignIn(page, owner);
  await expect(page.getByRole('button', { name: 'Account menu' })).toBeVisible();
  const session = await (await page.request.get(`${env.urls.studio}/bff/session`)).json() as { acr: string; user: { email: string } };
  expect(session.acr).toBe('mfa');
  await page.context().storageState({ path: statePath('owner') });
});

setup('Northline staff sign in to the console with a second factor', async ({ page }) => {
  await consoleSignIn(page, env.personas.staff);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await page.context().storageState({ path: statePath('staff') });
});

setup(isLocal ? 'a new customer signs up with a phone code and a passkey' : 'the customer signs in with their passkey', async ({ browser }) => {
  const context = await browser.newContext(consumerOptions());
  const page = await context.newPage();
  const authenticator = await VirtualAuthenticator.attach(page);
  if (isLocal) {
    const person = newConsumer();
    await consumerRegister(page, person, authenticator);
    writeFileSync(consumerProfilePath(), JSON.stringify(person));
  } else {
    const passkey = env.personas.consumer?.passkey;
    if (!passkey) throw new Error('E2E_CONSUMER_PASSKEY is not set (docs/runbooks/e2e.md § Target an environment)');
    await authenticator.add(passkey);
    await consumerPasskeySignIn(page);
  }
  writeFileSync(consumerPasskeyPath(), JSON.stringify((await authenticator.credentials())[0]));
  await context.storageState({ path: statePath('consumer') });
  await context.close();
});
