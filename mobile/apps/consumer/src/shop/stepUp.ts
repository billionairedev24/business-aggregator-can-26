import { apiErrorOf, NetworkError } from '@northline/mobile-kit';

import { config } from '../config';
import { services } from '../services';

export type StepUpFailure = 'wrong_code' | 'locked' | 'elsewhere';
export class StepUpFailed extends Error {
  constructor(readonly reason: StepUpFailure) {
    super(reason);
    this.name = 'StepUpFailed';
  }
}

/**
 * Paying with a sign-in that had no second factor (S-51): northline-auth checks the authenticator app's code in the
 * app's auth session (the platform cookie store, as Journey A's sign-in, S-98) and answers a 5-minute, one-time proof
 * that the api takes as `X-Step-Up`. Passkeys need a native module the app doesn't have (S-98), so the app asks for the
 * authenticator code only; a sign-in that left no auth session on this phone (the system-browser sign-in, or an
 * expired session) answers 401 → `elsewhere`.
 */
export async function stepUpWithCode(code: string): Promise<string> {
  let res: Response;
  try {
    res = await services().fetch(`${config.authIssuer}/api/auth/step-up/totp`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ code: code.trim() }),
    });
  } catch (e) {
    throw new NetworkError(e);
  }
  if (res.ok) {
    const body = (await res.json().catch(() => ({}))) as { proof?: unknown };
    if (typeof body.proof === 'string' && body.proof) return body.proof;
    throw new StepUpFailed('elsewhere');
  }
  if (res.status === 422 || res.status === 400) throw new StepUpFailed('wrong_code');
  if (res.status === 429 || res.status === 423) throw new StepUpFailed('locked');
  if (res.status === 401 || res.status === 403) throw new StepUpFailed('elsewhere');
  throw await apiErrorOf(res);
}
