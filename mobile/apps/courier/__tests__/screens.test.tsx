import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, renderRouter, screen, waitFor } from 'expo-router/testing-library';
import * as WebBrowser from 'expo-web-browser';
import * as Location from 'expo-location';

import { memorySecureStorage } from '@northline/mobile-kit';

import { createFixtureServer, FIXTURE_PIN, type FixtureOptions } from '../src/fixtures/server';
import type { KeyValueStore } from '../src/offline/outbox';
import { createServices, setServices, type Services } from '../src/services';

function memoryStore(): KeyValueStore {
  const data = new Map<string, string>();
  return { getItem: async (k) => data.get(k) ?? null, setItem: async (k, v) => void data.set(k, v), removeItem: async (k) => void data.delete(k) };
}

/** The app on the fixture backend (the same in-memory api the web smoke test uses), optionally already signed in. */
async function start(options: { signedIn?: boolean; fixture?: FixtureOptions; wrap?: (f: typeof fetch) => typeof fetch; url?: string } = {}) {
  const server = createFixtureServer(options.fixture);
  const services: Services = createServices({
    fixtures: server,
    fetchImpl: options.wrap ? options.wrap(server.fetch) : server.fetch,
    secureStorage: memorySecureStorage(),
    store: memoryStore(),
  });
  setServices(services);
  if (options.signedIn !== false) {
    const p = services.session.beginSignIn();
    await services.session.completeSignIn(`ca.northline.courier:/oauth2redirect?code=c&state=${p.state}`, p);
  }
  const view = renderRouter('./app', { initialUrl: options.url ?? '/' });
  return { server, services, view };
}

afterEach(async () => {
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear(); // the language choice
});

describe('signing in', () => {
  it('opens the system browser with the courier client, PKCE and the registered redirect, then shows the shift', async () => {
    const { view } = await start({ signedIn: false });
    expect(await screen.findByText('Courier sign-in')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
    await screen.findByText('On a run');
    const [url, redirect] = (WebBrowser.openAuthSessionAsync as jest.Mock).mock.calls[0];
    const q = new URL(url).searchParams;
    expect(q.get('client_id')).toBe('courier-app');
    expect(q.get('redirect_uri')).toBe('ca.northline.courier:/oauth2redirect');
    expect(q.get('code_challenge_method')).toBe('S256');
    expect(q.get('scope')).toBe('openid courier deliveries');
    expect(redirect).toBe('ca.northline.courier:/oauth2redirect');
    expect(view.getPathname()).toBe('/');
  });

  it('says so when the browser sign-in is cancelled', async () => {
    (WebBrowser.openAuthSessionAsync as jest.Mock).mockResolvedValueOnce({ type: 'cancel' });
    await start({ signedIn: false });
    fireEvent.press(await screen.findByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Sign-in was cancelled.')).toBeTruthy();
  });

  it('is in French on a French phone, and the language can be switched', async () => {
    await start({ signedIn: false });
    fireEvent.press(await screen.findByRole('tab', { name: 'Français' }));
    expect(await screen.findByText('Connexion coursier')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Se connecter' })).toBeTruthy();
  });
});

describe('the shift', () => {
  it('starts a scheduled shift', async () => {
    const { server } = await start({ fixture: { shiftOn: false, withRun: false } });
    fireEvent.press(await screen.findByRole('button', { name: 'Start shift' }));
    expect(await screen.findByText('On shift · waiting for a run')).toBeTruthy();
    expect(server.shift.state).toBe('on');
    expect(screen.getByText(/Dispatch assigns runs/)).toBeTruthy();
  });

  it('cannot end while a run is open', async () => {
    await start();
    const end = await screen.findByRole('button', { name: 'End shift' });
    expect(end.props.accessibilityState).toMatchObject({ disabled: true });
    expect(screen.getAllByText('Finish your run before you end the shift.').length).toBeGreaterThan(0);
  });
});

describe('the run', () => {
  it('lists the stops in order, pickups first, with the next one marked', async () => {
    await start({ url: '/run' });
    const cards = await screen.findAllByLabelText(/^Stop \d of 4/);
    expect(cards.map((c) => c.props.accessibilityLabel)).toEqual([
      'Stop 1 of 4: Pickup, Juniper Bakery, To do',
      'Stop 2 of 4: Pickup, Fern & Field Grocer, To do',
      'Stop 3 of 4: Drop-off, 1204 Example Ave, To do',
      'Stop 4 of 4: Drop-off, 57 Sample Street, To do',
    ]);
    expect(screen.getAllByText('Next')).toHaveLength(1);
  });

  it('explains location sharing before asking, then shares it in the background while the run is open', async () => {
    await start({ url: '/run' });
    expect(await screen.findByText('Share your location during this run')).toBeTruthy();
    expect(screen.getByText(/keeps your latest position only/)).toBeTruthy();
    (Location.getForegroundPermissionsAsync as jest.Mock).mockResolvedValue({ status: 'granted' });
    (Location.getBackgroundPermissionsAsync as jest.Mock).mockResolvedValue({ status: 'granted' });
    fireEvent.press(screen.getByRole('button', { name: 'Share location' }));
    expect(await screen.findByText('Location shared while this run is open.')).toBeTruthy();
    expect(Location.startLocationUpdatesAsync).toHaveBeenCalledWith(
      'nl-courier-run-location',
      expect.objectContaining({ timeInterval: 4000, foregroundService: expect.objectContaining({ notificationTitle: 'On a run' }) }),
    );
  });

  it('shows a stop: the address, the note, no phone number; arriving goes to the api with an idempotency key', async () => {
    const { server } = await start({ url: '/stops/st-3' });
    expect(await screen.findByText('1204 Example Ave, Sampleville A1A 1A1')).toBeTruthy();
    expect(screen.getByText('Unit 804')).toBeTruthy();
    expect(screen.getByText('Buzz 0804')).toBeTruthy();
    expect(screen.queryByText(/call/i)).toBeNull();
    expect(screen.getByText(/phone numbers aren't shared/)).toBeTruthy();
    // a drop-off before its pickups: not yet
    expect(screen.getByRole('button', { name: 'Hand over' }).props.accessibilityState).toMatchObject({ disabled: true });
    fireEvent.press(screen.getByRole('button', { name: "I've arrived" }));
    await waitFor(() => expect(server.run!.stops.find((s) => s.id === 'st-3')!.state).toBe('arrived'));
    const call = server.calls.find((c) => c.path.endsWith('/stops/st-3/arrive'))!;
    expect(call.idempotencyKey).toMatch(/[0-9a-f-]{36}/);
  });

  it('confirms a pickup once the bag is sealed', async () => {
    const { server, view } = await start({ url: '/stops/st-1/pickup' });
    const confirm = await screen.findByRole('button', { name: 'Confirm pickup' });
    expect(confirm.props.accessibilityState).toMatchObject({ disabled: true });
    fireEvent(screen.getByTestId('sealed'), 'valueChange', true);
    fireEvent.press(screen.getByRole('button', { name: 'Confirm pickup' }));
    await waitFor(() => expect(server.run!.stops.find((s) => s.id === 'st-1')!.state).toBe('done'));
    await waitFor(() => expect(view.getPathname()).toBe('/run'));
  });

  it("shows the api's refusal when the shop hasn't packed", async () => {
    await start({ url: '/stops/st-1/pickup', fixture: { packed: false } });
    expect(await screen.findByText(/Wait until the shop has packed/)).toBeTruthy();
    fireEvent(screen.getByTestId('sealed'), 'valueChange', true);
    fireEvent.press(screen.getByRole('button', { name: 'Confirm pickup' }));
    expect(await screen.findByText("Couldn't save: This shop hasn't packed the order yet.")).toBeTruthy();
  });
});

describe('the drop-off', () => {
  async function pickedUp(options: Parameters<typeof start>[0] = {}) {
    const ctx = await start({ url: '/stops/st-4/dropoff', ...options });
    await act(async () => {
      await ctx.services.courier.pickup('st-1', true, 'k1');
      await ctx.services.courier.pickup('st-2', true, 'k2');
      await ctx.services.queryClient.invalidateQueries();
    });
    return ctx;
  }

  it('checks the PIN has 4 digits, then completes with it', async () => {
    const { server } = await pickedUp();
    fireEvent.press(await screen.findByRole('tab', { name: 'PIN' }));
    fireEvent.changeText(screen.getByLabelText("Customer's PIN"), '12');
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    expect(await screen.findByText("Enter the customer's 4-digit PIN.")).toBeTruthy();
    fireEvent.changeText(screen.getByLabelText("Customer's PIN"), FIXTURE_PIN);
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    await waitFor(() => expect(server.run!.stops.find((s) => s.id === 'st-4')!.state).toBe('done'));
  });

  it("shows the api's message for a wrong PIN", async () => {
    await pickedUp();
    fireEvent.press(await screen.findByRole('tab', { name: 'PIN' }));
    fireEvent.changeText(screen.getByLabelText("Customer's PIN"), '0000');
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    expect(await screen.findByText(/That PIN doesn't match/)).toBeTruthy();
  });

  it('asks for the camera before a photo, and needs a signature before completing with one', async () => {
    await pickedUp();
    expect(await screen.findByRole('button', { name: 'Allow camera' })).toBeTruthy();
    fireEvent.press(screen.getByRole('tab', { name: 'Signature' }));
    expect(screen.getByLabelText('Signature box. Draw with one finger.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    expect(await screen.findByText('Ask the customer to sign first.')).toBeTruthy();
  });

  it('completes with the signature drawn on the pad: the PNG upload, then the drop-off', async () => {
    const { server } = await pickedUp();
    fireEvent.press(await screen.findByRole('tab', { name: 'Signature' }));
    const pad = screen.getByTestId('signature-pad');
    fireEvent(pad, 'layout', { nativeEvent: { layout: { width: 300, height: 200 } } });
    const at = (x: number, y: number) => ({ nativeEvent: { locationX: x, locationY: y, touches: [{}], changedTouches: [{}], timestamp: 0 }, touchHistory: { touchBank: [] } });
    fireEvent(pad, 'responderGrant', at(20, 100));
    for (const x of [60, 120, 180, 240]) fireEvent(pad, 'responderMove', at(x, 100 + (x % 40)));
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    await waitFor(() => expect(server.run!.stops.find((s) => s.id === 'st-4')!.state).toBe('done'));
    expect(server.run!.stops.find((s) => s.id === 'st-4')!.proofKind).toBe('signature');
    const paths = server.calls.map((c) => c.path).filter((p) => p.includes('/st-4/'));
    expect(paths.map((p) => p.split('/').pop())).toEqual(['proof', 'dropoff']);
  });

  it('keeps a drop-off made offline on the phone and sends it when the connection is back', async () => {
    let offline = false;
    const { server, services } = await pickedUp({
      wrap: (f) => (async (input: RequestInfo | URL, init?: RequestInit) => {
        if (offline && String(input).includes('/courier/stops/')) throw new TypeError('Network request failed');
        return f(input, init);
      }) as typeof fetch,
    });
    offline = true;
    fireEvent.press(await screen.findByRole('tab', { name: 'PIN' }));
    fireEvent.changeText(screen.getByLabelText("Customer's PIN"), FIXTURE_PIN);
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    expect(await screen.findByText(/1 action saved on this phone/)).toBeTruthy();
    expect(await screen.findByLabelText(/Stop 4 of 4: Drop-off, 57 Sample Street, Saved on phone/)).toBeTruthy();
    expect(server.run!.stops.find((s) => s.id === 'st-4')!.state).toBe('pending');
    offline = false;
    await act(async () => {
      services.outbox.wake();
      await services.outbox.flush();
    });
    expect(server.run!.stops.find((s) => s.id === 'st-4')!.state).toBe('done');
    const keys = server.calls.filter((c) => c.path.endsWith('/stops/st-4/dropoff')).map((c) => c.idempotencyKey);
    expect(new Set(keys).size).toBe(1);
  });
});

describe('age-restricted drop-offs (2026-10-04)', () => {
  async function restricted(options: Parameters<typeof start>[0] = {}) {
    const ctx = await start({ url: '/stops/st-3/dropoff', fixture: { idCheck: true }, ...options });
    await act(async () => {
      await ctx.services.courier.pickup('st-1', true, 'k1');
      await ctx.services.queryClient.invalidateQueries();
    });
    return ctx;
  }

  it('asks for the three ID confirmations before the hand-over and sends only the answers', async () => {
    const { server } = await restricted();
    expect(await screen.findByText('Check photo ID before you hand it over')).toBeTruthy();
    expect(screen.getByText('The ID must show: Sam Example')).toBeTruthy();
    fireEvent.press(screen.getByRole('tab', { name: 'PIN' }));
    fireEvent.changeText(screen.getByLabelText("Customer's PIN"), FIXTURE_PIN);
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    expect(await screen.findByText(/Confirm all three before you hand it over/)).toBeTruthy();
    expect(server.calls.some((c) => c.path.endsWith('/stops/st-3/dropoff'))).toBe(false);
    fireEvent.press(screen.getByRole('checkbox', { name: 'I checked a valid government photo ID' }));
    fireEvent.press(screen.getByRole('checkbox', { name: 'The name and photo match the person in front of me' }));
    fireEvent.press(screen.getByRole('checkbox', { name: 'The ID shows they are 19 or older' }));
    expect(screen.getByRole('checkbox', { name: 'The ID shows they are 19 or older' }).props.accessibilityState).toMatchObject({ checked: true });
    fireEvent.press(screen.getByRole('button', { name: 'Complete drop-off' }));
    await waitFor(() => expect(server.run!.stops.find((s) => s.id === 'st-3')!.state).toBe('done'));
    expect(server.idChecks).toEqual([{ stopId: 'st-3', outcome: 'passed' }]);
  });

  it("can't hand it over: a reason, then a return stop back to the business", async () => {
    const { server, view } = await restricted();
    fireEvent.press(await screen.findByRole('button', { name: "Can't hand it over" }));
    fireEvent.press(await screen.findByRole('button', { name: 'Take it back to the business' }));
    expect(await screen.findByText('Choose a reason first.')).toBeTruthy();
    fireEvent.press(screen.getByRole('radio', { name: 'Nobody of age is here' }));
    fireEvent.press(screen.getByRole('button', { name: 'Take it back to the business' }));
    await waitFor(() => expect(server.idChecks).toEqual([{ stopId: 'st-3', outcome: 'refused', reason: 'nobody_of_age' }]));
    await waitFor(() => expect(view.getPathname()).toBe('/run'));
    expect(await screen.findByLabelText('Stop 5 of 5: Return to the business, Juniper Bakery, To do')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Stop 5 of 5: Return to the business, Juniper Bakery, To do'));
    expect(await screen.findByText(/Bring it back to the business/)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: "It's back at the business" }));
    await waitFor(() => expect(server.run!.stops.find((s) => s.kind === 'return')!.state).toBe('done'));
  });

  it('a refusal made offline waits on the phone, in French', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValue([{ languageTag: 'fr-CA' }]);
    let offline = false;
    const { server, services } = await restricted({
      wrap: (f) => (async (input: RequestInfo | URL, init?: RequestInit) => {
        if (offline && String(input).includes('/courier/stops/')) throw new TypeError('Network request failed');
        return f(input, init);
      }) as typeof fetch,
    });
    try {
      expect(await screen.findByText('Vérifiez la pièce d’identité avant de remettre la commande')).toBeTruthy();
      expect(screen.getByRole('checkbox', { name: 'La pièce indique que la personne a 19 ans ou plus' })).toBeTruthy();
      offline = true;
      fireEvent.press(screen.getByRole('button', { name: 'Impossible de la remettre' }));
      fireEvent.press(await screen.findByRole('radio', { name: 'Aucune pièce d’identité avec photo' }));
      fireEvent.press(screen.getByRole('button', { name: 'La rapporter au commerce' }));
      await waitFor(() => expect(services.outbox.snapshot.pending.map((a) => a.kind)).toEqual(['refuse']));
      expect(JSON.stringify(services.outbox.snapshot.pending)).not.toMatch(/Sam Example/);
      offline = false;
      await act(async () => {
        services.outbox.wake();
        await services.outbox.flush();
      });
      expect(server.idChecks).toEqual([{ stopId: 'st-3', outcome: 'refused', reason: 'no_id' }]);
    } finally {
      getLocales.mockReturnValue([{ languageTag: 'en-CA' }]);
    }
  });
});

describe('the account', () => {
  it('warns before signing out with unsent actions, then signs out', async () => {
    const { services } = await start({ url: '/account', wrap: (f) => (async (i: RequestInfo | URL, o?: RequestInit) => {
      if (String(i).includes('/courier/stops/')) throw new TypeError('offline');
      return f(i, o);
    }) as typeof fetch });
    await act(async () => void (await services.outbox.enqueue({ kind: 'arrive', stopId: 'st-1' })));
    fireEvent.press(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByText(/1 action hasn't been sent yet/)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Sign out anyway' }));
    expect(await screen.findByText('Courier sign-in')).toBeTruthy();
    expect(services.outbox.snapshot.pending).toEqual([]);
  });

  it('tells a person who is not a courier', async () => {
    await start({
      wrap: (f) => (async (i: RequestInfo | URL, o?: RequestInit) =>
        String(i).endsWith('/courier/me') ? new Response(JSON.stringify({ code: 'not_a_courier' }), { status: 403 }) : f(i, o)) as typeof fetch,
    });
    expect(await screen.findByText(/isn't set up as a Northline courier/)).toBeTruthy();
  });
});
