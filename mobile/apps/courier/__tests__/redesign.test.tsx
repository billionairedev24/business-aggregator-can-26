import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, renderRouter, screen, waitFor } from 'expo-router/testing-library';
import { Linking, StyleSheet } from 'react-native';

import { memorySecureStorage } from '@northline/mobile-kit';

import type { Stop } from '../src/api/courier';
import { createFixtureServer } from '../src/fixtures/server';
import type { KeyValueStore } from '../src/offline/outbox';
import { createServices, setServices } from '../src/services';
import { navigationUrl } from '../src/stops';

/**
 * Redesign phase 4 (docs/design/redesign-spec.md § 2.5, AT driver layouts with our brand): the tabs Today · Route ·
 * Profile, the logo from brand.json, the stop's "Navigate" hand-off to the phone's maps app, and every control a
 * finger presses on the key screens named, with a role and a ≥ 44 pt target.
 */
function memoryStore(): KeyValueStore {
  const data = new Map<string, string>();
  return { getItem: async (k) => data.get(k) ?? null, setItem: async (k, v) => void data.set(k, v), removeItem: async (k) => void data.delete(k) };
}

async function start(url = '/', signedIn = true) {
  const server = createFixtureServer();
  const services = createServices({ fixtures: server, fetchImpl: server.fetch, secureStorage: memorySecureStorage(), store: memoryStore() });
  setServices(services);
  if (signedIn) {
    const p = services.session.beginSignIn();
    await services.session.completeSignIn(`ca.northline.courier:/oauth2redirect?code=c&state=${p.state}`, p);
  }
  return renderRouter('./app', { initialUrl: url });
}

afterEach(async () => {
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

type Node = typeof screen.root;
const PRESS_ROLES = new Set(['button', 'link', 'tab', 'checkbox', 'radio', 'switch']);

function audit(): string[] {
  const problems: string[] = [];
  const nodes = screen.root.findAll((n: Node) => typeof n.type === 'string' && n.props.accessible !== false && (typeof n.props.onClick === 'function' || typeof n.props.onResponderRelease === 'function'), { deep: true });
  for (const n of nodes) {
    if (['TextInput', 'RCTSinglelineTextInputView'].includes(String(n.type))) continue;
    const label = String(n.props.accessibilityLabel ?? '');
    const style = StyleSheet.flatten(typeof n.props.style === 'function' ? n.props.style({ pressed: false }) : n.props.style) ?? {};
    const slop = typeof n.props.hitSlop === 'number' ? 2 * n.props.hitSlop : 0;
    const h = Math.max(Number(style.minHeight ?? 0) || 0, Number(style.height ?? 0) || 0) + slop;
    if (!n.props.accessibilityRole || !PRESS_ROLES.has(n.props.accessibilityRole)) problems.push(`role ${n.props.accessibilityRole ?? 'missing'}: ${label}`);
    if (!label) problems.push(`no name: ${n.props.testID ?? n.type}`);
    if (h < 44) problems.push(`target ${h} pt: ${label || n.props.testID}`);
  }
  return problems;
}

describe('the courier app on the driver layouts', () => {
  it('has the tabs Today · Route · Profile, the current one marked', async () => {
    const view = await start();
    await screen.findByTestId('shift-screen');
    expect(screen.getAllByRole('tab').filter((t) => /^(Today|Route|Profile)$/.test(t.props.accessibilityLabel)).map((t) => t.props.accessibilityLabel)).toEqual(['Today', 'Route', 'Profile']);
    expect(screen.getByRole('tab', { name: 'Today' }).props.accessibilityState).toEqual({ selected: true });
    fireEvent.press(screen.getByRole('tab', { name: 'Route' }));
    expect(await screen.findByTestId('run-screen')).toBeTruthy();
    expect(view.getPathname()).toBe('/run');
    fireEvent.press(screen.getByRole('tab', { name: 'Profile' }));
    expect(await screen.findByTestId('account-screen')).toBeTruthy();
  });

  it('draws the brand\'s logo with the portal word on sign-in', async () => {
    await start('/', false);
    expect(await screen.findByRole('image', { name: 'Northline Courier' })).toBeTruthy();
  });

  it('hands a stop to the phone\'s maps app with directions', async () => {
    await start('/stops/st-3');
    fireEvent.press(await screen.findByRole('button', { name: 'Navigate' }));
    await waitFor(() => expect(Linking.openURL).toHaveBeenCalledWith(expect.stringMatching(/^maps:\?daddr=/)));
  });

  it('navigationUrl: Apple Maps directions on iPhone, the maps chooser on Android', () => {
    const stop = { kind: 'dropoff', place: null, dropoff: { street: '1 Test St', city: 'Testville', postal: 'A1A 1A1', lat: 1.5, lng: -2.25 } } as unknown as Stop;
    expect(navigationUrl(stop, 'ios')).toBe('maps:?daddr=1.5%2C-2.25&dirflg=d');
    expect(navigationUrl(stop, 'android')).toBe('geo:0,0?q=1.5%2C-2.25');
    expect(navigationUrl(stop, 'web')).toBe('https://www.google.com/maps/dir/?api=1&destination=1.5%2C-2.25');
    expect(navigationUrl({ ...stop, dropoff: null } as unknown as Stop, 'ios')).toBeNull();
  });

  it.each([
    ['today', '/', 'shift-screen'],
    ['route', '/run', 'run-screen'],
    ['stop', '/stops/st-3', 'stop-screen'],
    ['pickup', '/stops/st-1/pickup', 'pickup-screen'],
    ['profile', '/account', 'account-screen'],
  ])('names every control on %s with a role and a 44 pt target', async (_name, url, id) => {
    await start(url);
    await screen.findByTestId(id);
    expect(audit()).toEqual([]);
  }, 20_000);
});
