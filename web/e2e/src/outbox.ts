import { expect } from '@playwright/test';
import { env } from './env';

/**
 * The local fakes' outboxes (local profile only — the routes don't exist anywhere else):
 * northline-auth's SMS codes at `GET /api/auth/dev/outbox`, the api's emails and texts at `GET /api/v1/dev/outbox`.
 */
interface Code { to: string; channel: string; code: string; at: string }
export interface Message { kind: string; to: string; subject: string; text: string; tag: string | null; at: string }

/** The outbox stamps with the server's clock; on one machine they agree, this covers the rounding. */
const SKEW_MS = 2_000;

function localOnly(what: string): never {
  throw new Error(`${what} needs the local fakes' outbox — it exists only under the local profile (make e2e)`);
}

async function json<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url} answered ${res.status}`);
  return (await res.json()) as T;
}

/** The newest code northline-auth "texted" to `phone` after `since` (waits for it: the request is asynchronous). */
export async function smsCode(phone: string, since: Date): Promise<string> {
  if (env.mode !== 'local') localOnly('reading an SMS code');
  let code: string | undefined;
  await expect.poll(async () => {
    const { items } = await json<{ items: Code[] }>(`${env.urls.auth}/api/auth/dev/outbox?to=${encodeURIComponent(phone)}`);
    code = items.find(c => new Date(c.at).getTime() >= since.getTime() - SKEW_MS)?.code;
    return code;
  }, { message: `an SMS code for ${phone}`, timeout: 15_000 }).toBeTruthy();
  return code!;
}

/** The newest email from the api to `address` with the template `tag` (e.g. `payout-sent`), sent after `since`. */
export async function email(address: string, tag: string, since: Date): Promise<Message> {
  if (env.mode !== 'local' || !env.urls.api) localOnly('reading an email');
  let found: Message | undefined;
  await expect.poll(async () => {
    const { items } = await json<{ items: Message[] }>(`${env.urls.api}/api/v1/dev/outbox?to=${encodeURIComponent(address)}`);
    found = items.find(m => m.kind === 'email' && m.tag === tag && new Date(m.at).getTime() >= since.getTime() - SKEW_MS);
    return found?.subject;
  }, { message: `a ${tag} email to ${address}`, timeout: 30_000 }).toBeTruthy();
  return found!;
}
