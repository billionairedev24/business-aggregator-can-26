import { ApiError, NetworkError, apiErrorOf } from '@northline/mobile-kit';

import { config } from '../config';
import { services } from '../services';

/**
 * Paying for a booking with a sign-in that had no second factor (the S-51 rule the api applies, `403
 * step_up_required`): northline-auth checks the authenticator app's code in the app's auth session (the platform
 * cookie store, as Journey A's sign-in) and answers a 5-minute, one-time proof the api takes as `X-Step-Up`. Passkeys
 * need a native module the app doesn't have (S-98), so the app asks for the authenticator code.
 *
 * @returns the proof; throws the auth server's {@link ApiError} (422 = the code didn't match, 401 = no auth session on
 *     this phone) or {@link NetworkError}
 */
export async function stepUpProof(code: string): Promise<string> {
  let res: Response;
  try {
    res = await services().fetch(`${config.authIssuer}/api/auth/step-up/totp`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'Accept-Language': services().language() },
      body: JSON.stringify({ code: code.trim() }),
    });
  } catch (e) {
    throw new NetworkError(e);
  }
  if (!res.ok) throw await apiErrorOf(res);
  const body = (await res.json().catch(() => ({}))) as { proof?: unknown };
  if (typeof body.proof !== 'string' || !body.proof) throw new ApiError(res.status, 'no_proof', undefined);
  return body.proof;
}
