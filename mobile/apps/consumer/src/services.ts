import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient } from '@tanstack/react-query';
import { Platform } from 'react-native';

import {
  ApiClient,
  AppSignIn,
  ApiError,
  DEFAULT_TIMEOUT_MS,
  DpopSession,
  deviceSecureStorage,
  memorySecureStorage,
  randomToken,
  type Locale,
  type SecureStorage,
} from '@northline/mobile-kit';

import { AuthApi } from './api/auth';
import { config } from './config';
import { createFixtureServer, type FixtureServer } from './fixtures/server';

/** Small, non-secret values kept on the phone (AsyncStorage): the language, the saved location, "welcome seen". */
export interface KeyValueStore {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

export interface Services {
  session: DpopSession;
  /** `https://api.<zone>/api/v1`, DPoP-signed (public reads: `auth: 'optional'`). */
  api: ApiClient;
  /** northline-auth's JSON sign-in API in the app's cookie session (Journey A). */
  authApi: AuthApi;
  /** The in-app sign-in's hand-off to DPoP-bound tokens (the claimed https redirect). */
  appSignIn: AppSignIn;
  /** The fetch every request goes through (the fixture backend's in fixture mode and in tests). */
  fetch: typeof fetch;
  queryClient: QueryClient;
  store: KeyValueStore;
  fixtures: FixtureServer | null;
  language(): Locale;
  setLanguage(locale: Locale): void;
  /**
   * This installation's browsing key (`X-Northline-Guest`, sent on every api call like the consumer-bff does for the
   * web): a guest's cart is kept under it and merged into the person's at sign-in (S-51). Random, not an identity.
   */
  guestId(): string | null;
  /** Reads or creates the guest id (the root layout awaits it before the first screen). */
  loadGuestId(): Promise<string>;
}

export const GUEST_KEY = 'nl.app.guestId';

export interface ServiceOptions {
  fetchImpl?: typeof fetch;
  secureStorage?: SecureStorage;
  store?: KeyValueStore;
  fixtures?: FixtureServer | null;
}

/**
 * Everything the screens share. One instance per app process ({@link services}); tests build their own with fakes.
 * Queries: a refusal (4xx) is final, no answer or a 5xx gets two more tries with back-off (flaky mobile networks),
 * and data stays fresh for 30 s so moving between tabs doesn't refetch.
 */
export function createServices(options: ServiceOptions = {}): Services {
  const fixtures = options.fixtures !== undefined ? options.fixtures : config.fixtures ? createFixtureServer() : null;
  const fetchImpl = options.fetchImpl ?? fixtures?.fetch ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  const secure = options.secureStorage ?? (Platform.OS === 'web' || fixtures ? memorySecureStorage() : deviceSecureStorage);
  let language: Locale = 'en';
  let guest: string | null = null;
  const store = options.store ?? AsyncStorage;
  const session = new DpopSession(
    { issuer: config.authIssuer, clientId: config.clientId, redirectUri: config.redirectUri, scopes: config.scopes },
    secure,
    fetchImpl,
  );
  const guestHeader = (): Record<string, string> => (guest ? { 'X-Northline-Guest': guest } : {});
  const api = new ApiClient(config.apiUrl, session, () => language, fetchImpl, DEFAULT_TIMEOUT_MS, guestHeader);
  const authApi = new AuthApi(config.authIssuer, fetchImpl, () => language);
  const appSignIn = new AppSignIn(session, config.httpsRedirectUri, fetchImpl);
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: (count, e) => count < 2 && !(e instanceof ApiError && !e.transient),
        retryDelay: (n) => Math.min(8_000, 1_000 * 2 ** n),
        staleTime: 30_000,
        refetchOnWindowFocus: true,
      },
      mutations: { retry: 0 },
    },
  });
  session.onChange((signedIn) => {
    if (!signedIn) queryClient.clear();
  });
  return {
    session,
    api,
    authApi,
    appSignIn,
    fetch: fetchImpl,
    queryClient,
    store,
    fixtures,
    language: () => language,
    setLanguage: (l) => {
      language = l;
    },
    guestId: () => guest,
    loadGuestId: async () => {
      if (guest) return guest;
      const saved = await store.getItem(GUEST_KEY).catch(() => null);
      guest = saved && /^[A-Za-z0-9_-]{16,128}$/.test(saved) ? saved : `g_${randomToken(16)}`;
      if (guest !== saved) await store.setItem(GUEST_KEY, guest).catch(() => undefined);
      return guest;
    },
  };
}

let instance: Services | null = null;

/** The app's services (created on first use by the root layout). */
export function services(): Services {
  instance ??= createServices();
  return instance;
}

/** Tests: use these services instead. */
export function setServices(s: Services | null) {
  instance = s;
}
