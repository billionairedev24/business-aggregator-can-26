import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Linking from 'expo-linking';
import * as Location from 'expo-location';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { setPushRegistrar } from '@northline/mobile-kit';

import { FIXTURE_CODE, FIXTURE_TOTP } from '../src/fixtures/auth';
import { setServices } from '../src/services';
import { start } from './support';

afterEach(async () => {
  setServices(null);
  setPushRegistrar(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

const SAVED = { 'nl.location': JSON.stringify({ label: '1204 Example Ave, Sampleville', city: 'Sampleville', marketId: 'mkt-sampleville' }) };

async function fillSignUp(phone = '555 555 0148', name = 'Grace Hopper', email = 'grace@example.com') {
  fireEvent.changeText(await screen.findByTestId('field-phone'), phone);
  fireEvent.changeText(screen.getByTestId('field-name'), name);
  fireEvent.changeText(screen.getByTestId('field-email'), email);
  fireEvent.press(screen.getByRole('checkbox'));
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

describe('A2 Sign up', () => {
  it('keeps Send code off until the mobile and the terms are there, then shows every rule with its message', async () => {
    await start({ url: '/sign-up' });
    expect(await screen.findByText("A phone number is all we need to start. No passwords — you'll set up a passkey next.")).toBeTruthy();
    const send = screen.getByRole('button', { name: 'Send code' });
    expect(send.props.accessibilityState).toMatchObject({ disabled: true });
    fireEvent.changeText(screen.getByTestId('field-phone'), '12345');
    fireEvent.changeText(screen.getByTestId('field-name'), 'Grace');
    fireEvent.changeText(screen.getByTestId('field-email'), 'grace@');
    fireEvent.press(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: 'Send code' }).props.accessibilityState).toMatchObject({ disabled: false });
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('Enter a valid Canadian mobile, e.g. +1 403 555 0148.')).toBeTruthy();
    expect(screen.getByText('Last name is required.')).toBeTruthy();
    expect(screen.getByText("That doesn't look like an email address.")).toBeTruthy();
  });

  it('opens the Terms and the Privacy Policy outside the app', async () => {
    await start({ url: '/sign-up' });
    fireEvent.press(await screen.findByTestId('link-terms'));
    fireEvent.press(screen.getByTestId('link-privacy'));
    expect(Linking.openURL).toHaveBeenNthCalledWith(1, 'http://localhost:3000/legal/terms.html');
    expect(Linking.openURL).toHaveBeenNthCalledWith(2, 'http://localhost:3000/legal/privacy.html');
  });

  it('shows the server’s “already used” under the field', async () => {
    await start({ url: '/sign-up' });
    await fillSignUp('+1 555 555 0100');
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('An account already uses this mobile number. Sign in instead.')).toBeTruthy();
  });

  it('keeps what was typed and says so when there is no connection', async () => {
    await start({
      url: '/sign-up',
      wrap: (f) => async (input, init) => {
        if (String(input).includes('/api/auth/register')) throw new TypeError('Network request failed');
        return f(input, init);
      },
    });
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText("We couldn't reach Northline. Check your connection and try again.")).toBeTruthy();
    expect(screen.getByTestId('field-name').props.value).toBe('Grace Hopper');
  });
});

describe('creating an account (A2 → A3 → A4 → A5) on the app’s own screens', () => {
  it('sends the code, verifies it, finishes with SMS only, and is signed in with DPoP tokens via the https hand-off', async () => {
    const { server, services, view } = await start({ url: '/sign-up' });
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));

    // A3: where the code went, the countdown, a wrong code
    expect(await screen.findByText('Enter the 6-digit code')).toBeTruthy();
    expect(screen.getByText('Sent to +1 (555) 555-0148 ·')).toBeTruthy();
    expect(screen.getByText('Resend in 0:45')).toBeTruthy();
    expect(server.auth.api[0]).toMatchObject({ path: '/api/auth/register', body: { firstName: 'Grace', lastName: 'Hopper', phone: '555 555 0148', email: 'grace@example.com', terms: true }, language: 'en-CA' });
    // the app's authorization request was kept in the auth session before the JSON calls
    expect(server.calls.findIndex((c) => c.path === '/oauth2/authorize')).toBeLessThan(server.calls.findIndex((c) => c.path === '/api/auth/register'));
    fireEvent.changeText(screen.getByTestId('code-input'), '111111');
    expect(await screen.findByText("That code doesn't match. Check it and try again.")).toBeTruthy();

    // six digits (as SMS autofill) submit by themselves
    fireEvent.changeText(screen.getByTestId('code-input'), FIXTURE_CODE);
    expect(await screen.findByText('Protect your account')).toBeTruthy();
    expect(view.getPathname()).toBe('/second-factor');

    // A4: the design's three choices; passkey isn't possible in the app yet
    expect(screen.getByText("You'll pay into escrow and store addresses here, so we require two factors. Pick your primary.")).toBeTruthy();
    expect(screen.getByTestId('mfa-passkey').props.accessibilityState).toMatchObject({ disabled: true });
    expect(screen.getByText("Passkeys aren't in the app yet: add one later on the Northline site, in Settings → Security.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Scan QR code' })).toBeTruthy();
    fireEvent.press(screen.getByTestId('mfa-sms'));
    fireEvent.press(screen.getByRole('button', { name: 'Continue with SMS' }));

    // A5, signed in
    expect(await screen.findByText('Where should we bring things?')).toBeTruthy();
    expect(view.getPathname()).toBe('/location');
    expect(await services.session.restore()).toBe(true);
    const authorize = server.calls.filter((c) => c.path === '/oauth2/authorize');
    expect(authorize).toHaveLength(2); // kept, then followed through continueTo
    expect(server.calls.filter((c) => c.path === '/oauth2/token')).toHaveLength(2); // use_dpop_nonce, then tokens
    expect(server.auth.api.map((c) => c.path)).toEqual(['/api/auth/register', '/api/auth/register/verify', '/api/auth/register/verify', '/api/auth/register/complete']);
  });

  it('sets up an authenticator app: the QR, the key, the otpauth link, then its code', async () => {
    const { server } = await start({ url: '/sign-up' });
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    fireEvent.changeText(await screen.findByTestId('code-input'), FIXTURE_CODE);
    fireEvent.press(await screen.findByRole('button', { name: 'Scan QR code' }));
    expect(await screen.findByLabelText('QR code for your authenticator app')).toBeTruthy();
    expect(screen.getByText("Can't scan? Enter this key: JBSWY3DPEHPK3PXP")).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Open my authenticator app' }));
    expect(Linking.openURL).toHaveBeenCalledWith(expect.stringMatching(/^otpauth:\/\/totp\//));
    fireEvent.changeText(screen.getByTestId('totp-input'), FIXTURE_TOTP);
    fireEvent.press(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByText('Where should we bring things?')).toBeTruthy();
    expect(server.auth.api.at(-1)?.path).toBe('/api/auth/register/totp/verify');
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
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    fireEvent.changeText(await screen.findByTestId('code-input'), FIXTURE_CODE);
    fireEvent.press(await screen.findByTestId('mfa-sms'));
    fireEvent.press(screen.getByRole('button', { name: 'Continue with SMS' }));
    expect(await screen.findByText("We couldn't reach Northline. Check your connection and try again.")).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Finish signing in' }));
    expect(await screen.findByText('Where should we bring things?')).toBeTruthy();
    expect(await services.session.restore()).toBe(true);
  });

  it('says how long to wait when a new code is asked for too soon', async () => {
    await start({ url: '/sign-up' });
    await fillSignUp();
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    fireEvent.press(await screen.findByRole('link', { name: 'Call me instead' }));
    expect(await screen.findByText(/Too many attempts\. Try again in 0:4\d\./)).toBeTruthy();
  });
});

describe('signing in to an existing account', () => {
  it('sends a code to the account’s phone and signs in; Home when the address is already set', async () => {
    const pushes: string[] = [];
    setPushRegistrar({ signedIn: async () => void pushes.push('in'), signingOut: async () => void pushes.push('out') });
    const { view, server, services } = await start({ url: '/sign-in', store: SAVED });
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
    await waitFor(() => expect(pushes).toEqual(['in']));
  });

  it('says a wrong code didn’t work, in the sign-in words', async () => {
    await start({ url: '/sign-in' });
    fireEvent.changeText(await screen.findByTestId('field-identifier'), 'nobody@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Send code' }));
    fireEvent.changeText(await screen.findByTestId('code-input'), FIXTURE_CODE);
    expect(await screen.findByText("That code didn't work. Check it and try again.")).toBeTruthy();
  });
});

describe('A5 Province & address', () => {
  it('lists the provinces with their stage, picks an address and saves it with its market, zone and note', async () => {
    const { view, store } = await start({ welcomed: true, url: '/location?next=/cart' });
    expect(await screen.findByText('Live · Sampleville, Exampleton')).toBeTruthy();
    expect(screen.getByText('Pilot · invite only')).toBeTruthy();
    expect(screen.getByTestId('province-XC').props.accessibilityState).toMatchObject({ disabled: true });
    expect(screen.getByTestId('province-XD').props.accessibilityState).toMatchObject({ disabled: true });
    fireEvent.changeText(screen.getByTestId('field-street'), '1204 Exa');
    fireEvent.press(await screen.findByRole('button', { name: '1204 Example Ave, Sampleville, XA' }));
    expect(await screen.findByText('Zone · Old Town')).toBeTruthy();
    expect(screen.getByText('3 pooled runs / day')).toBeTruthy();
    expect(screen.getByText('Sales tax 5%')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-unit'), 'Apt 804 · buzz 0804');
    fireEvent.press(screen.getByRole('button', { name: 'Save and continue' }));
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

  it('asks to choose from the list, and offers the waitlist outside a live market', async () => {
    const { server } = await start({ welcomed: true, url: '/location' });
    fireEvent.changeText(await screen.findByTestId('field-street'), 'somewhere');
    fireEvent.press(screen.getByRole('button', { name: 'Save and continue' }));
    expect(await screen.findByText('Choose your address from the list.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-street'), '77 Harb');
    fireEvent.press(await screen.findByRole('button', { name: '77 Harbour Road, Faraway, XC' }));
    expect(await screen.findByText('Northline isn’t in Waitlist Province yet.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText('Email is required.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('field-waitlist-email'), 'grace@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText('You’re on the list for Waitlist Province. We’ll email you when we open.')).toBeTruthy();
    expect(server.geo.waitlist).toEqual([{ regionId: 'XC', email: 'grace@example.com' }]);
  });

  it('“Use my location”: asks for the permission, names the place through the api, and saves it', async () => {
    const { store, view } = await start({ welcomed: true, url: '/location' });
    fireEvent.press(await screen.findByRole('button', { name: 'Use my location' }));
    await waitFor(() => expect(screen.getByTestId('field-street').props.value).toBe('1204 Example Ave, Sampleville'));
    expect(Location.requestForegroundPermissionsAsync).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Zone · Old Town')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(view.getPathname()).toBe('/home'));
    expect(JSON.parse(store.data.get('nl.location')!)).toMatchObject({ label: '1204 Example Ave, Sampleville', city: 'Sampleville', marketId: 'mkt-sampleville', zone: 'Old Town' });
  });

  it('falls back to the api’s market when location is refused, and points to Settings when it can’t ask again', async () => {
    (Location.requestForegroundPermissionsAsync as jest.Mock).mockResolvedValueOnce({ status: 'denied', granted: false, canAskAgain: false });
    await start({ welcomed: true, url: '/location' });
    expect(await screen.findByText('Showing Sampleville for now. Set your address to see what delivers to you.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Use my location' }));
    expect(await screen.findByText('Location is off for Northline. Type your address, or allow location in Settings.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Open Settings' }));
    expect(Linking.openSettings).toHaveBeenCalled();
  });

  it('never asks for location outside this screen (an undecided permission stays undecided)', async () => {
    await start({ welcomed: true });
    await screen.findByTestId('home');
    expect(Location.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
    expect(Location.getCurrentPositionAsync).not.toHaveBeenCalled();
  });
});

describe('signing out (You)', () => {
  it('runs the push hook, revokes the refresh token, forgets the key and shows the guest view', async () => {
    const pushes: string[] = [];
    setPushRegistrar({ signedIn: async () => void pushes.push('in'), signingOut: async () => void pushes.push('out') });
    const { server, services, view } = await start({ signedIn: true, url: '/account' });
    expect(await screen.findByText('Ada Example')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(view.getPathname()).toBe('/home'));
    expect(pushes).toEqual(['out']);
    expect(server.auth.revoked).toEqual(['fixture-refresh-1']);
    expect(await services.session.restore()).toBe(false);
    fireEvent.press(screen.getByRole('tab', { name: 'You' }));
    expect(await screen.findByText('Sign in to see your orders, bookings and points.')).toBeTruthy();
  });

  it('switches the language in place', async () => {
    await start({ welcomed: true, url: '/account' });
    fireEvent.press(await screen.findByRole('radio', { name: 'Français' }));
    expect(await screen.findByText('Connectez-vous pour voir vos commandes, vos réservations et vos points.')).toBeTruthy();
    expect(screen.getAllByRole('tab').map((t) => t.props.accessibilityLabel)).toEqual(['Accueil', 'Services', 'Panier', 'Commandes', 'Vous']);
  });
});

describe('Journey A in French', () => {
  it('speaks Canadian French end to end, and asks the auth server for French codes', async () => {
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
      fireEvent.press(screen.getByRole('button', { name: 'Envoyer le code' }));
      expect(await screen.findByText('Entrez le code à 6 chiffres')).toBeTruthy();
      expect(server.auth.api[0]?.language).toBe('fr-CA');
    } finally {
      getLocales.mockReturnValue([{ languageTag: 'en-CA' }]);
    }
  });
});
