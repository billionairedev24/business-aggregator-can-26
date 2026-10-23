import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AuthPage, type AuthSearch } from './AuthPage';
import { splitName } from './RegisterFlow';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: '01J9ZD3V0000000000000C0001', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550201', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
const PASSWORD = 'correct horse battery staple';
const invalid = (field: string, rule: string, message: string) => ({ status: 422, body: { errors: [{ field, rule, message }] } });
type Reply = { status?: number; body?: unknown } | undefined;

let assign: ReturnType<typeof vi.fn>;
let signedIn = false;
let routes: Record<string, (c: Call) => Reply>;

function server(call: Call): Reply {
  if (call.url === '/bff/session') return { body: { user: signedIn ? AMARA : null, guestId: 'g_x' } };
  const path = call.url.replace('http://auth.test', '');
  return routes[path]?.(call);
}

beforeEach(() => {
  signedIn = false;
  assign = vi.fn();
  vi.stubGlobal('location', { ...window.location, assign, protocol: 'http:' });
  routes = {
    '/api/auth/sign-in': c => ({ body: { identifier: (c.body as { identifier: string }).identifier, factors: ['passkey', 'totp', 'backup_code'] } }),
    '/api/auth/sign-in/code': () => ({ body: { resendAfterSeconds: 45, channel: 'sms' } }),
    '/api/auth/sign-in/code/verify': c => ((c.body as { code: string }).code === '123456' ? { body: { user: AMARA, acr: null } } : invalid('code', 'mismatch', "That code didn't work. Check it and try again.")),
    '/api/auth/register': () => ({ body: { step: 'otp', phone: '+1 403 555 0201', resendAfterSeconds: 45, channel: 'sms' } }),
    '/api/auth/register/verify': c => ((c.body as { code: string }).code === '654321' ? { body: { step: 'mfa', phone: '+1 403 555 0201', resendAfterSeconds: 0, channel: 'sms' } } : invalid('code', 'mismatch', "That code doesn't match. Check it and try again.")),
    '/api/auth/register/complete': () => ({ status: 201, body: { user: AMARA, acr: null } }),
    '/api/auth/register/totp': () => ({ body: { secret: 'ABCDEFGHIJKLMNOP', otpauthUri: 'otpauth://totp/Northline:a?secret=ABCDEFGHIJKLMNOP', qrCode: 'data:image/png;base64,AAAA' } }),
    '/api/auth/register/totp/verify': () => ({ status: 201, body: { user: AMARA, acr: 'mfa' } }),
    // S-154: email + password
    '/api/auth/sign-in/password': c => ((c.body as { password: string }).password === PASSWORD ? { body: { step: 'factor', factors: ['totp', 'backup_code'], email: 'amara@example.ca' } } : invalid('password', 'mismatch', "That email and password don't match. Check them and try again.")),
    '/api/auth/sign-in/totp': c => ((c.body as { code: string }).code === '654321' ? { body: { user: AMARA, acr: 'mfa' } } : invalid('code', 'mismatch', "That code didn't work. Check it and try again.")),
    '/api/auth/accounts': () => ({ status: 202, body: { step: 'verify-email', email: 'amara@example.ca', resendAfterSeconds: 60 } }),
    '/api/auth/accounts/verify': c => ((c.body as { code: string }).code === '482913' ? { status: 201, body: { user: AMARA, acr: null, needsSecondFactor: true } } : invalid('code', 'mismatch', "That code doesn't match. Check it and try again.")),
    '/api/auth/enroll/totp': () => ({ body: { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Northline:a', qrCode: 'data:image/png;base64,AAAA' } }),
    '/api/auth/enroll/totp/verify': () => ({ body: { session: { user: AMARA, acr: 'mfa', needsSecondFactor: false }, backupCodes: ['abcde-fghij', 'bcdef-ghijk'] } }),
  };
});
afterEach(() => { vi.unstubAllGlobals(); });

function Page({ mode }: { mode: 'signin' | 'register' }) {
  const search = useSearch({ strict: false }) as AuthSearch;
  return <AuthPage mode={mode} search={search} />;
}
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp(path, { locale, routes: { signIn: () => <Page mode="signin" />, register: () => <Page mode="register" /> } });
  return { calls, ...view };
};
const card = () => screen.getByRole('region', { name: /Sign in|Create account|Se connecter|Créer un compte/ });

describe('sign in (design 06 auth)', () => {
  it('names the visitor\'s own province\'s privacy law once their place is known', async () => {
    localStorage.setItem('nl.location', JSON.stringify({ label: '1204 17 Ave SW', city: 'Calgary', province: 'AB' }));
    open('/sign-in');
    expect(await screen.findByText('Stored on servers in Canada under PIPEDA and Alberta PIPA.')).toBeInTheDocument();
    localStorage.removeItem('nl.location');
  });

  it('shows the design: pitch, card, email + password, passkey, Apple / Google back to the consumer site', async () => {
    open('/sign-in?next=%2Fcart');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Sign in to pick up where you left off.' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Welcome back')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').map(li => li.textContent)).toEqual([
      '1Passkeys welcomeFace ID, Touch ID or Windows Hello. Phishing-resistant by design.',
      '2Two factors for paymentsEscrow and saved cards sit behind a second factor.',
      '3Your data stays in CanadaStored on servers in Canada under PIPEDA and provincial privacy law.',
    ]);
    expect(screen.getByText(/^Bilingual support 7:00 a\.m\. – 11:00 p\.m\. M[DS]T · northline\.ca\/help$/)).toBeInTheDocument();
    const c = card();
    expect(within(c).getByText('Welcome back. Sign in with your email and password.')).toBeInTheDocument();
    expect(within(c).getByRole('link', { name: 'Back to browsing' })).toHaveAttribute('href', '/cart');
    expect(within(c).getByRole('button', { name: 'Sign in' })).toBeDisabled();
    expect(within(c).getByRole('button', { name: 'Sign in with a passkey' })).toBeInTheDocument();
    expect(within(c).getByRole('button', { name: 'Text a code to my phone instead' })).toBeInTheDocument();
    expect(within(c).getByRole('link', { name: 'Apple' })).toHaveAttribute('href', 'http://auth.test/oauth2/authorization/apple?app=consumer');
    expect(within(c).getByRole('link', { name: 'Google' })).toHaveAttribute('href', 'http://auth.test/oauth2/authorization/google?app=consumer');
    expect(within(screen.getByRole('banner')).queryByRole('link', { name: 'Sign in' })).toBeNull();
  });

  it('S-154: email + password → the account’s authenticator code → hands off to the consumer-bff', async () => {
    const { calls } = open('/sign-in?next=%2Fcart');
    await userEvent.type(await screen.findByLabelText('Email'), 'amara@example.ca');
    await userEvent.type(screen.getByLabelText('Password'), 'not the right one');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText("That email and password don't match. Check them and try again.")).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('Password'));
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Confirm it’s you, amara@example.ca.')).toBeInTheDocument();
    expect(screen.getAllByRole('radio').map(r => r.textContent)).toEqual(['Authenticator app', 'Backup code']);
    await userEvent.type(screen.getByLabelText('6-digit code'), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Signed in. Your cart and bookings are back.');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(assign).toHaveBeenCalledWith('/bff/login?next=%2Fcart');
    expect(calls.filter(c => c.url.endsWith('/api/auth/sign-in/password')).at(-1)!.body).toEqual({ email: 'amara@example.ca', password: PASSWORD });
  });

  it('S-154: without a second factor, one is offered — and can be skipped', async () => {
    routes['/api/auth/sign-in/password'] = () => ({ body: { step: 'enroll', session: { user: AMARA, acr: null, needsSecondFactor: true } } });
    open('/sign-in');
    await userEvent.type(await screen.findByLabelText('Email'), 'amara@example.ca');
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Set up your second factor' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Skip for now' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Signed in.');
  });

  it('signs in with a code to the phone (S-62 kept), then hands off to the consumer-bff', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    onTestFinished(() => { vi.useRealTimers(); });
    const { calls } = open('/sign-in?next=%2Fcart');
    await userEvent.click(await screen.findByRole('button', { name: 'Text a code to my phone instead' }));
    await userEvent.type(screen.getByLabelText('Mobile number'), '403 555 0201');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    const code = await screen.findByLabelText('6-digit code sent to 403 555 0201');
    expect(calls.find(c => c.url.endsWith('/api/auth/sign-in'))!.body).toEqual({ identifier: '403 555 0201' });
    const send = calls.find(c => c.url.endsWith('/api/auth/sign-in/code'))!;
    expect(send.headers['accept-language']).toBe('en-CA');
    expect(screen.getByText('Resend in 0:45')).toBeInTheDocument();
    await userEvent.type(code, '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Signed in. Your cart and bookings are back.');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(assign).toHaveBeenCalledWith('/bff/login?next=%2Fcart');
  });

  it('checks the mobile number with the registration rules', async () => {
    open('/sign-in');
    await userEvent.click(await screen.findByRole('button', { name: 'Text a code to my phone instead' }));
    await userEvent.type(screen.getByLabelText('Mobile number'), '555-01');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Enter a valid Canadian mobile, e.g. +1 403 555 0148.')).toBeInTheDocument();
  });

  it('shows the rate limit with a countdown', async () => {
    routes['/api/auth/sign-in/password'] = () => ({ status: 429, body: { code: 'rate_limited', retryAfterSeconds: 60 } });
    open('/sign-in');
    await userEvent.type(await screen.findByLabelText('Email'), 'amara@example.ca');
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText(/Too many attempts\. Try again in (1:00|0:59)\./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
  });

  it('continues a Google sign-in with the email filled in', async () => {
    open('/sign-in?step=factor&identifier=amara%40example.ca&link=google');
    expect(await screen.findByLabelText('Email')).toHaveValue('amara@example.ca');
    expect(screen.getByText('Sign in with your password to link your Google account.')).toBeInTheDocument();
  });

  it('S-154: forgot password by email', async () => {
    routes['/api/auth/password/forgot'] = c => ({ status: 202, body: { step: 'verify-email', email: (c.body as { email: string }).email, resendAfterSeconds: 60 } });
    routes['/api/auth/password/reset'] = () => ({ body: { email: 'amara@example.ca' } });
    const { calls } = open('/sign-in');
    await userEvent.click(await screen.findByRole('button', { name: 'Forgot password?' }));
    await userEvent.type(screen.getByLabelText('Email'), 'amara@example.ca');
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }));
    expect(calls.find(c => c.url.endsWith('/api/auth/password/forgot'))!.body).toEqual({ email: 'amara@example.ca', app: 'consumer' });
    await userEvent.type(await screen.findByLabelText('Code from the email'), '730215');
    await userEvent.type(screen.getByLabelText('New password'), 'Maple syrup on fresh snow 42');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'Maple syrup on fresh snow 42');
    await userEvent.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Password changed.');
  });

  it('explains a cancelled Google / Apple sign-in', async () => {
    open('/sign-in?error=federation_cancelled');
    expect(await screen.findByText('Signing in with Google or Apple was cancelled. Try again or use your email.')).toBeInTheDocument();
  });

  it('sends someone already signed in where they were going', async () => {
    signedIn = true;
    const { router } = open('/sign-in?next=%2Fcart');
    await waitFor(() => expect(router.state.location.pathname).toBe('/cart'));
  });

  it('is in French', async () => {
    open('/sign-in', 'fr');
    expect(await screen.findByRole('heading', { level: 2, name: 'Connectez-vous pour reprendre où vous en étiez.' })).toBeInTheDocument();
    expect(screen.getByLabelText('Mot de passe')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: "Se connecter avec une clé d'accès" })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Retour' })).toBeInTheDocument();
  });
});

describe('create account (S-154: email + password)', () => {
  const fill = async (name = 'Amara Kofi Osei', password = PASSWORD) => {
    await userEvent.type(await screen.findByLabelText('Full name'), name);
    await userEvent.type(screen.getByLabelText('Email'), 'amara@example.ca');
    await userEvent.type(screen.getByLabelText('Password'), password);
    await userEvent.type(screen.getByLabelText('Confirm password'), password);
    await userEvent.click(screen.getByRole('checkbox'));
  };

  it('shows the design, with the legal documents in a new tab', async () => {
    open('/register');
    expect(await screen.findByRole('heading', { level: 2, name: 'One account for groceries, hot food and every trusted local.' })).toBeInTheDocument();
    const c = card();
    expect(within(c).getByText('Your email and a password. We send a code to confirm the email.')).toBeInTheDocument();
    for (const name of [/^Terms/, /^Privacy Policy/]) {
      for (const link of within(c).getAllByRole('link', { name })) {
        expect(link).toHaveAttribute('target', '_blank');
        expect(link).toHaveAttribute('rel', 'noopener');
      }
    }
    expect(within(c).getAllByRole('link', { name: /^Terms/ })[0]).toHaveAttribute('href', '/legal/terms.html');
  });

  it('validates with the exact messages and counts what needs attention', async () => {
    open('/register');
    await userEvent.type(await screen.findByLabelText('Full name'), 'Amara');
    await userEvent.type(screen.getByLabelText('Email'), 'amara@');
    await userEvent.type(screen.getByLabelText('Password'), 'too short');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Last name is required.')).toBeInTheDocument();
    expect(screen.getByText("That doesn't look like an email address.")).toBeInTheDocument();
    expect(screen.getByText('Use at least 12 characters.')).toBeInTheDocument();
    expect(screen.getByText("The passwords don't match.")).toBeInTheDocument();
    expect(screen.getByText('4 things need attention.')).toBeInTheDocument();
  });

  it('puts the server’s 422 on its field, in French', async () => {
    routes['/api/auth/accounts'] = () => invalid('password', 'common', "This password is too common. Choose one that's harder to guess.");
    open('/register', 'fr');
    await userEvent.type(await screen.findByLabelText('Nom complet'), 'Amara Osei');
    await userEvent.type(screen.getByLabelText('Courriel'), 'amara@example.ca');
    await userEvent.type(screen.getByLabelText('Mot de passe'), '123456789012');
    await userEvent.type(screen.getByLabelText('Confirmez le mot de passe'), '123456789012');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Créer un compte' }));
    expect(await screen.findByText('Ce mot de passe est trop courant. Choisissez-en un plus difficile à deviner.')).toBeInTheDocument();
  });

  it('form → code from the email → authenticator app (optional) → backup codes → the address', async () => {
    const { calls } = open('/register');
    await fill();
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    // S-116: the language the Terms were shown in and no request for English
    expect(calls.find(c => c.url.endsWith('/api/auth/accounts'))!.body).toEqual({
      firstName: 'Amara Kofi', lastName: 'Osei', email: 'amara@example.ca', password: PASSWORD, passwordConfirm: PASSWORD, phone: null, terms: true,
      termsLanguage: 'en', termsEnglishRequested: false, app: 'consumer', marketing: [],
    });
    await userEvent.type(await screen.findByLabelText('Code from the email'), '482913');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm email' }));
    expect(await screen.findByRole('button', { name: 'Skip for now' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await userEvent.type(await screen.findByLabelText('6-digit code from the app'), '111222');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm code' }));
    await userEvent.click(await screen.findByRole('checkbox', { name: 'I saved my backup codes somewhere safe' }));
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('status')).toHaveTextContent("Account created. Set your delivery address next so we can show your province's shops.");
    await userEvent.click(screen.getByRole('button', { name: 'Set my address' }));
    expect(assign).toHaveBeenCalledWith('/bff/login?next=%2Flocation');
  });

  it('S-157: unticked offers boxes (email, texts) with the server’s wording; texts need the mobile; ticked ones are sent', async () => {
    routes['/api/auth/accounts/consent-wordings?app=consumer'] = () => ({ body: {
      requester: 'Northline Marketplace Inc. · 1200 – 8th Avenue SW · support@northline.ca',
      items: [
        { category: 'marketing_email', channel: 'email', wordingVersion: 'account.email.2026-10', wording: 'Yes, Northline Marketplace Inc. may email me Northline’s offers, rewards and news.' },
        { category: 'marketing_sms', channel: 'sms', wordingVersion: 'account.sms.2026-10', wording: 'Yes, Northline Marketplace Inc. may text me Northline’s offers and rewards.' },
      ],
    } });
    const { calls } = open('/register');
    const email = await screen.findByRole('checkbox', { name: /may email me/ });
    const sms = screen.getByRole('checkbox', { name: /may text me/ });
    expect((email as HTMLInputElement).checked).toBe(false);
    expect((sms as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText('Add your mobile number above to get offers by text.')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Full name'), 'Amara Kofi Osei');
    await userEvent.type(screen.getByLabelText('Email'), 'amara@example.ca');
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD);
    await userEvent.type(screen.getByLabelText('Confirm password'), PASSWORD);
    await userEvent.click(screen.getByRole('checkbox', { name: /Terms/ }));
    await userEvent.type(screen.getByLabelText(/Mobile number/), '+1 403 555 0201');
    await userEvent.click(email);
    await userEvent.click(screen.getByRole('checkbox', { name: /may text me/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    await screen.findByLabelText('Code from the email');
    expect((calls.find(c => c.url.endsWith('/api/auth/accounts'))!.body as { marketing: unknown }).marketing).toEqual([
      { category: 'marketing_email', wordingVersion: 'account.email.2026-10' },
      { category: 'marketing_sms', wordingVersion: 'account.sms.2026-10' },
    ]);
  });

  it('a second factor can be skipped', async () => {
    open('/register');
    await fill('Amara Osei');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    await userEvent.type(await screen.findByLabelText('Code from the email'), '482913');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm email' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Skip for now' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Account created.');
  });

  it('pre-fills what Google / Apple gave us', async () => {
    open('/register?firstName=Amara&lastName=Osei&email=amara%40example.ca&provider=apple&relay=1');
    expect(await screen.findByLabelText('Full name')).toHaveValue('Amara Osei');
    expect(screen.getByLabelText('Email')).toHaveValue('amara@example.ca');
    expect(screen.getByRole('status')).toHaveTextContent('You’re signed in with Apple. Choose a password and confirm your email to finish creating your account.');
  });
});

describe('splitName', () => {
  it('keeps the last word as the last name', () => {
    expect(splitName('  Amara   Kofi Osei ')).toEqual({ firstName: 'Amara Kofi', lastName: 'Osei' });
    expect(splitName('Amara')).toEqual({ firstName: 'Amara', lastName: '' });
    expect(splitName('')).toEqual({ firstName: '', lastName: '' });
  });
});
