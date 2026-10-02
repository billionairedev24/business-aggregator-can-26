import AsyncStorage from '@react-native-async-storage/async-storage';
import { screen, waitFor } from 'expo-router/testing-library';
import { StyleSheet } from 'react-native';

import { setServices } from '../src/services';
import { start } from './support';

/**
 * S-109 (cheap wins for the app): on the key screens of every journey, each control a finger can press has a role and
 * a name for VoiceOver/TalkBack, and a touch target of at least 44 pt (iOS HIG; WCAG 2.5.8 asks 24) — its own height or
 * its hitSlop. The kit's primitives aim for 48 (MIN_TARGET); screens' own controls are checked here too.
 */
jest.mock('@stripe/stripe-react-native', () => ({
  initStripe: jest.fn(async () => undefined),
  initPaymentSheet: jest.fn(async () => ({})),
  presentPaymentSheet: jest.fn(async () => ({})),
  PaymentSheetError: { Canceled: 'Canceled', Failed: 'Failed' },
  handleURLCallback: jest.fn(async () => true),
}));

afterEach(async () => {
  setServices(null);
  await AsyncStorage.clear();
});

type ReactTestInstance = typeof screen.root;

const MIN = 44;
const PRESS_ROLES = new Set(['button', 'link', 'tab', 'checkbox', 'radio', 'switch', 'menuitem', 'togglebutton', 'combobox', 'search', 'adjustable', 'imagebutton']);

/** Host nodes a finger can press (Pressable/Touchable render a host View with onClick or a responder). */
function pressables(root: ReactTestInstance): ReactTestInstance[] {
  return root.findAll((n: ReactTestInstance) => typeof n.type === 'string' && n.props.accessible !== false && (typeof n.props.onClick === 'function' || typeof n.props.onResponderRelease === 'function'), { deep: true })
    .filter((n: ReactTestInstance) => !['TextInput', 'RCTSinglelineTextInputView'].includes(String(n.type)));
}

function text(n: ReactTestInstance | string): string {
  if (typeof n === 'string') return n;
  return n.children.map((c: ReactTestInstance | string) => text(c)).join('');
}

function slop(h: unknown): number {
  if (typeof h === 'number') return 2 * h;
  if (h && typeof h === 'object') { const o = h as { top?: number; bottom?: number }; return (o.top ?? 0) + (o.bottom ?? 0); }
  return 0;
}

/** The declared touch height: (min)height plus the vertical hitSlop; undefined when only padding sizes it. */
function targetHeight(n: ReactTestInstance): number | undefined {
  const style = StyleSheet.flatten(typeof n.props.style === 'function' ? n.props.style({ pressed: false }) : n.props.style) ?? {};
  const own = Math.max(Number(style.minHeight ?? 0) || 0, Number(style.height ?? 0) || 0);
  const padded = (Number(style.paddingVertical ?? style.padding ?? 0) || 0) * 2;
  const h = own || (padded ? padded + 18 : 0); // a line of 15–16 pt text is about 18–20 pt tall
  return h ? h + slop(n.props.hitSlop) : undefined;
}

interface Problem { screen: string; what: string; control: string }

function audit(name: string): Problem[] {
  const problems: Problem[] = [];
  for (const n of pressables(screen.root)) {
    const label = (n.props.accessibilityLabel as string | undefined) ?? text(n).trim();
    const control = `${n.props.testID ?? n.props.accessibilityRole ?? n.type} "${label.slice(0, 40)}"`;
    if (!n.props.accessibilityRole || !PRESS_ROLES.has(n.props.accessibilityRole)) problems.push({ screen: name, what: `role ${n.props.accessibilityRole ?? 'missing'}`, control });
    if (!label) problems.push({ screen: name, what: 'no accessible name', control });
    const h = targetHeight(n);
    if (h === undefined || h < MIN) problems.push({ screen: name, what: `target ${h ?? '?'} pt`, control });
  }
  return problems;
}

const SCREENS: { name: string; url: string; signedIn?: boolean; ready: RegExp }[] = [
  { name: 'welcome', url: '/welcome', ready: /./ },
  { name: 'sign-up', url: '/sign-up', ready: /./ },
  { name: 'location', url: '/location', signedIn: true, ready: /./ },
  { name: 'home', url: '/home', signedIn: true, ready: /./ },
  { name: 'search', url: '/search?q=ribeye', signedIn: true, ready: /./ },
  { name: 'product', url: '/product/p-sourdough', signedIn: true, ready: /./ },
  { name: 'cart', url: '/cart', signedIn: true, ready: /./ },
  { name: 'checkout', url: '/checkout', signedIn: true, ready: /./ },
  { name: 'services', url: '/services', signedIn: true, ready: /./ },
  { name: 'providers', url: '/services/mobile-mechanic', signedIn: true, ready: /./ },
  { name: 'provider', url: '/providers/prairie-wrench', signedIn: true, ready: /./ },
  { name: 'book · service', url: '/book/prairie-wrench/service', signedIn: true, ready: /./ },
  { name: 'book · time', url: '/book/prairie-wrench/time', signedIn: true, ready: /./ },
  { name: 'orders', url: '/orders', signedIn: true, ready: /./ },
  { name: 'account', url: '/account', signedIn: true, ready: /./ },
  { name: 'security', url: '/security', signedIn: true, ready: /./ },
  { name: 'wallet', url: '/wallet', signedIn: true, ready: /./ },
];

describe('roles, names and 44 pt touch targets on the key screens (S-109)', () => {
  it('names the home screen\'s address kicker without its ▾ and gives it a 48 pt touch area', async () => {
    await start({ signedIn: true, welcomed: true, url: '/home' });
    const place = await screen.findByTestId('home-place');
    expect(place.props.accessibilityLabel).toMatch(/^Delivery address: [^▾]+$/);
    expect(targetHeight(place)).toBeGreaterThanOrEqual(48);
  });

  it.each(SCREENS)('$name', async ({ name, url, signedIn }) => {
    await start({ signedIn, welcomed: true, url });
    await waitFor(() => expect(screen.queryByTestId('loading')).toBeNull(), { timeout: 8000 });
    expect(pressables(screen.root).length).toBeGreaterThan(0); // the walk sees the controls
    expect(audit(name)).toEqual([]);
  });
});
