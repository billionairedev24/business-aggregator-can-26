import type { Locale } from '../i18n';
import type { SecureStorage } from '../storage/secure';
import { routeOf } from './deepLinks';
import { setPushRegistrar } from './hooks';
import { PushRegistration, handleNotificationTaps, pushRegistrar, type DeviceRegistryApi, type PushPermission, type PushPlatform } from './registration';

export interface AppPushOptions {
  platform: PushPlatform;
  storage: SecureStorage;
  /** The app's signed api client (the registry call needs the person's DPoP-bound token). */
  api: DeviceRegistryApi;
  appVersion: string;
  locale: () => Locale;
  /** The consumer hosts whose https links the app accepts (parseDeepLink). */
  hosts: readonly string[];
  /** Opens an app route (expo-router's `router.push`). */
  open: (route: string) => void;
  /** Whether someone is signed in now: the registry is only told while they are. */
  signedIn: () => boolean;
  /** Calls the listener each time the app comes back to the foreground (React Native's AppState). */
  onForeground?: (listener: () => void) => () => void;
}

export interface AppPush {
  registration: PushRegistration;
  permission(): Promise<PushPermission>;
  /** Removes the listeners and the registrar (tests, hot reload). */
  stop(): void;
}

/**
 * Push in an app, in one call at start-up (mobile gaps part 1 — the wiring S-102 and S-103 left open):
 *
 * - installs S-102's registrar at S-97's hook point, so the sign-in registers this installation and the sign-out
 *   removes it while the tokens still work;
 * - tells the registry again at every start and every return to the foreground while signed in — a permission turned
 *   off (or on) in the system settings, a new app version or language reaches the server; unchanged state sends nothing;
 * - follows the platform's token rotation;
 * - routes taps (also the one that launched the app) through the strict deep-link parser to the link's screen.
 *
 * It never asks for the permission: the app does that from a screen that explains why ({@link PushRegistration#enable}).
 */
export function installPush(o: AppPushOptions): AppPush {
  const { platform, storage, api, appVersion, locale } = o;
  const registration = new PushRegistration({ api, platform, storage, appVersion, locale });
  setPushRegistrar(pushRegistrar({ platform, storage, appVersion, locale }));
  const sync = () => {
    if (o.signedIn()) void registration.sync().catch(() => undefined);
  };
  const offs = [
    platform.onTokenChange(sync),
    handleNotificationTaps(platform, o.hosts, (link) => o.open(routeOf(link))),
    o.onForeground?.(sync) ?? (() => undefined),
  ];
  sync();
  return {
    registration,
    permission: () => platform.permission(),
    stop: () => {
      offs.forEach((off) => off());
      setPushRegistrar(null);
    },
  };
}

/**
 * The consumer host of an api URL by the runbooks' naming (`https://api.<zone>/api/v1` → `<zone>`): the courier app has
 * no site origin of its own, and its run links (`https://<zone>/courier/run`) come from the consumer host.
 */
export function siteHostOf(apiUrl: string): string | null {
  try {
    const host = new URL(apiUrl).host;
    return host.startsWith('api.') ? host.slice(4) : host;
  } catch {
    return null;
  }
}
