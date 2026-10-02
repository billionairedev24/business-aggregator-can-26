import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { screen as routerScreen } from 'expo-router/testing-library';
import * as WebBrowser from 'expo-web-browser';
import type { ReactNode } from 'react';

import { ApiError, NetworkError, SignedOutError } from '@northline/mobile-kit';

import { I18nProvider } from '../src/i18n';
import { createServices, setServices } from '../src/services';
import { EmptyState, ErrorState, QueryView } from '../src/ui/states';
import { start } from './support';

afterEach(async () => {
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

describe('the shell', () => {
  it('opens on Welcome the first time, and on Home with the five tabs afterwards', async () => {
    const first = await start();
    expect(await routerScreen.findByTestId('welcome')).toBeTruthy();
    expect(first.view.getPathname()).toBe('/welcome');
    first.view.unmount();

    const again = await start({ welcomed: true });
    expect(await routerScreen.findByTestId('home')).toBeTruthy();
    expect(again.view.getPathname()).toBe('/home');
    const tabs = routerScreen.getAllByRole('tab').map((t) => t.props.accessibilityLabel);
    expect(tabs).toEqual(['Home', 'Services', 'Cart', 'Orders', 'You']);
    expect(routerScreen.getByRole('tab', { name: 'Home' }).props.accessibilityState).toEqual({ selected: true });
  });

  it('moves between tabs, the current one marked', async () => {
    const { view } = await start({ welcomed: true });
    fireEvent.press(await routerScreen.findByRole('tab', { name: 'Services' }));
    expect(await routerScreen.findByTestId('stub-services')).toBeTruthy();
    expect(view.getPathname()).toBe('/services');
    expect(routerScreen.getByRole('tab', { name: 'Services' }).props.accessibilityState).toEqual({ selected: true });
  });

  it('opens sub-screens with the design header and its "← Back"', async () => {
    const { view } = await start({ welcomed: true, url: '/orders/NL-48213/track' });
    expect(await routerScreen.findByText('Order NL-48213')).toBeTruthy();
    expect(routerScreen.getByRole('button', { name: 'Back' })).toBeTruthy();
    fireEvent.press(routerScreen.getByRole('button', { name: 'Back' }));
    expect(view.getPathname()).toBe('/home');
  });

  it('asks a guest to sign in on a personal screen, and shows it once signed in', async () => {
    await start({ welcomed: true, url: '/wallet' });
    expect(await routerScreen.findByTestId('sign-in-prompt')).toBeTruthy();
    setServices(null);
    await start({ signedIn: true, url: '/wallet' });
    expect(await routerScreen.findByText('This screen is being built (S-101).')).toBeTruthy();
  });

  it('is in French on a French phone', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValueOnce([{ languageTag: 'fr-CA' }]);
    await start({ welcomed: true });
    expect((await routerScreen.findAllByRole('tab')).map((t) => t.props.accessibilityLabel)).toEqual(['Accueil', 'Services', 'Panier', 'Commandes', 'Vous']);
  });

  it('keeps one random guest id per installation and sends it on api calls (the guest cart, S-51)', async () => {
    const { services, store, server } = await start({ welcomed: true });
    await routerScreen.findByTestId('home');
    const id = services.guestId();
    expect(id).toMatch(/^g_[A-Za-z0-9_-]{16,}$/);
    expect(store.data.get('nl.app.guestId')).toBe(id);
    await services.api.get('/geo/markets', { auth: 'optional' });
    expect(server.calls.at(-1)).toMatchObject({ path: '/geo/markets', signed: false, guest: id });
  });

  it('shows the offline banner while the phone has no connection', async () => {
    await start({ welcomed: true });
    await routerScreen.findByTestId('home');
    const netinfo = jest.requireMock('@react-native-community/netinfo') as { __emit: (s: object) => void };
    act(() => netinfo.__emit({ isConnected: false, isInternetReachable: false }));
    expect(await routerScreen.findByText("You're offline. Northline will catch up when you're back online.")).toBeTruthy();
    act(() => netinfo.__emit({ isConnected: true, isInternetReachable: true }));
    expect(routerScreen.queryByTestId('offline-banner')).toBeNull();
  });
});

describe('signing in in the browser (consumer site page, RFC 8252)', () => {
  it('opens the mobile-consumer authorization request with PKCE and the app scheme, then signs in', async () => {
    const { services, view, server } = await start({ welcomed: true, url: '/sign-in', store: { 'nl.location': JSON.stringify({ label: 'Old Town', city: 'Sampleville' }) } });
    // fixture mode completes without a browser; the real path is the system browser (next test)
    fireEvent.press(await routerScreen.findByRole('button', { name: 'Sign in with a passkey' }));
    expect(await routerScreen.findByTestId('home')).toBeTruthy();
    expect(view.getPathname()).toBe('/home');
    expect(await services.session.restore()).toBe(true);
    expect(server.calls.filter((c) => c.path === '/oauth2/token')).toHaveLength(2); // use_dpop_nonce, then tokens
  });

  it('builds the request for the system browser with the registered redirect', () => {
    const s = createServices({ fixtures: null, fetchImpl: jest.fn() as unknown as typeof fetch });
    const q = new URL(s.session.beginSignIn().url).searchParams;
    expect(q.get('client_id')).toBe('mobile-consumer');
    expect(q.get('redirect_uri')).toBe('ca.northline.app:/oauth2redirect');
    expect(q.get('code_challenge_method')).toBe('S256');
    expect(q.get('scope')).toBe('openid profile orders bookings offline_access');
    expect(WebBrowser.openAuthSessionAsync).not.toHaveBeenCalled();
  });
});

describe('the states every screen implements', () => {
  const wrap = (node: ReactNode) => render(<I18nProvider initial="en">{node}</I18nProvider>);
  const query = <T,>(over: Partial<{ data: T; error: unknown; isPending: boolean; isError: boolean }>) => ({
    data: undefined as T | undefined,
    error: null as unknown,
    isPending: false,
    isError: false,
    refetch: jest.fn(),
    ...over,
  });

  it('loading: a skeleton', () => {
    wrap(<QueryView query={query<string[]>({ isPending: true })}>{() => null}</QueryView>);
    expect(screen.getByTestId('loading')).toBeTruthy();
  });

  it('empty: one line and the primary action', () => {
    const onAction = jest.fn();
    wrap(
      <QueryView query={query({ data: [] as string[] })} isEmpty={(d) => d.length === 0} empty={<EmptyState message="Nothing yet." action="Browse" onAction={onAction} />}>
        {() => null}
      </QueryView>,
    );
    fireEvent.press(screen.getByRole('button', { name: 'Browse' }));
    expect(onAction).toHaveBeenCalled();
  });

  it('error: the reason in words and Retry; data already shown stays', () => {
    const q = query({ data: ['a'], error: new NetworkError(), isError: true });
    wrap(<QueryView query={q}>{(d) => <>{d.map((x) => <EmptyState key={x} message={`row ${x}`} />)}</>}</QueryView>);
    expect(screen.getByText("We couldn't reach Northline. Check your connection and try again.")).toBeTruthy();
    expect(screen.getByText('row a')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(q.refetch).toHaveBeenCalled();
  });

  it("error: the api's own message, a busy server, and a sign-in that ended", () => {
    wrap(<ErrorState error={new ApiError(422, 'x', undefined, [{ field: 'q', message: 'Choose a city.' }])} />);
    expect(screen.getByText('Choose a city.')).toBeTruthy();
    screen.unmount();
    wrap(<ErrorState error={new ApiError(503, undefined, undefined)} onRetry={jest.fn()} />);
    expect(screen.getByText('Northline is having trouble right now. Try again in a moment.')).toBeTruthy();
    screen.unmount();
    wrap(<ErrorState error={new SignedOutError()} />);
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy();
  });
});
