import type { PushPermission, PushRegistration } from '@northline/mobile-kit';

/**
 * Push on this phone, for Notifications' "This phone" row (S-102's parts). Account › Notifications is where the
 * design lets the person turn notifications on — the moment that explains why, never at launch — so this screen asks
 * the system for the permission (`PushRegistration.enable()`: the prompt, then `PUT /me/devices/{installation}`).
 *
 * The app installs the registration at start-up together with S-102's registrar once `expo-notifications` and its
 * config plugin are in the app (MOBILE_PLAN § Contracts › Push: not yet — it needs the plugin and EAS push
 * credentials). Until then nothing is installed and the row isn't shown; the api's push column (where the server
 * sends to) stays editable either way.
 */
export interface PhonePush {
  registration: Pick<PushRegistration, 'enable'>;
  permission(): Promise<PushPermission>;
  /** The system settings page for the app (notifications refused there can only be turned on there). */
  openSettings(): Promise<void>;
}

let phone: PhonePush | null = null;

export function setPhonePush(p: PhonePush | null) {
  phone = p;
}

export function phonePush(): PhonePush | null {
  return phone;
}
