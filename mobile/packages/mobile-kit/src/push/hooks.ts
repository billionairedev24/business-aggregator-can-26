import type { ApiClient } from '../api/client';

/**
 * The hook point for push notifications (S-102 builds them: device registration api, APNs/FCM sender, deep links).
 *
 * The apps call {@link PushHooks} at the two moments a device registration changes owner — after a sign-in completes
 * and before a sign-out revokes the tokens — and never talk to a push SDK themselves. Until S-102 installs a
 * {@link PushRegistrar} with {@link setPushRegistrar}, the hooks do nothing. A registrar must never throw into the
 * sign-in or sign-out (it is called best effort, errors are swallowed) and must not ask for the notification
 * permission on its own: the app asks at a moment the design chooses.
 */
export interface PushRegistrar {
  /** A person signed in on this device: register its push token for them (signed api calls through `api`). */
  signedIn(api: ApiClient): Promise<void>;
  /** The person is signing out: remove this device's registration while the tokens still work. */
  signingOut(api: ApiClient): Promise<void>;
}

let registrar: PushRegistrar | null = null;

/** S-102: installs the app's registrar (once, at start-up). `null` removes it (tests). */
export function setPushRegistrar(r: PushRegistrar | null): void {
  registrar = r;
}

/** What the apps' sign-in and sign-out code call. Best effort: a push failure never blocks either. */
export const PushHooks = {
  async signedIn(api: ApiClient): Promise<void> {
    try {
      await registrar?.signedIn(api);
    } catch {
      // registration is retried by the registrar at the next start; never fail a sign-in for it
    }
  },
  async signingOut(api: ApiClient): Promise<void> {
    try {
      await registrar?.signingOut(api);
    } catch {
      // offline or refused: the server drops registrations of ended sign-ins (S-102)
    }
  },
  get installed(): boolean {
    return registrar !== null;
  },
};
