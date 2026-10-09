import { PushHooks, setPushRegistrar } from '../src/push/hooks';
import { installPush, siteHostOf, type PushPermission, type PushPlatform } from '../src/push';
import { memorySecureStorage } from '../src/storage/secure';

/** A phone's notification service under the test's control (as push.test.ts's). */
function fakePlatform() {
  const state = { permission: 'granted' as PushPermission, token: 'fcm-token-1' as string | null };
  const tokens = new Set<(t: string) => void>();
  const taps = new Set<(d: Record<string, unknown>) => void>();
  let launch: Record<string, unknown> | null = null;
  const platform: PushPlatform = {
    os: 'android',
    permission: async () => state.permission,
    requestPermission: async () => (state.permission = 'granted'),
    deviceToken: async () => state.token,
    onTokenChange: (l) => (tokens.add(l), () => tokens.delete(l)),
    onTap: (l) => (taps.add(l), () => taps.delete(l)),
    launchTap: async () => launch,
  };
  return {
    platform,
    state,
    tokens,
    taps,
    rotate: (t: string) => {
      state.token = t;
      tokens.forEach((l) => l(t));
    },
    tap: (d: Record<string, unknown>) => taps.forEach((l) => l(d)),
    launchWith: (d: Record<string, unknown>) => (launch = d),
  };
}

function fakeApi() {
  const calls: Array<{ method: string; path: string; json?: Record<string, unknown> }> = [];
  return {
    calls,
    call: jest.fn(async (method: string, path: string, o: { json?: Record<string, unknown> } = {}) => {
      calls.push({ method, path, json: o.json });
      return { status: 200, body: null, headers: new Headers() };
    }),
  };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

afterEach(() => setPushRegistrar(null));

describe('installPush (the apps’ push wiring)', () => {
  it('registers at start while signed in, follows token rotation and foreground returns, and routes taps', async () => {
    const phone = fakePlatform();
    const api = fakeApi();
    let foreground: (() => void) | null = null;
    const opened: string[] = [];
    const push = installPush({
      platform: phone.platform,
      storage: memorySecureStorage(),
      api,
      appVersion: '1.0.0',
      locale: () => 'fr-CA',
      hosts: ['northline.ca'],
      open: (r) => opened.push(r),
      signedIn: () => true,
      onForeground: (l) => ((foreground = l), () => (foreground = null)),
    });
    await flush();
    expect(api.calls).toHaveLength(1);
    expect(api.calls[0]).toMatchObject({ method: 'PUT', json: { platform: 'android', token: 'fcm-token-1', locale: 'fr-CA', permission: 'granted' } });

    phone.rotate('fcm-token-2');
    await flush();
    expect(api.calls.at(-1)?.json).toMatchObject({ token: 'fcm-token-2' });

    // nothing changed: a foreground return sends nothing; the permission turned off in the settings: sent, no token
    foreground!();
    await flush();
    expect(api.calls).toHaveLength(2);
    phone.state.permission = 'denied';
    foreground!();
    await flush();
    expect(api.calls).toHaveLength(3);
    expect(api.calls[2]!.json).toMatchObject({ permission: 'denied' });
    expect(api.calls[2]!.json?.token).toBeUndefined();

    phone.tap({ link: 'https://northline.ca/app/orders/01J9ORDER' });
    phone.tap({ link: 'https://evil.example/app/orders/01J9ORDER' });
    expect(opened).toEqual(['/orders/01J9ORDER']);

    push.stop();
    expect(phone.tokens.size + phone.taps.size).toBe(0);
    expect(foreground).toBeNull();
    expect(PushHooks.installed).toBe(false);
  });

  it('installs the sign-in / sign-out registrar and tells nobody while signed out', async () => {
    const phone = fakePlatform();
    const api = fakeApi();
    phone.launchWith({ link: 'ca.northline.courier://run' });
    const opened: string[] = [];
    installPush({ platform: phone.platform, storage: memorySecureStorage(), api, appVersion: '1', locale: () => 'en', hosts: [], open: (r) => opened.push(r), signedIn: () => false });
    await flush();
    expect(api.calls).toHaveLength(0);
    phone.rotate('other');
    await flush();
    expect(api.calls).toHaveLength(0);
    // the tap that launched the app opens its screen
    expect(opened).toEqual(['/run']);
    expect(PushHooks.installed).toBe(true);
    await PushHooks.signedIn(api as never);
    expect(api.calls.map((c) => c.method)).toEqual(['PUT']);
    await PushHooks.signingOut(api as never);
    expect(api.calls.map((c) => c.method)).toEqual(['PUT', 'DELETE']);
  });

  it('derives the consumer host from the api URL', () => {
    expect(siteHostOf('https://api.dev.northline.ca/api/v1')).toBe('dev.northline.ca');
    expect(siteHostOf('http://localhost:8080/api/v1')).toBe('localhost:8080');
    expect(siteHostOf('not a url')).toBeNull();
  });
});
