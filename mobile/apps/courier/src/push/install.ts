import Constants from 'expo-constants';
import * as Linking from 'expo-linking';
import * as Notifications from 'expo-notifications';
import { useSyncExternalStore } from 'react';
import { AppState, Platform } from 'react-native';

import { expoPushPlatform, installPush, siteHostOf, type ExpoNotificationsModule, type PushPermission, type PushRegistration } from '@northline/mobile-kit';

import { config } from '../config';
import { services } from '../services';

/** The Android channel the worker sends to (S-103's config plugin names it the default; push.md). */
export const CHANNEL = 'updates';

/** Push on this phone, for Account's "This phone" row and the shift screen's card. */
export interface PhonePush {
  registration: Pick<PushRegistration, 'enable'>;
  permission(): Promise<PushPermission>;
  openSettings(): Promise<void>;
}

let phone: PhonePush | null = null;
const watchers = new Set<() => void>();
export const phonePush = () => phone;
export function setPhonePush(p: PhonePush | null) {
  phone = p;
  watchers.forEach((w) => w());
}
/** The installed port, re-rendering when push is installed after the screen (a cold start straight onto it). */
export function usePhonePush(): PhonePush | null {
  return useSyncExternalStore(
    (w) => (watchers.add(w), () => watchers.delete(w)),
    () => phone,
  );
}

/** Not on the web build or the fixture backend; Jest turns it off for the screen tests and on in the push tests. */
let available = Platform.OS !== 'web' && !config.fixtures;
export function setPushAvailable(on: boolean) {
  available = on;
}

/**
 * Push in the courier app (mobile gaps part 1): S-102's "New run" and "run given to another courier" notifications.
 * `expo-notifications` through mobile-kit's `installPush` — the registrar at the sign-in / sign-out hook point (the
 * courier's token's scope `courier` makes the api file it under the courier app), the registry told at every start and
 * foreground return while signed in, token rotation, a tap → the run (`/courier/run` on the consumer host, whose name
 * comes from the api's: `api.<zone>` → `<zone>`). The permission is asked from the shift screen's card (the moment a
 * run can come) or Account's "This phone", never at launch.
 */
export function setUpPush(o: { open: (route: string) => void; signedIn: () => boolean; channelName: string }): (() => void) | null {
  if (!available) return null;
  Notifications.setNotificationHandler({
    handleNotification: async () => ({ shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false }),
  });
  if (Platform.OS === 'android') {
    void Notifications.setNotificationChannelAsync(CHANNEL, { name: o.channelName, importance: Notifications.AndroidImportance.HIGH }).catch(() => undefined);
  }
  const platform = expoPushPlatform(Notifications as unknown as ExpoNotificationsModule, Platform.OS === 'ios' ? 'ios' : 'android');
  const host = siteHostOf(config.apiUrl);
  const push = installPush({
    platform,
    storage: services().secure,
    api: services().api,
    appVersion: Constants.expoConfig?.version ?? 'dev',
    locale: () => services().language(),
    hosts: host ? [host] : [],
    open: o.open,
    signedIn: o.signedIn,
    onForeground: (listener) => {
      const sub = AppState.addEventListener('change', (state) => state === 'active' && listener());
      return () => sub.remove();
    },
  });
  setPhonePush({ registration: push.registration, permission: push.permission, openSettings: () => Linking.openSettings() });
  return () => {
    push.stop();
    setPhonePush(null);
  };
}
