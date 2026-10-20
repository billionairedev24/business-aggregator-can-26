import AsyncStorage from '@react-native-async-storage/async-storage';
import { forwardRef, useImperativeHandle } from 'react';
import { Linking, View } from 'react-native';
import { act, fireEvent, renderRouter, screen, waitFor } from 'expo-router/testing-library';

import { memorySecureStorage } from '@northline/mobile-kit';

import { createFixtureServer, type FixtureOptions } from '../src/fixtures/server';
import type { KeyValueStore } from '../src/offline/outbox';
import { createServices, setServices, type Services } from '../src/services';

/** S-151 in the courier app: a drop-off that can't be made, a customer's return collected, earnings and payout setup. */
function memoryStore(): KeyValueStore {
  const data = new Map<string, string>();
  return { getItem: async (k) => data.get(k) ?? null, setItem: async (k, v) => void data.set(k, v), removeItem: async (k) => void data.delete(k) };
}

async function start(options: { fixture?: FixtureOptions; url?: string } = {}) {
  const server = createFixtureServer(options.fixture);
  const services: Services = createServices({ fixtures: server, fetchImpl: server.fetch, secureStorage: memorySecureStorage(), store: memoryStore() });
  setServices(services);
  const p = services.session.beginSignIn();
  await services.session.completeSignIn(`ca.northline.courier:/oauth2redirect?code=c&state=${p.state}`, p);
  const view = renderRouter('./app', { initialUrl: options.url ?? '/' });
  return { server, services, view };
}

/** The camera on: a preview whose shutter answers a photo in the cache. */
function cameraOn() {
  const camera = jest.requireMock('expo-camera') as { CameraView: unknown; useCameraPermissions: jest.Mock };
  const FakeCamera = forwardRef<unknown, object>(function FakeCamera(props, ref) {
    useImperativeHandle(ref, () => ({ takePictureAsync: async () => ({ uri: 'file:///cache/door.jpg', width: 1200, height: 1600 }) }));
    return <View {...props} />;
  });
  const before = camera.CameraView;
  camera.CameraView = FakeCamera;
  camera.useCameraPermissions.mockReturnValue([{ granted: true, canAskAgain: true }, jest.fn()]);
  return () => {
    camera.CameraView = before;
    camera.useCameraPermissions.mockReturnValue([{ granted: false, canAskAgain: true }, jest.fn()]);
  };
}

afterEach(async () => {
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

describe('a drop-off that can’t be made (S-151)', () => {
  async function atTheDoor(options: Parameters<typeof start>[0] = {}) {
    const ctx = await start({ url: '/stops/st-4/dropoff', ...options });
    await act(async () => {
      await ctx.services.courier.pickup('st-1', true, 'k1');
      await ctx.services.courier.pickup('st-2', true, 'k2');
      await ctx.services.queryClient.invalidateQueries();
    });
    return ctx;
  }

  it('needs a reason, a note for “another reason” and a photo at the door; then the order goes back to the shop', async () => {
    const off = cameraOn();
    try {
      const { server, view } = await atTheDoor();
      fireEvent.press(await screen.findByRole('button', { name: "Couldn't deliver" }));
      expect(await screen.findByText("Why couldn't you hand it over?")).toBeTruthy();
      fireEvent.press(screen.getByRole('button', { name: 'Take it back to the business' }));
      expect(await screen.findByText('Choose a reason first.')).toBeTruthy();
      fireEvent.press(screen.getByRole('radio', { name: 'Another reason' }));
      fireEvent.press(screen.getByRole('button', { name: 'Take it back to the business' }));
      expect(await screen.findByText('Say what happened.')).toBeTruthy();
      fireEvent.press(screen.getByRole('radio', { name: 'Nobody answered' }));
      fireEvent.press(screen.getByRole('button', { name: 'Take it back to the business' }));
      expect(await screen.findByText('Take the photo at the door first.')).toBeTruthy();
      fireEvent.press(screen.getByTestId('take-photo'));
      expect(await screen.findByLabelText('Photo of the delivery')).toBeTruthy();
      fireEvent.changeText(screen.getByTestId('fail-note'), 'Knocked twice');
      fireEvent.press(screen.getByRole('button', { name: 'Take it back to the business' }));
      await waitFor(() => expect(server.failures).toEqual([{ stopId: 'st-4', reason: 'absent', note: 'Knocked twice' }]));
      const paths = server.calls.map((c) => c.path).filter((p) => p.includes('/st-4/'));
      expect(paths.map((p) => p.split('/').pop())).toEqual(['proof', 'fail']);
      await waitFor(() => expect(view.getPathname()).toBe('/run'));
      // the order goes back to the shop it came from
      expect(await screen.findByLabelText(/Stop 5 of 5: Return to the business, Fern & Field Grocer/)).toBeTruthy();
    } finally {
      off();
    }
  });

  it('is in French', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValue([{ languageTag: 'fr-CA' }]);
    try {
      await atTheDoor();
      fireEvent.press(await screen.findByRole('button', { name: 'Livraison impossible' }));
      expect(await screen.findByText('Pourquoi n’avez-vous pas pu la remettre?')).toBeTruthy();
      expect(screen.getByRole('radio', { name: 'Personne n’a répondu' })).toBeTruthy();
    } finally {
      getLocales.mockReturnValue([{ languageTag: 'en-CA' }]);
    }
  });
});

describe('a customer’s return on the run (S-151)', () => {
  it('collects it at the door, then brings it to the shop', async () => {
    const { server } = await start({ url: '/stops/st-5', fixture: { withReturn: true } });
    expect(await screen.findByText('Return RT-1201')).toBeTruthy();
    expect(screen.getByText(/Collect return RT-1201 from the customer at this address/)).toBeTruthy();
    expect(screen.getByText('9 Example Court, Sampleville A1C 3D4')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Collected' }));
    await waitFor(() => expect(server.run!.stops.find((s) => s.id === 'st-5')!.state).toBe('done'));
    expect(server.calls.some((c) => c.path.endsWith('/stops/st-5/collect'))).toBe(true);
  });
});

describe('earnings and payouts (S-151)', () => {
  it('shows today, this week and each run, and opens Stripe’s payout setup', async () => {
    const { server } = await start({ url: '/earnings' });
    expect(await screen.findByTestId('earnings-screen')).toBeTruthy();
    expect(screen.getByTestId('earnings-today')).toBeTruthy();
    expect(screen.getAllByText('$19.00').length).toBeGreaterThan(0); // today: $16.00 pay + $3.00 tips
    expect(screen.getByText('Not paid out yet: $19.00')).toBeTruthy();
    expect(screen.getByText(/2 pickups · 2 drop-offs · 0 failed/)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Set up payouts' }));
    await waitFor(() => expect(Linking.openURL).toHaveBeenCalledWith('https://connect.stripe.example/setup/fixture'));
    expect(server.payout.onboardingLinks).toBe(1);
  });

  it('says where payouts go once set up, in French', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValue([{ languageTag: 'fr-CA' }]);
    try {
      await start({ url: '/earnings', fixture: { payoutsEnabled: true } });
      expect(await screen.findByText('Versé chaque semaine dans le compte bancaire se terminant par 6789.')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Configurer les versements' })).toBeNull();
      expect(screen.getByText(/versé$/)).toBeTruthy();
    } finally {
      getLocales.mockReturnValue([{ languageTag: 'en-CA' }]);
    }
  });

  it('opens from the profile', async () => {
    const { view } = await start({ url: '/account' });
    fireEvent.press(await screen.findByRole('button', { name: 'Earnings and payouts' }));
    await waitFor(() => expect(view.getPathname()).toBe('/earnings'));
  });
});
