import { ApiError, NetworkError } from '../src/api/errors';
import {
  PushRegistration,
  expoPushPlatform,
  pushRegistrar,
  handleNotificationTaps,
  parseDeepLink,
  routeOf,
  type ExpoNotificationsModule,
  type PushPermission,
  type PushPlatform,
} from '../src/push';
import { memorySecureStorage } from '../src/storage/secure';

const HOSTS = ['northline.ca', 'staging.northline.ca'];

/** A phone's notification service: permission, a token, taps — all under the test's control. */
function fakePlatform(os: 'ios' | 'android' = 'ios') {
  const state = { permission: 'undetermined' as PushPermission, token: 'apns-token-0001' as string | null, asked: 0 };
  const tokenListeners = new Set<(t: string) => void>();
  const tapListeners = new Set<(d: Record<string, unknown>) => void>();
  let launch: Record<string, unknown> | null = null;
  const platform: PushPlatform = {
    os,
    permission: async () => state.permission,
    requestPermission: async () => {
      state.asked++;
      state.permission = 'granted';
      return state.permission;
    },
    deviceToken: async () => state.token,
    onTokenChange: (l) => (tokenListeners.add(l), () => tokenListeners.delete(l)),
    onTap: (l) => (tapListeners.add(l), () => tapListeners.delete(l)),
    launchTap: async () => launch,
  };
  return {
    platform,
    state,
    rotate: (token: string) => {
      state.token = token;
      tokenListeners.forEach((l) => l(token));
    },
    tap: (data: Record<string, unknown>) => tapListeners.forEach((l) => l(data)),
    launchWith: (data: Record<string, unknown>) => (launch = data),
  };
}

/** The api as the registry sees it: calls are recorded; `fail` makes the next calls throw. */
function fakeApi() {
  const calls: Array<{ method: string; path: string; json?: unknown }> = [];
  const failures: Error[] = [];
  return {
    calls,
    fail: (...errors: Error[]) => failures.push(...errors),
    api: {
      call: jest.fn(async (method: string, path: string, options: { json?: unknown } = {}) => {
        calls.push({ method, path, json: options.json });
        const failure = failures.shift();
        if (failure) throw failure;
        return { status: 200, body: null, headers: new Headers() };
      }),
    } as never,
  };
}

function registration(os: 'ios' | 'android' = 'ios') {
  const phone = fakePlatform(os);
  const server = fakeApi();
  const storage = memorySecureStorage();
  let locale: 'en' | 'fr-CA' = 'en';
  const push = new PushRegistration({
    api: server.api,
    platform: phone.platform,
    storage,
    appVersion: '1.0.0 (7)',
    locale: () => locale,
  });
  return { push, phone, server, storage, setLocale: (l: 'en' | 'fr-CA') => (locale = l) };
}

describe('push registration', () => {
  it('asks for permission only when enabled, then registers the token, platform, language and version', async () => {
    const { push, phone, server } = registration();
    expect(await push.sync()).toBe(true);
    expect(phone.state.asked).toBe(0);
    expect(server.calls[0]).toMatchObject({ method: 'PUT', json: { platform: 'ios', permission: 'undetermined', locale: 'en-CA' } });
    expect((server.calls[0]!.json as { token?: string }).token).toBeUndefined(); // no permission, no token

    expect(await push.enable()).toBe('granted');
    expect(phone.state.asked).toBe(1);
    const id = await push.installationId();
    expect(id).toMatch(/^[A-Za-z0-9_-]{32}$/);
    expect(server.calls[1]).toEqual({
      method: 'PUT',
      path: `/me/devices/${id}`,
      json: { platform: 'ios', token: 'apns-token-0001', locale: 'en-CA', appVersion: '1.0.0 (7)', permission: 'granted' },
    });
  });

  it('keeps one installation id and calls the api only when something changed', async () => {
    const { push, phone, server, setLocale } = registration('android');
    phone.state.permission = 'granted';
    await push.sync();
    await push.sync();
    expect(server.calls).toHaveLength(1);

    setLocale('fr-CA');
    await push.sync();
    expect(server.calls).toHaveLength(2);
    expect(server.calls[1]!.json).toMatchObject({ platform: 'android', locale: 'fr-CA' });
    expect(server.calls[0]!.path).toBe(server.calls[1]!.path);
  });

  it('refreshes when the platform rotates the token', async () => {
    const { push, phone, server } = registration();
    phone.state.permission = 'granted';
    await push.sync();
    const stop = push.start();
    phone.rotate('apns-token-0002');
    await new Promise((r) => setTimeout(r, 0));
    expect(server.calls.at(-1)!.json).toMatchObject({ token: 'apns-token-0002' });
    stop();
  });

  it('is offline tolerant: a lost or failed call is sent again by the next sync', async () => {
    const { push, phone, server } = registration();
    phone.state.permission = 'granted';
    server.fail(new NetworkError(new Error('offline')), new ApiError(503, 'unavailable', undefined));
    expect(await push.sync()).toBe(false);
    expect(await push.sync()).toBe(false);
    expect(await push.sync()).toBe(true);
    expect(server.calls).toHaveLength(3);
    expect(await push.sync()).toBe(true);
    expect(server.calls).toHaveLength(3);
  });

  it('runs one sync at a time', async () => {
    const { push, phone, server } = registration();
    phone.state.permission = 'granted';
    await Promise.all([push.sync(), push.sync(), push.sync()]);
    expect(server.calls).toHaveLength(1);
  });

  it('waits for a token rather than registering an allowed device without one', async () => {
    const { push, phone, server } = registration();
    phone.state.permission = 'granted';
    phone.state.token = null;
    expect(await push.sync()).toBe(false);
    expect(server.calls).toHaveLength(0);
  });

  it('a refused registration (422) is an error, not a retry', async () => {
    const { push, phone, server } = registration();
    phone.state.permission = 'granted';
    server.fail(new ApiError(422, undefined, undefined, [{ field: 'token', message: 'Send the push token the platform gave this app.' }]));
    await expect(push.sync()).rejects.toBeInstanceOf(ApiError);
  });

  it('removes the installation at sign-out, and tolerates it being gone or the phone being offline', async () => {
    const { push, phone, server, storage } = registration();
    phone.state.permission = 'granted';
    await push.sync();
    const id = await push.installationId();
    await push.unregister();
    expect(server.calls.at(-1)).toMatchObject({ method: 'DELETE', path: `/me/devices/${id}` });
    expect(storage.values.has('nl.push.registered.v1')).toBe(false);

    server.fail(new ApiError(404, 'not_found', undefined));
    await expect(push.unregister()).resolves.toBeUndefined();
    server.fail(new NetworkError(new Error('offline')));
    await expect(push.unregister()).resolves.toBeUndefined();

    // the next person to sign in on this phone registers again (the server moves the token to them)
    await push.sync();
    expect(server.calls.at(-1)).toMatchObject({ method: 'PUT' });
  });
});

describe("the sign-in / sign-out hook point's registrar", () => {
  it('registers after a sign-in and removes the installation before the sign-out', async () => {
    const phone = fakePlatform();
    phone.state.permission = 'granted';
    const server = fakeApi();
    const storage = memorySecureStorage();
    const registrar = pushRegistrar({ platform: phone.platform, storage, appVersion: '1.0.0', locale: () => 'fr-CA' });
    await registrar.signedIn(server.api);
    expect(server.calls[0]).toMatchObject({ method: 'PUT', json: { token: 'apns-token-0001', locale: 'fr-CA' } });
    await registrar.signingOut(server.api);
    expect(server.calls[1]).toMatchObject({ method: 'DELETE', path: server.calls[0]!.path });
  });
});

describe('deep links', () => {
  it.each([
    ['https://northline.ca/app/orders/01J9ZD3V00000000000000ORD1', { screen: 'order', orderId: '01J9ZD3V00000000000000ORD1', food: false }, '/orders/01J9ZD3V00000000000000ORD1'],
    ['https://staging.northline.ca/app/food/orders/O1', { screen: 'order', orderId: 'O1', food: true }, '/food/orders/O1'],
    ['https://northline.ca/app/bookings/B1', { screen: 'booking', bookingId: 'B1' }, '/bookings/B1'],
    ['https://northline.ca/app/quotes/qt_1', { screen: 'quote', quoteId: 'qt_1' }, '/quotes/qt_1'],
    ['https://northline.ca/app/cases/RF-2214', { screen: 'case', caseNumber: 'RF-2214' }, '/cases/RF-2214'],
    ['https://northline.ca/courier/run', { screen: 'courierRun' }, '/run'],
    ['ca.northline.app://orders/O1', { screen: 'order', orderId: 'O1', food: false }, '/orders/O1'],
    ['ca.northline.app:/bookings/B1', { screen: 'booking', bookingId: 'B1' }, '/bookings/B1'],
    ['ca.northline.courier://run', { screen: 'courierRun' }, '/run'],
  ])('%s opens %j', (link, expected, route) => {
    const parsed = parseDeepLink(link, HOSTS);
    expect(parsed).toEqual(expected);
    expect(routeOf(parsed!)).toBe(route);
  });

  it.each([
    'https://evil.example/app/orders/O1', // another host
    'https://northline.ca/orders/O1', // the web's own order page, not an app link
    'https://northline.ca/account', // not an app screen
    'https://northline.ca/app/orders/O1/extra',
    'https://northline.ca/app/oauth2redirect', // the OAuth redirect (S-29), not a screen
    'https://northline.ca/app/orders/..%2Fsecrets',
    'javascript:alert(1)',
    'ca.northline.courier://orders/O1',
    'not a url',
    'https://northline.ca/app/orders/%E0%A4%A', // malformed escape: ignored, not thrown (S-104)
    'ca.northline.app://orders/%ZZ',
  ])('ignores %s', (link) => {
    expect(parseDeepLink(link, HOSTS)).toBeNull();
  });

  it('routes taps, including the one that launched the app, and ignores foreign links', async () => {
    const phone = fakePlatform();
    phone.launchWith({ link: 'https://northline.ca/app/quotes/qt_9', quoteId: 'qt_9' });
    const opened: string[] = [];
    const stop = handleNotificationTaps(phone.platform, HOSTS, (l) => opened.push(routeOf(l)));
    await new Promise((r) => setTimeout(r, 0));
    phone.tap({ link: 'https://northline.ca/app/orders/O7' });
    phone.tap({ link: 'https://evil.example/app/orders/O7' });
    phone.tap({});
    stop();
    phone.tap({ link: 'https://northline.ca/app/orders/O8' });
    expect(opened).toEqual(['/quotes/qt_9', '/orders/O7']);
  });
});

describe('expo-notifications adapter', () => {
  function module(overrides: Partial<ExpoNotificationsModule> = {}): ExpoNotificationsModule {
    return {
      getPermissionsAsync: async () => ({ status: 'undetermined' }),
      requestPermissionsAsync: async () => ({ status: 'granted' }),
      getDevicePushTokenAsync: async () => ({ data: 'fcm-token-1' }),
      addPushTokenListener: () => ({ remove: () => undefined }),
      addNotificationResponseReceivedListener: () => ({ remove: () => undefined }),
      getLastNotificationResponseAsync: async () => ({ notification: { request: { content: { data: { link: 'x' } } } } }),
      ...overrides,
    };
  }

  it('maps permissions, iOS provisional included, and reads the native token', async () => {
    expect(await expoPushPlatform(module(), 'android').permission()).toBe('undetermined');
    expect(await expoPushPlatform(module({ getPermissionsAsync: async () => ({ status: 'granted', ios: { status: 3 } }) }), 'ios').permission()).toBe('provisional');
    expect(await expoPushPlatform(module({ getPermissionsAsync: async () => ({ status: 'denied' }) }), 'ios').permission()).toBe('denied');
    expect(await expoPushPlatform(module(), 'android').deviceToken()).toBe('fcm-token-1');
    expect(await expoPushPlatform(module({ getDevicePushTokenAsync: async () => Promise.reject(new Error('simulator')) }), 'ios').deviceToken()).toBeNull();
    expect(await expoPushPlatform(module(), 'ios').launchTap()).toEqual({ link: 'x' });
  });
});
