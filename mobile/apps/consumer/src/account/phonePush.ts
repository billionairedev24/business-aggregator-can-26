import { useSyncExternalStore } from 'react';

import type { PushPermission, PushRegistration } from '@northline/mobile-kit';

/**
 * Push on this phone, for Notifications' "This phone" row (S-102's parts). Account › Notifications is where the
 * design lets the person turn notifications on — the moment that explains why, never at launch — so this screen asks
 * the system for the permission (`PushRegistration.enable()`: the prompt, then `PUT /me/devices/{installation}`).
 *
 * The app installs it at start-up with S-102's registrar (`src/push/install.ts`, mobile gaps part 1); where push isn't
 * installed (the web build, the fixture backend) the row isn't shown; the api's push column (where the server sends to)
 * stays editable either way.
 */
export interface PhonePush {
  registration: Pick<PushRegistration, 'enable'>;
  permission(): Promise<PushPermission>;
  /** The system settings page for the app (notifications refused there can only be turned on there). */
  openSettings(): Promise<void>;
}

let phone: PhonePush | null = null;
const watchers = new Set<() => void>();

export function setPhonePush(p: PhonePush | null) {
  phone = p;
  watchers.forEach((w) => w());
}

export function phonePush(): PhonePush | null {
  return phone;
}

/** The installed port, re-rendering when push is installed after the screen (a cold start straight onto it). */
export function usePhonePush(): PhonePush | null {
  return useSyncExternalStore(
    (w) => (watchers.add(w), () => watchers.delete(w)),
    () => phone,
  );
}
