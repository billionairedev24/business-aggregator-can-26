import { authUrl, createPasskey, getPasskey, PasskeyError } from '@northline/auth-kit';

/**
 * Step-up before paying (S-51, the S-62 follow-up). A sign-in with a phone code only must confirm a second factor at
 * northline-auth, which answers with a 5-minute, one-time proof the api takes as `X-Step-Up`:
 *   - `required`: the account has a passkey or an authenticator app → confirm with it;
 *   - `enrol`: it has neither → add a passkey now (northline-auth issues the proof with it).
 * Dev mode (`VITE_NL_DEV_STEP_UP=1`, with NL_DEV_USER and no northline-auth): the proof is `dev` (api local/test only).
 */
export type StepUpFailure = 'cancelled' | 'unsupported' | 'rejected' | 'locked' | 'signed_out' | 'wrong_code';
export class StepUpFailed extends Error {
  constructor(readonly reason: StepUpFailure) { super(reason); this.name = 'StepUpFailed'; }
}

const dev = () => import.meta.env.VITE_NL_DEV_STEP_UP === '1';

async function authPost(path: string, body?: unknown): Promise<unknown> {
  const res = await fetch(authUrl(path), {
    method: 'POST', credentials: 'include',
    headers: { accept: 'application/json', ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data: unknown = text ? JSON.parse(text) : undefined;
  if (res.ok) return data;
  if (res.status === 422) throw new StepUpFailed('wrong_code');
  if (res.status === 401) throw new StepUpFailed('signed_out');
  if (res.status === 429 || res.status === 423) throw new StepUpFailed('locked');
  throw new StepUpFailed('rejected');
}

const proofOf = (data: unknown) => {
  const proof = data && typeof data === 'object' && 'proof' in data ? String((data as { proof: unknown }).proof) : '';
  if (!proof) throw new StepUpFailed('rejected');
  return proof;
};

async function withPasskey<T>(run: () => Promise<T>): Promise<T> {
  try { return await run(); } catch (e) { if (e instanceof PasskeyError) throw new StepUpFailed(e.reason); throw e; }
}

/** The account's passkey: options → navigator.credentials.get() → proof. */
export async function stepUpWithPasskey(): Promise<string> {
  if (dev()) return 'dev';
  const options = await authPost('/api/auth/step-up/passkey/options') as Record<string, unknown>;
  const credential = await withPasskey(() => getPasskey(options));
  return proofOf(await authPost('/api/auth/step-up/passkey', { credential }));
}

/** The authenticator app's 6-digit code. */
export async function stepUpWithCode(code: string): Promise<string> {
  if (dev()) return 'dev';
  return proofOf(await authPost('/api/auth/step-up/totp', { code: code.trim() }));
}

/** No second factor yet: create a passkey on this device (it becomes the account's) → proof. */
export async function enrolPasskey(): Promise<string> {
  if (dev()) return 'dev';
  const options = await authPost('/api/auth/step-up/enrol/passkey/options') as Record<string, unknown>;
  const credential = await withPasskey(() => createPasskey(options));
  return proofOf(await authPost('/api/auth/step-up/enrol/passkey', { credential, label: 'Passkey' }));
}
