import { existsSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { test as base, expect, type Browser, type BrowserContextOptions, type Page, type TestInfo } from '@playwright/test';
import { env, type PasskeyCredential } from './env';
import { VirtualAuthenticator } from './passkey';

export type Actor = 'owner' | 'staff' | 'consumer';

/** Signed-in browser state of each persona, written by the setup project (tests/sign-in.setup.ts). */
export const statePath = (actor: Actor) => join(env.stateDir, `${actor}.json`);
export const consumerPasskeyPath = () => join(env.stateDir, 'consumer-passkey.json');
export const consumerProfilePath = () => join(env.stateDir, 'consumer.profile.json');

/** What every actor's browser has in common: the market's time zone, Canadian English, a desktop window. */
export function contextOptions(): BrowserContextOptions {
  return { viewport: { width: 1280, height: 900 }, locale: 'en-CA', timezoneId: env.data.market.timeZone };
}

/** The consumer's browser also knows where it is (the header asks once per visit; quotes and delivery need it). */
export function consumerOptions(): BrowserContextOptions {
  return { ...contextOptions(), geolocation: env.data.market.geolocation, permissions: ['geolocation'] };
}

/**
 * One browser context per actor (cross-user journeys never share cookies), signed in from the setup's state, with a
 * video per actor. On failure each actor's last screen is attached and its video kept; traces are recorded by the
 * runner for every context (`trace: 'retain-on-failure'`).
 */
async function actorPage(browser: Browser, testInfo: TestInfo, actor: Actor, use: (page: Page) => Promise<void>) {
  const videoDir = testInfo.outputPath(`video-${actor}`);
  const context = await browser.newContext({
    ...(actor === 'consumer' ? consumerOptions() : contextOptions()),
    storageState: statePath(actor),
    recordVideo: { dir: videoDir, size: { width: 1280, height: 900 } },
  });
  const page = await context.newPage();
  if (actor === 'consumer' && existsSync(consumerPasskeyPath())) {
    const authenticator = await VirtualAuthenticator.attach(page);
    await authenticator.add(JSON.parse(readFileSync(consumerPasskeyPath(), 'utf8')) as PasskeyCredential);
  }
  await use(page);
  const failed = testInfo.status !== testInfo.expectedStatus;
  if (failed) {
    await testInfo.attach(`${actor} — last screen`, { body: await page.screenshot({ fullPage: true }).catch(() => Buffer.alloc(0)), contentType: 'image/png' });
  }
  const video = page.video();
  await context.close();
  if (failed && video) await testInfo.attach(`${actor} — video`, { path: await video.path(), contentType: 'video/webm' });
  else rmSync(videoDir, { recursive: true, force: true });
}

export const test = base.extend<{ owner: Page; staff: Page; consumer: Page }>({
  owner: async ({ browser }, use, testInfo) => actorPage(browser, testInfo, 'owner', use),
  staff: async ({ browser }, use, testInfo) => actorPage(browser, testInfo, 'staff', use),
  consumer: async ({ browser }, use, testInfo) => actorPage(browser, testInfo, 'consumer', use),
});

export { expect };

/**
 * Names that are unique per run (titles, descriptions), so a rerun against the same database never finds the last
 * one's — and per repeat (`--repeat-each`) and retry within a run.
 */
export function unique(label: string): string {
  const { repeatEachIndex, retry } = base.info();
  return `${label} ${env.runId}${repeatEachIndex || retry ? `-${repeatEachIndex}.${retry}` : ''}`;
}
