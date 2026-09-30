import { authUrl } from '../../lib/auth-server';
import { ValidationError, type FieldError } from '../../lib/http';
import { getPasskey, PasskeyError } from '@northline/auth-kit';

/**
 * Step-up for money moves ("Payouts always require a fresh authentication", design 02): confirm with a passkey — or
 * the authenticator app — at northline-auth, which returns a 5-minute one-time proof the api accepts as X-Step-Up.
 *
 * Dev mode (`VITE_NL_DEV_STEP_UP=1`, used with NL_DEV_USER when northline-auth isn't running): the proof is `dev`,
 * which only the api's local/test profiles accept.
 */
export type StepUpFailure = 'cancelled' | 'unsupported' | 'rejected' | 'locked' | 'signed_out';
export class StepUpFailed extends Error {
  constructor(readonly reason: StepUpFailure) { super(reason); this.name = 'StepUpFailed'; }
}

export const devStepUp = () => import.meta.env.VITE_NL_DEV_STEP_UP === '1';

async function authPost(path: string, body?: unknown): Promise<unknown> {
  const res = await fetch(authUrl(path), {
    method: 'POST', credentials: 'include',
    headers: { accept: 'application/json', ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data: unknown = text ? JSON.parse(text) : undefined;
  if (res.ok) return data;
  if (res.status === 422 && data && typeof data === 'object' && 'errors' in data) throw new ValidationError((data as { errors: FieldError[] }).errors);
  if (res.status === 401) throw new StepUpFailed('signed_out');
  if (res.status === 429) throw new StepUpFailed('locked');
  throw new StepUpFailed('rejected');
}

const proofOf = (data: unknown) => {
  const proof = data && typeof data === 'object' && 'proof' in data ? String((data as { proof: unknown }).proof) : '';
  if (!proof) throw new StepUpFailed('rejected');
  return proof;
};

/** Passkey: options → navigator.credentials.get() → proof. */
export async function stepUpWithPasskey(): Promise<string> {
  if (devStepUp()) return 'dev';
  const options = await authPost('/api/auth/step-up/passkey/options') as Record<string, unknown>;
  let credential: Record<string, unknown>;
  try {
    credential = await getPasskey(options);
  } catch (e) {
    if (e instanceof PasskeyError) throw new StepUpFailed(e.reason);
    throw e;
  }
  try {
    return proofOf(await authPost('/api/auth/step-up/passkey', { credential }));
  } catch (e) {
    if (e instanceof ValidationError) throw new StepUpFailed('rejected');
    throw e;
  }
}

/** Authenticator app code (6 digits). A wrong code throws ValidationError (field `code`). */
export async function stepUpWithCode(code: string): Promise<string> {
  if (devStepUp()) return 'dev';
  return proofOf(await authPost('/api/auth/step-up/totp', { code }));
}
