import Constants from 'expo-constants';
import * as Linking from 'expo-linking';
import * as Notifications from 'expo-notifications';
import { AppState, Platform } from 'react-native';

import { expoPushPlatform, installPush, type ExpoNotificationsModule, type Locale } from '@northline/mobile-kit';

import { setPhonePush } from '../account/phonePush';
import { config } from '../config';
import { services } from '../services';

/** The Android channel the worker sends to (S-103's config plugin names it the default; push.md). */
export const CHANNEL = 'updates';

/**
 * Whether this build talks to APNs/FCM: not the web build, not the fixture backend (the smoke test and demos). Jest
 * turns it off for the screen tests (jest.setup.ts) and on in the push tests.
 */
let available = Platform.OS !== 'web' && !config.fixtures;

export function setPushAvailable(on: boolean) {
  available = on;
}

export interface PushSetupOptions {
  open: (route: string) => void;
  signedIn: () => boolean;
  /** The channel's name in the system settings, in the app's language. */
  channelName: string;
}

/**
 * Turns push on in the consumer app (mobile gaps part 1): `expo-notifications` through mobile-kit's `installPush` —
 * S-102's registrar at the sign-in / sign-out hook point, the registry told at every start and foreground return while
 * signed in, token rotation, taps to the deep link's screen — and Notifications' "This phone" row (`setPhonePush`). The
 * permission is asked only from the screens that explain it (the order / booking confirmation, the settings row).
 *
 * Not on the web build or the fixture backend (no APNs/FCM there; the smoke test runs without it). Returns the stop.
 */
export function setUpPush(o: PushSetupOptions): (() => void) | null {
  if (!available) return null;
  Notifications.setNotificationHandler({
    // a notification that arrives while the app is open still shows (it carries the order's or booking's news)
    handleNotification: async () => ({ shouldShowBanner: true, shouldShowList: true, shouldPlaySound: false, shouldSetBadge: false }),
  });
  if (Platform.OS === 'android') {
    void Notifications.setNotificationChannelAsync(CHANNEL, { name: o.channelName, importance: Notifications.AndroidImportance.DEFAULT }).catch(() => undefined);
  }
  const platform = expoPushPlatform(Notifications as unknown as ExpoNotificationsModule, Platform.OS === 'ios' ? 'ios' : 'android');
  const push = installPush({
    platform,
    storage: services().secure,
    api: services().api,
    appVersion: Constants.expoConfig?.version ?? 'dev',
    locale: (): Locale => services().language(),
    hosts: [new URL(config.siteOrigin).host],
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
