import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Linking from 'expo-linking';
import * as Location from 'expo-location';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { setPushRegistrar } from '@northline/mobile-kit';

import { FIXTURE_BACKUP, FIXTURE_CODE, FIXTURE_PASSWORD, FIXTURE_TOTP } from '../src/fixtures/auth';
import { defaultAddress } from '../src/location/DeliveryLocation';
import { setServices } from '../src/services';
import { start } from './support';

afterEach(async () => {
  setServices(null);
  setPushRegistrar(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

const SAVED = { 'nl.location': JSON.stringify({ label: '1204 Example Ave, Sampleville', city: 'Sampleville', marketId: 'mkt-sampleville' }) };

const PASSWORD = 'a long fixture password';

async function fillSignUp(name = 'Grace Hopper', email = 'grace@example.com', password = PASSWORD, phone = '') {
  fireEvent.changeText(await screen.findByTestId('field-name'), name);
  fireEvent.changeText(screen.getByTestId('field-email'), email);
  fireEvent.changeText(screen.getByTestId('field-password'), password);
  fireEvent.changeText(screen.getByTestId('field-password-confirm'), password);
  if (phone) fireEvent.changeText(screen.getByTestId('field-phone'), phone);
  fireEvent.press(screen.getByRole('checkbox'));
}

async function signInWith(email: string, password: string) {
  fireEvent.changeText(await screen.findByTestId('field-email'), email);
  fireEvent.changeText(screen.getByTestId('field-password'), password);
  fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
}

describe('A1 Welcome', () => {
  it('says the design’s words, names the province from the region model, and lets a guest browse first', async () => {
    const { view, store } = await start();
    expect(await screen.findByText('Northline · Sample Province')).toBeTruthy();
    expect(screen.getByText('Every trusted local,\none tap from\nthe door.')).toBeTruthy();
    expect(screen.getByText("Book a mechanic, order tonight's groceries, hire the bartender — pay into escrow, get it delivered for the price of a coffee.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Create account' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Browse first' }));
    await screen.findByTestId('home');
    expect(view.getPathname()).toBe('/home');
    expect(store.data.get('nl.app.welcomed')).toBe('1');
  });

  it('opens Sign up and Sign in', async () => {
    const { view } = await start();
    fireEvent.press(await screen.findByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Create your account')).toBeTruthy();
    expect(view.getPathname()).toBe('/sign-up');
  });
});

describe('A2 Sign up (S-159: email + password, S-154)', () => {
  it('keeps Create account off until the terms are ticked, then shows every rule with its message', async () => {
    await start({ url: '/sign-up' });
    expect(await screen.findByText('Your email and a password. Add your mobile if you like — we use it only to reach you about an order.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Create account' }).props.accessibilityState).toMatchObject({ disabled: true });
    fireEvent.changeText(screen.getByTestId('field-name'), 'Grace');
    fireEvent.changeText(screen.getByTestId('field-email'), 'grace@');
    fireEvent.changeText(screen.getByTestId('field-password'), 'too short');
    fireEvent.changeText(screen.getByTestId('field-phone'), '12345');
    fireEvent.press(screen.getByRole('checkbox'));
    fireEvent.press(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Last name is required.')).toBeTruthy();
    expect(screen.getByText("That doesn't look like an email address.")).toBeTruthy();
    expect(screen.getByText('Use at least 12 characters.')).toBeTruthy();
    expect(screen.getByText('Enter a valid Canadian mobile, e.g. +1 403 555 0148.')).toBeTruthy();
  });

  it('opens the Terms and the Privacy Policy outside the app', async () => {
    await start({ url: '/sign-up' });
    fireEvent.press(await screen.findByTestId('link-terms'));
    fireEvent.press(screen.getByTestId('link-privacy'));
    expect(Linking.openURL).toHaveBeenNthCalledWith(1, 'http://localhost:3000/legal/terms.html');
    expect(Linking.openURL).toHaveBeenNthCalledWith(2, 'http://localhost:3000/legal/privacy.html');
  });

  it('shows the server’s “too common” under the password', async () => {
    await start({ url: '/sign-up' });
    await fillSignUp('Grace Hopper', 'grace@example.com', 'password1234');
    fireEvent.press(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText("This password is too common. Choose one that's harder to guess.")).toBeTruthy();
  });

  it('keeps what was typed (not the password) and says so when there is no connection', async () => {
    await start({
      url: '/sign-up',
      wrap: (f) => async (input, init) => {
        if (String(input).includes('/api/auth/accounts')) throw new TypeError('Network request failed');
        return f(input, init);
      },
    });
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText("We couldn't reach Northline. Check your connection and try again.")).toBeTruthy();
    expect(screen.getByTestId('field-name').props.value).toBe('Grace Hopper');
  });
});

describe('creating an account (A2 → email code → A4 → A5) on the app’s own screens', () => {
  it('sends the email code, verifies it, skips the second factor, and is signed in with DPoP tokens via the https hand-off', async () => {
    const { server, services, view } = await start({ url: '/sign-up' });
    await fillSignUp('Grace Hopper', 'grace@example.com', PASSWORD, '555 555 0148');
    fireEvent.press(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('Check your email')).toBeTruthy();
    expect(screen.getByText('We sent a 6-digit code to grace@example.com.')).toBeTruthy();
    expect(screen.getByText('Resend in 0:45')).toBeTruthy();
    expect(server.auth.api[0]).toMatchObject({
      path: '/api/auth/accounts',
      body: { firstName: 'Grace', lastName: 'Hopper', email: 'grace@example.com', password: PASSWORD, passwordConfirm: PASSWORD, phone: '555 555 0148', terms: true, app: 'consumer' },
      language: 'en-CA',
    });
    // the app's authorization request was kept in the auth session before the JSON calls
    expect(server.calls.findIndex((c) => c.path === '/oauth2/authorize')).toBeLessThan(server.calls.findIndex((c) => c.path === '/api/auth/accounts'));
    fireEvent.changeText(screen.getByTestId('code-input'), '111111');
    expect(await screen.findByText("That code doesn't match. Check it and try again.")).toBeTruthy();

    fireEvent.changeText(screen.getByTestId('code-input'), FIXTURE_CODE);
    expect(await screen.findByText('Protect your account')).toBeTruthy();
    expect(view.getPathname()).toBe('/second-factor');
    expect(screen.getByTestId('mfa-passkey').props.accessibilityState).toMatchObject({ disabled: true });
    fireEvent.press(screen.getByRole('button', { name: 'Not now' }));

    expect(await screen.findByText('Where should we bring things?')).toBeTruthy();
    expect(view.getPathname()).toBe('/location');
    expect(await services.session.restore()).toBe(true);
    expect(server.calls.filter((c) => c.path === '/oauth2/authorize')).toHaveLength(2); // kept, then followed through continueTo
    expect(server.calls.filter((c) => c.path === '/oauth2/token')).toHaveLength(2); // use_dpop_nonce, then tokens
    expect(server.auth.api.map((c) => c.path)).toEqual(['/api/auth/accounts', '/api/auth/accounts/verify', '/api/auth/accounts/verify']);
  });

  it('sets up an authenticator app: the QR, the key, the otpauth link, its code, then the backup codes once', async () => {
    const { server } = await start({ url: '/sign-up' });
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Create account' }));
    fireEvent.changeText(await screen.findByTestId('code-input'), FIXTURE_CODE);
    fireEvent.press(await screen.findByRole('button', { name: 'Set up an authenticator app' }));
    expect(await screen.findByLabelText('QR code for your authenticator app')).toBeTruthy();
    expect(screen.getByText("Can't scan? Enter this key: JBSWY3DPEHPK3PXP")).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Open my authenticator app' }));
    expect(Linking.openURL).toHaveBeenCalledWith(expect.stringMatching(/^otpauth:\/\/totp\//));
    fireEvent.changeText(screen.getByTestId('totp-input'), FIXTURE_TOTP);
    fireEvent.press(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByText('Save your backup codes')).toBeTruthy();
    expect(screen.getByText(FIXTURE_BACKUP)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Tick the box once you’ve saved them.')).toBeTruthy();
    fireEvent.press(screen.getByRole('checkbox'));
    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Where should we bring things?')).toBeTruthy();
    expect(server.auth.api.map((c) => c.path).slice(-2)).toEqual(['/api/auth/enroll/totp', '/api/auth/enroll/totp/verify']);
  });

  it('offers “Finish signing in” when the account is made but the tokens didn’t arrive, and finishes on retry', async () => {
    let failOnce = true;
    const { services } = await start({
      url: '/sign-up',
      wrap: (f) => async (input, init) => {
        if (String(input).includes('/oauth2/authorize') && String(input).includes('state=') && failOnce && server().auth.session.signedIn) {
          failOnce = false;
          throw new TypeError('Network request failed');
        }
        return f(input, init);
      },
    });
    const server = () => services.fixtures!;
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Create account' }));
    fireEvent.changeText(await screen.findByTestId('code-input'), FIXTURE_CODE);
    fireEvent.press(await screen.findByRole('button', { name: 'Not now' }));
    expect(await screen.findByText("We couldn't reach Northline. Check your connection and try again.")).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Finish signing in' }));
    expect(await screen.findByText('Where should we bring things?')).toBeTruthy();
    expect(await services.session.restore()).toBe(true);
  });

  it('shows when a new email code can be asked for, and the spam-folder hint', async () => {
    await start({ url: '/sign-up' });
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Resend in 0:45')).toBeTruthy();
    expect(screen.getByText('Not there? Check your spam folder.')).toBeTruthy();
  });
});

describe('signing in to an existing account (S-159: email + password + its second factor)', () => {
  it('asks the account’s authenticator code after the password and signs in; Home when the address is set', async () => {
    const pushes: string[] = [];
    setPushRegistrar({ signedIn: async () => void pushes.push('in'), signingOut: async () => void pushes.push('out') });
    const { view, server, services } = await start({ url: '/sign-in', store: SAVED });
    fireEvent.press(await screen.findByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Email is required.')).toBeTruthy();
    expect(screen.getByText('Enter a password.')).toBeTruthy();
    await signInWith('ada@example.com', 'not the password');
    expect(await screen.findByText("That email and password don't match. Check them and try again.")).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-password'), FIXTURE_PASSWORD);
    fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Enter the code from your authenticator app')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('factor-code'), FIXTURE_TOTP);
    fireEvent.press(screen.getByRole('button', { name: 'Verify' }));
    await screen.findByTestId('home');
    expect(view.getPathname()).toBe('/home');
    expect(await services.session.restore()).toBe(true);
    expect(server.auth.api.map((c) => c.path)).toEqual(['/api/auth/sign-in/password', '/api/auth/sign-in/password', '/api/auth/sign-in/totp']);
    await waitFor(() => expect(pushes).toEqual(['in']));
  });

  it('takes a backup code instead of the authenticator', async () => {
    const { server } = await start({ url: '/sign-in', store: SAVED });
    await signInWith('ada@example.com', FIXTURE_PASSWORD);
    fireEvent.press(await screen.findByRole('link', { name: 'Use a backup code instead' }));
    fireEvent.changeText(screen.getByTestId('factor-backup'), FIXTURE_BACKUP);
    fireEvent.press(screen.getByRole('button', { name: 'Verify' }));
    await screen.findByTestId('home');
    expect(server.auth.api.at(-1)?.path).toBe('/api/auth/sign-in/backup-code');
  });

  it('an account without a second factor is offered one after its password', async () => {
    const { server } = await start({ url: '/sign-in', store: SAVED });
    server.auth.accounts.push({ id: 'acct-bo', firstName: 'Bo', lastName: 'Example', email: 'bo@example.com', phone: '', password: PASSWORD, factors: [] });
    await signInWith('bo@example.com', PASSWORD);
    expect(await screen.findByText('Protect your account')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Not now' }));
    await screen.findByTestId('home');
  });

  it('forgot password: the email, then the code and a new password, then back to Sign in', async () => {
    const { server } = await start({ url: '/sign-in' });
    fireEvent.changeText(await screen.findByTestId('field-email'), 'ada@example.com');
    fireEvent.press(screen.getByRole('link', { name: 'Forgot password?' }));
    expect(await screen.findByText('Forgot your password?')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('Enter the code we emailed to ada@example.com and choose a new password.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('code-input'), FIXTURE_CODE);
    fireEvent.changeText(screen.getByTestId('field-password'), 'a brand new long password');
    fireEvent.changeText(screen.getByTestId('field-password-confirm'), 'a brand new long password');
    fireEvent.press(screen.getByRole('button', { name: 'Save the new password' }));
    expect(await screen.findByText('Your password is changed. Sign in with it.')).toBeTruthy();
    expect(server.auth.api.map((c) => c.path)).toEqual(['/api/auth/password/forgot', '/api/auth/password/reset']);
    expect(server.auth.api[0]?.body).toEqual({ email: 'ada@example.com', app: 'consumer' });
  });

  it('the phone code stays as an option: a code to the account’s phone signs in', async () => {
    const { view, server, services } = await start({ url: '/sign-in', store: SAVED });
    fireEvent.press(await screen.findByRole('button', { name: 'Use a code to my mobile instead' }));
    fireEvent.press(await screen.findByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('Enter your email or mobile.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-identifier'), 'ada@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('Enter the 6-digit code we sent to the mobile number on your account.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('code-input'), FIXTURE_CODE);
    await screen.findByTestId('home');
    expect(view.getPathname()).toBe('/home');
    expect(await services.session.restore()).toBe(true);
    expect(server.auth.api.map((c) => c.path)).toEqual(['/api/auth/sign-in', '/api/auth/sign-in/code', '/api/auth/sign-in/code/verify']);
  });

  it('says a wrong phone code didn’t work, in the sign-in words', async () => {
    await start({ url: '/sign-in-phone' });
    fireEvent.changeText(await screen.findByTestId('field-identifier'), 'nobody@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    fireEvent.changeText(await screen.findByTestId('code-input'), FIXTURE_CODE);
    expect(await screen.findByText("That code didn't work. Check it and try again.")).toBeTruthy();
  });
});

describe('A5 the location sheet (the web\'s location dialog)', () => {
  it('says what is shown, lists the live markets, picks an address and saves it with its market, zone and note', async () => {
    const { view, store } = await start({ welcomed: true, url: '/location?next=/cart' });
    expect(await screen.findByRole('header', { name: 'Choose your location' })).toBeTruthy();
    expect(await screen.findByText('Showing Sampleville until you choose your location.')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Sampleville, Sample Province' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Exampleton, Sample Province' })).toBeTruthy();
    expect(screen.queryByText(/Waitlist Province/)).toBeNull(); // only live markets are offered
    fireEvent.changeText(screen.getByTestId('field-street'), '1204 Exa');
    fireEvent.press(await screen.findByRole('button', { name: '1204 Example Ave, Sampleville, XA' }));
    expect(await screen.findByText('Zone · Old Town')).toBeTruthy();
    expect(screen.getByText('3 pooled runs / day')).toBeTruthy();
    expect(screen.getByText('Sales tax 5%')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-unit'), 'Apt 804 · buzz 0804');
    fireEvent.press(screen.getByRole('button', { name: 'Deliver here' }));
    await waitFor(() => expect(view.getPathname()).toBe('/cart'));
    expect(JSON.parse(store.data.get('nl.location')!)).toMatchObject({
      label: '1204 Example Ave, Sampleville',
      city: 'Sampleville',
      street: '1204 Example Ave',
      unit: 'Apt 804 · buzz 0804',
      province: 'XA',
      postalCode: 'A1A 1A1',
      marketId: 'mkt-sampleville',
      zoneId: 'zone-old-town',
      zone: 'Old Town',
    });
  });

  it('as the sign-up’s address step (A5): asks design 01’s question and goes on to Home once a place is chosen', async () => {
    const { view } = await start({ signedIn: true, url: '/location?step=address' });
    expect(await screen.findByRole('header', { name: 'Where should we bring things?' })).toBeTruthy();
    fireEvent.press(await screen.findByRole('button', { name: 'Sampleville, Sample Province' }));
    await waitFor(() => expect(view.getPathname()).toBe('/home'));
  });

  it('chooses a live market in one tap', async () => {
    const { store } = await start({ welcomed: true, url: '/location' });
    fireEvent.press(await screen.findByRole('button', { name: 'Exampleton, Sample Province' }));
    await waitFor(() => expect(store.data.get('nl.location')).toBeTruthy());
    expect(JSON.parse(store.data.get('nl.location')!)).toMatchObject({ label: 'Exampleton', city: 'Exampleton' });
  });

  it('offers the waitlist outside a live market', async () => {
    const { server } = await start({ welcomed: true, url: '/location' });
    fireEvent.changeText(await screen.findByTestId('field-street'), '77 Harb');
    fireEvent.press(await screen.findByRole('button', { name: '77 Harbour Road, Faraway, XC' }));
    expect(await screen.findByText('Northline isn’t in Waitlist Province yet.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Deliver here' })).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText('Email is required.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-waitlist-email'), 'grace@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText('You’re on the list for Waitlist Province. We’ll email you when we open.')).toBeTruthy();
    expect(server.geo.waitlist).toEqual([{ regionId: 'XC', email: 'grace@example.com' }]);
  });

  it('“Use my current location”: asks for the permission on the press, names the place through the api, and saves it', async () => {
    const { store, view } = await start({ welcomed: true, url: '/location' });
    expect(Location.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
    fireEvent.press(await screen.findByRole('button', { name: 'Use my current location' }));
    expect(await screen.findByText('1204 Example Ave, Sampleville')).toBeTruthy();
    expect(Location.requestForegroundPermissionsAsync).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Zone · Old Town')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Deliver here' }));
    await waitFor(() => expect(view.getPathname()).toBe('/home'));
    expect(JSON.parse(store.data.get('nl.location')!)).toMatchObject({ label: '1204 Example Ave, Sampleville', city: 'Sampleville', marketId: 'mkt-sampleville', zone: 'Old Town' });
  });

  it('keeps the fallback market when location is refused, and points to Settings when it can’t ask again', async () => {
    (Location.requestForegroundPermissionsAsync as jest.Mock).mockResolvedValueOnce({ status: 'denied', granted: false, canAskAgain: false });
    await start({ welcomed: true, url: '/location' });
    expect(await screen.findByText('Showing Sampleville until you choose your location.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Use my current location' }));
    expect(await screen.findByText('Location is off for Northline. Search for your address or postal code instead, or allow location in Settings.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Open Settings' }));
    expect(Linking.openSettings).toHaveBeenCalled();
  });

  it('signed in: lists the account’s addresses and chooses one', async () => {
    const { server, services, store } = await start({ signedIn: true, url: '/location' });
    server.shop.addresses.push({ id: 'ad-1', street: '12 Sample Street', city: 'Sampleville', province: 'XA', postal: 'A1A 1A1', isDefault: true });
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    fireEvent.press(await screen.findByRole('button', { name: '12 Sample Street, Sampleville, default' }));
    await waitFor(() => expect(store.data.get('nl.location')).toBeTruthy());
    expect(JSON.parse(store.data.get('nl.location')!)).toMatchObject({ label: '12 Sample Street, Sampleville', street: '12 Sample Street', postalCode: 'A1A 1A1' });
  });

  it('the sources, in order: a saved place, else the account’s default address, else the fallback market', async () => {
    const { server } = await start({ signedIn: true, url: '/home' });
    await screen.findByTestId('home');
    expect(await defaultAddress()).toBeNull(); // no address on the account yet → the fallback market stands
    expect(screen.getByRole('button', { name: 'Delivery address: Sampleville' })).toBeTruthy();
    expect(screen.getByText('Near')).toBeTruthy();
    server.shop.addresses.push({ id: 'ad-2', street: '9 Other Way', city: 'Sampleville', province: 'XA', postal: 'A1A 1A1', isDefault: false });
    server.shop.addresses.push({ id: 'ad-1', street: '12 Sample Street', city: 'Sampleville', province: 'XA', postal: 'A1A 1A1', isDefault: true });
    expect(await defaultAddress()).toMatchObject({ status: 'address', label: '12 Sample Street, Sampleville', street: '12 Sample Street' });
  });

  it('never asks for location outside the sheet (an undecided permission stays undecided)', async () => {
    await start({ welcomed: true });
    await screen.findByTestId('home');
    expect(Location.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
    expect(Location.getCurrentPositionAsync).not.toHaveBeenCalled();
    expect(Location.getForegroundPermissionsAsync).not.toHaveBeenCalled();
  });
});

describe('signing out (You)', () => {
  it('runs the push hook, revokes the refresh token, forgets the key and shows the guest view', async () => {
    const pushes: string[] = [];
    setPushRegistrar({ signedIn: async () => void pushes.push('in'), signingOut: async () => void pushes.push('out') });
    const { server, services, view } = await start({ signedIn: true, url: '/account' });
    expect(await screen.findByText('Hi, Ada')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(view.getPathname()).toBe('/home'));
    expect(pushes).toEqual(['out']);
    expect(server.auth.revoked).toEqual(['fixture-refresh-1']);
    expect(await services.session.restore()).toBe(false);
    fireEvent.press(screen.getByRole('tab', { name: 'Account' }));
    expect(await screen.findByText('Sign in to see your orders and bookings.')).toBeTruthy();
  });

  it('switches the language in place', async () => {
    await start({ welcomed: true, url: '/account' });
    fireEvent.press(await screen.findByRole('radio', { name: 'Français' }));
    expect(await screen.findByText('Connectez-vous pour voir vos commandes et vos réservations.')).toBeTruthy();
    expect(screen.getAllByRole('tab').map((t) => t.props.accessibilityLabel)).toEqual(['Accueil', 'Parcourir', 'Activité', 'Panier', 'Compte']);
  });
});

describe('Journey A in French', () => {
  it('speaks Canadian French end to end, and asks the auth server for French emails', async () => {
    const { getLocales } = jest.requireMock('expo-localization') as { getLocales: jest.Mock };
    getLocales.mockReturnValue([{ languageTag: 'fr-CA' }]);
    try {
      const { server } = await start({ url: '/welcome' });
      expect(await screen.findByText('Tous les commerçants de confiance,\nà un geste\nde votre porte.')).toBeTruthy();
      fireEvent.press(screen.getByRole('button', { name: 'Créer un compte' }));
      expect(await screen.findByText('Créez votre compte')).toBeTruthy();
      await act(async () => {
        await fillSignUp();
      });
      fireEvent.press(screen.getByRole('button', { name: 'Créer le compte' }));
      expect(await screen.findByText('Vérifiez vos courriels')).toBeTruthy();
      expect(server.auth.api[0]?.language).toBe('fr-CA');
      expect(server.auth.api[0]?.body).toMatchObject({ termsLanguage: 'fr' });
    } finally {
      getLocales.mockReturnValue([{ languageTag: 'en-CA' }]);
    }
  });
});
