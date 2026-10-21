// S-159: the 6-digit code northline-auth emails to a new account (S-154: email + password), read back without a
// person — from Mailpit's API (the local compose inbox, or the OVH demo inbox of `OVH_DEMO_MAILPIT=true`, behind its
// basic auth), else, locally, from auth's dev outbox (`/api/auth/dev/outbox?to=<email>`, local profile only). No
// Twilio: since S-154 a demo account needs only an address whose mail can be read.
import { CookieJar, request } from './http.mjs';

const CODE = /\b(\d{6})\b/;
/** The subject of the email an address that already has an account gets instead of a code (S-154: no enumeration). */
const ACCOUNT_EXISTS = /already have a .* account|avez déjà un compte/i;

export class AccountExists extends Error {}

/**
 * The newest code for `email` in Mailpit search results (`GET /api/v1/search?query=to:…`) sent at or after `since`
 * (ms, two seconds' slack for clocks). Throws {@link AccountExists} when the newest message is the "you already have
 * an account" notice; null when nothing is there yet.
 */
export function codeFromMailpit(body, since) {
  const messages = [...(body?.messages ?? [])]
    .filter(m => Date.parse(m.Created) >= since - 2000)
    .sort((a, b) => Date.parse(b.Created) - Date.parse(a.Created));
  for (const m of messages) {
    if (ACCOUNT_EXISTS.test(m.Subject ?? '')) throw new AccountExists(m.Subject);
    const code = CODE.exec(m.Subject ?? '')?.[1] ?? CODE.exec(m.Snippet ?? '')?.[1];
    if (code) return code;
  }
  return null;
}

/** The newest code auth's dev outbox recorded for `email` at or after `since` (local profile only). */
export function codeFromOutbox(body, since) {
  return (body?.items ?? []).find(c => Date.parse(c.at) >= since - 2000)?.code ?? null;
}

/**
 * Waits up to `waitMs` for the code.
 * @param mailpit `{ url, user, password }` or null (no inbox to read)
 * @param outbox northline-auth's origin when its dev outbox can be read (local), else null
 */
export async function emailCode(email, since, { mailpit, outbox, waitMs = 20_000, sleep = ms => new Promise(r => setTimeout(r, ms)) }) {
  const jar = new CookieJar();
  for (const deadline = Date.now() + waitMs; Date.now() < deadline; await sleep(500)) {
    if (mailpit) {
      const headers = mailpit.password ? { authorization: `Basic ${Buffer.from(`${mailpit.user ?? 'demo'}:${mailpit.password}`).toString('base64')}` } : {};
      const { body } = await request(jar, 'GET', `${mailpit.url.replace(/\/$/, '')}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}&limit=20`, { headers, anyStatus: true });
      const code = codeFromMailpit(body, since);
      if (code) return code;
    }
    if (outbox) {
      const { body } = await request(jar, 'GET', `${outbox}/api/auth/dev/outbox?to=${encodeURIComponent(email)}`, { anyStatus: true });
      const code = codeFromOutbox(body, since);
      if (code) return code;
    }
    if (!mailpit && !outbox) return null;
  }
  return null;
}
