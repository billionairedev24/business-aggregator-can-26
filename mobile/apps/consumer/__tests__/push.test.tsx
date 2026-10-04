import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { PushHooks } from '@northline/mobile-kit';

import { seedOrder } from '../src/fixtures/shop';
import { PUSH_ASKED_KEY } from '../src/push/PushPrompt';
import { setPushAvailable } from '../src/push/install';
import { setServices } from '../src/services';
import { start } from './support';

jest.mock('@stripe/stripe-react-native', () => ({}));

interface PhoneMock {
  state: { permission: string; token: string | null; asked: number };
  tap(data: Record<string, unknown>): void;
  launchWith(data: Record<string, unknown> | null): void;
  reset(): void;
}
const notifications = () => jest.requireMock('expo-notifications') as { __phone: PhoneMock; requestPermissionsAsync: jest.Mock; setNotificationChannelAsync: jest.Mock };
const phone = () => notifications().__phone;
const HOME = { label: '1204 Example Ave, Sampleville', city: 'Sampleville', province: 'XA', street: '1204 Example Ave', postalCode: 'A1A 1A1', marketId: 'mkt-sampleville' };
const flush = () => act(async () => new Promise((r) => setTimeout(r, 0)));

beforeEach(() => setPushAvailable(true));
afterEach(async () => {
  setPushAvailable(false);
  phone().reset();
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

describe('push in the consumer app (mobile gaps part 1, expo-notifications mocked)', () => {
  it('registers this phone at start without asking; asks only after the order is placed, from the card', async () => {
    const { server, store } = await start({ signedIn: true, store: { 'nl.location': JSON.stringify(HOME) }, url: '/home' });
    await screen.findByTestId('home');
    // at launch: the registry hears "undetermined", no token, and the system prompt is not shown
    await waitFor(() => expect(server.shop.devices.size).toBe(1));
    expect([...server.shop.devices.values()][0]).toMatchObject({ platform: 'ios', permission: 'undetermined', locale: 'en-CA' });
    expect([...server.shop.devices.values()][0]).not.toHaveProperty('token');
    expect(notifications().requestPermissionsAsync).not.toHaveBeenCalled();
    expect(PushHooks.installed).toBe(true);

    // the order is placed: "Turn on notifications" on Order confirmed asks the system, then registers the token
    seedOrder(server.shop, Date.now(), 'placed');
    act(() => {
      require('expo-router').router.push('/orders/ord-1001/confirmed');
    });
    fireEvent.press(await screen.findByTestId('push-prompt-on'));
    await waitFor(() => expect([...server.shop.devices.values()][0]).toMatchObject({ permission: 'granted', token: 'apns-device-token-0001' }));
    expect(phone().state.asked).toBe(1);
    await waitFor(() => expect(screen.queryByTestId('push-prompt')).toBeNull());
    expect(store.data.get(PUSH_ASKED_KEY)).toBe('1');
  });

  it('“Not now” hides the card for good; the settings row still turns it on', async () => {
    const { server, store } = await start({ signedIn: true, store: { 'nl.location': JSON.stringify(HOME) }, url: '/home' });
    await screen.findByTestId('home');
    seedOrder(server.shop, Date.now(), 'placed');
    act(() => {
      require('expo-router').router.push('/orders/ord-1001/confirmed');
    });
    fireEvent.press(await screen.findByTestId('push-prompt-later'));
    await waitFor(() => expect(store.data.get(PUSH_ASKED_KEY)).toBe('1'));
    expect(phone().state.asked).toBe(0);
    act(() => {
      require('expo-router').router.push('/account/notifications');
    });
    expect(await screen.findByText('This phone')).toBeTruthy();
    fireEvent.press(await screen.findByTestId('push-enable'));
    expect(await screen.findByText('Push notifications come to this phone.')).toBeTruthy();
    await waitFor(() => expect([...server.shop.devices.values()][0]).toMatchObject({ permission: 'granted', token: 'apns-device-token-0001' }));
  });

  it('follows the platform’s new token and routes a tap to the screen of its link (never another host’s)', async () => {
    phone().state.permission = 'granted';
    const { server, view } = await start({ signedIn: true, store: { 'nl.location': JSON.stringify(HOME) }, url: '/home' });
    await screen.findByTestId('home');
    seedOrder(server.shop, Date.now(), 'picked_up');
    await waitFor(() => expect([...server.shop.devices.values()][0]).toMatchObject({ permission: 'granted', token: 'apns-device-token-0001' }));
    act(() => {
      phone().state.token = 'apns-device-token-0002';
      (phone().state as unknown as { tokenListeners: Set<(t: { data: unknown }) => void> }).tokenListeners.forEach((l) => l({ data: 'apns-device-token-0002' }));
    });
    await waitFor(() => expect([...server.shop.devices.values()][0]).toMatchObject({ token: 'apns-device-token-0002' }));
    act(() => phone().tap({ link: 'https://evil.example/app/orders/ord-1001' }));
    expect(view.getPathname()).toBe('/home');
    act(() => phone().tap({ type: 'order', link: 'http://localhost:3000/app/orders/ord-1001' }));
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-1001/track'));
  });

  it('opens the screen of the notification that launched the app', async () => {
    phone().launchWith({ type: 'booking', link: 'ca.northline.app://orders/ord-1001' });
    const { server, view } = await start({ signedIn: true, store: { 'nl.location': JSON.stringify(HOME) }, url: '/home' });
    seedOrder(server.shop, Date.now(), 'picked_up');
    await waitFor(() => expect(view.getPathname()).toBe('/orders/ord-1001/track'));
  });

  it('removes this phone at sign-out, before the tokens are revoked', async () => {
    const { server } = await start({ signedIn: true, url: '/account' });
    expect(await screen.findByText('Ada Example')).toBeTruthy();
    await waitFor(() => expect(server.shop.devices.size).toBe(1));
    fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(server.shop.devices.size).toBe(0));
    expect(server.auth.revoked).toEqual(['fixture-refresh-1']);
    const deleted = server.calls.findIndex((c) => c.method === 'DELETE' && c.path.startsWith('/me/devices/'));
    expect(server.calls[deleted]!.signed).toBe(true);
  });
});
