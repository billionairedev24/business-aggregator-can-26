import type { ApiClient } from '../api/client';
import { ApiError, NetworkError } from '../api/errors';
import { randomToken } from '../dpop/random';
import type { Locale } from '../i18n';
import type { SecureStorage } from '../storage/secure';
import { parseDeepLink, type DeepLink } from './deepLinks';

/** What the phone says about notifications for this app (iOS "provisional" = delivered quietly). */
export type PushPermission = 'granted' | 'provisional' | 'denied' | 'undetermined';

/**
 * The phone's notification service, as the app sees it. {@link expoPushPlatform} adapts `expo-notifications`; tests
 * pass a fake. Tokens are the native ones (APNs device token on iOS, FCM registration token on Android): the worker
 * talks to APNs and FCM itself, without Expo's push service.
 */
export interface PushPlatform {
  readonly os: 'ios' | 'android';
  permission(): Promise<PushPermission>;
  /** Shows the system prompt (only from a screen that explains why — never at launch). */
  requestPermission(): Promise<PushPermission>;
  /** The native token, or null when there is none yet (no permission on iOS, no Google Play services). */
  deviceToken(): Promise<string | null>;
  /** The platform rotated the token. */
  onTokenChange(listener: (token: string) => void): () => void;
  /** The person tapped a notification while the app was running or in the background. */
  onTap(listener: (data: Record<string, unknown>) => void): () => void;
  /** The tap that launched the app from cold, if any. */
  launchTap(): Promise<Record<string, unknown> | null>;
}

/** Only `call` of the api client is used (tests pass a fake). */
export type DeviceRegistryApi = Pick<ApiClient, 'call'>;

export interface PushRegistrationOptions {
  api: DeviceRegistryApi;
  platform: PushPlatform;
  storage: SecureStorage;
  /** `1.2.0 (42)` — shown nowhere, helps support. */
  appVersion: string;
  /** The app's language now (`en` | `fr-CA`); "same as app" notifications are written in it. */
  locale: () => Locale;
}

const INSTALLATION = 'nl.push.installation.v1';
const REGISTERED = 'nl.push.registered.v1';

/** What the registry was last told, to skip a PUT when nothing changed (flaky networks, every app start). */
interface Registered {
  token: string | null;
  permission: PushPermission;
  locale: string;
  appVersion: string;
}

/**
 * This installation in the push device registry (`PUT|DELETE /api/v1/me/devices/{installationId}`, S-102):
 *
 * - {@link sync} at every start, when the app comes back to the foreground and when the token changes: it sends the
 *   permission, the token, the language and the app version — only when one of them changed since the last
 *   successful call. Offline or a 5xx: nothing is lost, the next sync sends it (`false` = try again later).
 * - {@link enable} after the person chose to turn notifications on (asks the system, then syncs).
 * - {@link unregister} at sign-out, **before** the tokens are revoked (the call needs them): this phone stops getting
 *   the person's notifications. If it fails offline, the server forgets the installation after 90 days without a
 *   refresh, and a token that comes back for someone else moves to them.
 *
 * The installation id is random, made once per installation and kept in secure storage (not a hardware id).
 */
export class PushRegistration {
  private syncing: Promise<boolean> | null = null;

  constructor(private readonly options: PushRegistrationOptions) {}

  async installationId(): Promise<string> {
    const stored = await this.options.storage.get(INSTALLATION);
    if (stored) return stored;
    const id = randomToken(24); // 32 base64url characters
    await this.options.storage.set(INSTALLATION, id);
    return id;
  }

  /** Asks the system for permission, then registers. */
  async enable(): Promise<PushPermission> {
    const current = await this.options.platform.permission();
    const permission = current === 'undetermined' ? await this.options.platform.requestPermission() : current;
    await this.sync();
    return permission;
  }

  /** Tells the registry this installation's state when it changed; one at a time. */
  sync(): Promise<boolean> {
    this.syncing ??= this.doSync().finally(() => {
      this.syncing = null;
    });
    return this.syncing;
  }

  private async doSync(): Promise<boolean> {
    const { platform, storage, api } = this.options;
    const permission = await platform.permission();
    const allowed = permission === 'granted' || permission === 'provisional';
    const token = allowed ? await platform.deviceToken() : null;
    if (allowed && !token) return false; // the platform has no token yet; onTokenChange syncs when it does
    const state: Registered = {
      token,
      permission,
      locale: this.options.locale().startsWith('fr') ? 'fr-CA' : 'en-CA',
      appVersion: this.options.appVersion,
    };
    const last = await storage.get(REGISTERED);
    if (last === JSON.stringify(state)) return true;
    const id = await this.installationId();
    try {
      await api.call('PUT', `/me/devices/${id}`, {
        json: { platform: platform.os, token: token ?? undefined, locale: state.locale, appVersion: state.appVersion, permission },
      });
    } catch (e) {
      if (e instanceof NetworkError || (e instanceof ApiError && e.transient)) return false;
      throw e;
    }
    await storage.set(REGISTERED, JSON.stringify(state));
    return true;
  }

  /** Sign-out: removes this installation from the person's devices (call it before revoking the tokens). */
  async unregister(): Promise<void> {
    const id = await this.options.storage.get(INSTALLATION);
    await this.options.storage.remove(REGISTERED);
    if (!id) return;
    try {
      await this.options.api.call('DELETE', `/me/devices/${id}`);
    } catch (e) {
      // 404: already gone. Offline: the server prunes it (and a new sign-in moves the token).
      if (!(e instanceof ApiError && e.status === 404) && !(e instanceof NetworkError)) throw e;
    }
  }

  /** Syncs whenever the platform rotates the token; returns the unsubscribe. */
  start(): () => void {
    return this.options.platform.onTokenChange(() => void this.sync().catch(() => undefined));
  }
}

/**
 * Routes notification taps — including the one that launched the app — to the screen of their deep link (`link` in
 * the payload). A link for another host or an unknown screen is ignored.
 */
export function handleNotificationTaps(
  platform: PushPlatform,
  hosts: readonly string[],
  open: (link: DeepLink) => void,
): () => void {
  const route = (data: Record<string, unknown> | null) => {
    const link = data && typeof data.link === 'string' ? parseDeepLink(data.link, hosts) : null;
    if (link) open(link);
  };
  void platform.launchTap().then(route);
  return platform.onTap(route);
}

/** The parts of `expo-notifications` the adapter uses (structural, so mobile-kit doesn't depend on it). */
export interface ExpoNotificationsModule {
  getPermissionsAsync(): Promise<ExpoPermission>;
  requestPermissionsAsync(request?: unknown): Promise<ExpoPermission>;
  getDevicePushTokenAsync(): Promise<{ data: unknown }>;
  addPushTokenListener(listener: (token: { data: unknown }) => void): { remove(): void };
  addNotificationResponseReceivedListener(listener: (response: ExpoResponse) => void): { remove(): void };
  getLastNotificationResponseAsync(): Promise<ExpoResponse | null>;
}
interface ExpoPermission {
  status: string;
  ios?: { status?: number };
}
interface ExpoResponse {
  notification: { request: { content: { data?: Record<string, unknown> | null } } };
}

/** iOS `UNAuthorizationStatus.provisional` as expo-notifications reports it. */
const IOS_PROVISIONAL = 3;

function permissionOf(p: ExpoPermission): PushPermission {
  if (p.ios?.status === IOS_PROVISIONAL) return 'provisional';
  return p.status === 'granted' ? 'granted' : p.status === 'denied' ? 'denied' : 'undetermined';
}

/** {@link PushPlatform} over expo-notifications (the app passes the module and `Platform.OS`). */
export function expoPushPlatform(notifications: ExpoNotificationsModule, os: 'ios' | 'android'): PushPlatform {
  const dataOf = (r: ExpoResponse | null) => r?.notification.request.content.data ?? null;
  return {
    os,
    permission: async () => permissionOf(await notifications.getPermissionsAsync()),
    requestPermission: async () =>
      permissionOf(await notifications.requestPermissionsAsync({ ios: { allowAlert: true, allowBadge: true, allowSound: true } })),
    deviceToken: async () => {
      try {
        const token = (await notifications.getDevicePushTokenAsync()).data;
        return typeof token === 'string' && token ? token : null;
      } catch {
        return null; // simulators, no Google Play services
      }
    },
    onTokenChange: (listener) => {
      const sub = notifications.addPushTokenListener((t) => typeof t.data === 'string' && listener(t.data));
      return () => sub.remove();
    },
    onTap: (listener) => {
      const sub = notifications.addNotificationResponseReceivedListener((r) => listener(dataOf(r) ?? {}));
      return () => sub.remove();
    },
    launchTap: async () => dataOf(await notifications.getLastNotificationResponseAsync()),
  };
}
