import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Linking from 'expo-linking';
import { act, fireEvent, renderRouter, screen, waitFor } from 'expo-router/testing-library';

import { PushHooks, memorySecureStorage } from '@northline/mobile-kit';

import { createFixtureServer, type FixtureOptions } from '../src/fixtures/server';
import type { KeyValueStore } from '../src/offline/outbox';
import { PUSH_ASKED_KEY } from '../src/push/PhonePush';
import { setPushAvailable } from '../src/push/install';
import { createServices, setServices } from '../src/services';

function memoryStore(): KeyValueStore {
  const data = new Map<string, string>();
  return { getItem: async (k) => data.get(k) ?? null, setItem: async (k, v) => void data.set(k, v), removeItem: async (k) => void data.delete(k) };
}

async function start(options: { fixture?: FixtureOptions; url?: string } = {}) {
  const server = createFixtureServer(options.fixture);
  const services = createServices({ fixtures: server, fetchImpl: server.fetch, secureStorage: memorySecureStorage(), store: memoryStore() });
  setServices(services);
  const p = services.session.beginSignIn();
  await services.session.completeSignIn(`ca.northline.courier:/oauth2redirect?code=c&state=${p.state}`, p);
  const view = renderRouter('./app', { initialUrl: options.url ?? '/' });
  return { server, services, view };
}

interface PhoneMock {
  state: { permission: string; token: string | null; asked: number };
  tap(data: Record<string, unknown>): void;
  reset(): void;
}
const phone = () => (jest.requireMock('expo-notifications') as { __phone: PhoneMock }).__phone;
const picker = () => jest.requireMock('expo-image-picker') as { __queue: unknown[]; launchImageLibraryAsync: jest.Mock };

afterEach(async () => {
  setPushAvailable(false);
  phone().reset();
  picker().__queue.length = 0;
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

describe('push in the courier app (mobile gaps part 1, expo-notifications mocked)', () => {
  beforeEach(() => setPushAvailable(true));

  it('registers this phone at start without asking; the shift screen’s card asks once the courier is on shift', async () => {
    const { server } = await start({ fixture: { withRun: false } });
    await screen.findByTestId('shift-screen');
    await waitFor(() => expect(server.devices.size).toBe(1));
    expect([...server.devices.values()][0]).toMatchObject({ platform: 'ios', permission: 'undetermined', locale: 'en-CA' });
    expect(PushHooks.installed).toBe(true);
    fireEvent.press(await screen.findByTestId('push-prompt-on'));
    await waitFor(() => expect([...server.devices.values()][0]).toMatchObject({ permission: 'granted', token: 'apns-device-token-0001' }));
    expect(phone().state.asked).toBe(1);
    await waitFor(() => expect(screen.queryByTestId('push-prompt')).toBeNull());
    expect(await AsyncStorage.getItem(PUSH_ASKED_KEY)).toBe('1');
  });

  it('Account › “This phone”: on, or a way to the settings when refused', async () => {
    phone().state.permission = 'denied';
    const settings = jest.spyOn(Linking, 'openSettings').mockResolvedValue(undefined);
    await start({ url: '/account' });
    expect(await screen.findByText('Run notifications on this phone')).toBeTruthy();
    expect(screen.getByText('Notifications are off for Northline Courier in your phone’s settings.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('push-settings'));
    expect(settings).toHaveBeenCalled();
    settings.mockRestore();
  });

  it('a tap on “New run” opens the run (only a link of ours)', async () => {
    phone().state.permission = 'granted';
    const { view } = await start();
    await screen.findByTestId('shift-screen');
    act(() => phone().tap({ type: 'run', link: 'https://evil.example/courier/run' }));
    expect(view.getPathname()).toBe('/');
    act(() => phone().tap({ type: 'run', link: 'ca.northline.courier://run' }));
    await waitFor(() => expect(view.getPathname()).toBe('/run'));
  });

  it('removes this phone at sign-out, before the tokens are revoked', async () => {
    const { server } = await start({ url: '/account', fixture: { withRun: false } });
    await waitFor(() => expect(server.devices.size).toBe(1));
    fireEvent.press(await screen.findByTestId('sign-out'));
    await waitFor(() => expect(server.devices.size).toBe(0));
    const del = server.calls.findIndex((c) => c.method === 'DELETE');
    const revoke = server.calls.findIndex((c) => c.path.endsWith('/oauth2/revoke'));
    expect(del).toBeGreaterThan(-1);
    expect(del).toBeLessThan(revoke);
  });
});

describe('pilot feedback from the courier app (S-121, mobile gaps part 1)', () => {
  it('shows “Feedback” to pilot couriers only', async () => {
    await start();
    await screen.findByTestId('shift-screen');
    await waitFor(() => expect(screen.queryByTestId('pilot-feedback')).toBeNull());
  });

  it('sends what happened with the screen, app courier, and a screenshot from the photo library', async () => {
    const { server, view } = await start({ fixture: { pilot: true }, url: '/run' });
    fireEvent.press(await screen.findByTestId('pilot-feedback'));
    await waitFor(() => expect(view.getPathname()).toBe('/feedback'));
    fireEvent.press(await screen.findByTestId('pilot-c-confusing'));
    fireEvent.press(screen.getByTestId('pilot-s-major'));
    fireEvent.press(screen.getByTestId('pilot-send'));
    expect(await screen.findByText('Tell us what happened, in 1 to 4,000 characters.')).toBeTruthy();
    picker().__queue.push({ uri: 'file:///s.heic', mimeType: 'image/heic', fileSize: 1000 });
    fireEvent.press(screen.getByTestId('pilot-shot-add'));
    expect(await screen.findByText('Add a PNG or JPEG image.')).toBeTruthy();
    picker().__queue.push({ uri: 'file:///s.png', mimeType: 'image/png', fileSize: 300_000 });
    fireEvent.press(screen.getByTestId('pilot-shot-add'));
    expect(await screen.findByTestId('pilot-shot')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('pilot-body'), 'The next-stop card hides the PIN field.');
    fireEvent.press(screen.getByTestId('pilot-send'));
    expect(await screen.findByText('Thank you — we got it as UAT-1101.')).toBeTruthy();
    expect(server.feedback).toEqual([
      { screenshot: true },
      expect.objectContaining({ app: 'courier', category: 'confusing', severity: 'major', route: '/run', screenshotId: 'shot-1', locale: 'en', body: 'The next-stop card hides the PIN field.' }),
    ]);
    expect(String(server.feedback[1]!.platform)).toMatch(/^Northline Courier · ios /);
  });
});
