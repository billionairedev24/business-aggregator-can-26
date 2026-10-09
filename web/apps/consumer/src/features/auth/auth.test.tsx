import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { AuthPage, type AuthSearch } from './AuthPage';
import { splitName } from './RegisterFlow';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const AMARA = { id: '01J9ZD3V0000000000000C0001', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550201', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };
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
  it('shows the design: pitch, card, passkey, Apple / Google back to the consumer site', async () => {
    open('/sign-in?next=%2Fcart');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in to pick up where you left off.' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Welcome back')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').map(li => li.textContent)).toEqual([
      '1Passkeys, not passwordsFace ID, Touch ID or Windows Hello. Phishing-resistant by design.',
      '2Two factors for paymentsEscrow and saved cards sit behind a second factor.',
      '3Your data stays in CanadaStored in ca-central-1 under PIPEDA and Alberta PIPA.',
    ]);
    const c = card();
    expect(within(c).getByText('Welcome back. Use your passkey or a code to your phone.')).toBeInTheDocument();
    expect(within(c).getByRole('link', { name: 'Back to browsing' })).toHaveAttribute('href', '/cart');
    expect(within(c).getByRole('button', { name: 'Continue' })).toBeDisabled();
    expect(within(c).getByRole('button', { name: 'Sign in with a passkey' })).toBeInTheDocument();
    expect(within(c).getByRole('link', { name: 'Apple' })).toHaveAttribute('href', 'http://auth.test/oauth2/authorization/apple?app=consumer');
    expect(within(c).getByRole('link', { name: 'Google' })).toHaveAttribute('href', 'http://auth.test/oauth2/authorization/google?app=consumer');
    expect(within(c).getByText('Trouble signing in? Use a code to your phone, or contact support — we never ask for a password.')).toBeInTheDocument();
    expect(within(screen.getByRole('banner')).queryByRole('link', { name: 'Sign in' })).toBeNull();
  });

  it('signs in with a code to the phone, then hands off to the consumer-bff', async () => {
    // the resend countdown reads the clock: on a loaded machine the axe run below took over a second and the text was
    // "Resend in 0:44" (engineering follow-ups). The clock stands still here; timers stay real for Testing Library.
    vi.useFakeTimers({ toFake: ['Date'] });
    onTestFinished(() => { vi.useRealTimers(); });
    const { calls } = open('/sign-in?next=%2Fcart');
    await userEvent.type(await screen.findByLabelText('Mobile number'), '403 555 0201');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    const code = await screen.findByLabelText('6-digit code sent to 403 555 0201');
    await expectNoAxeViolations(document.body); // S-109
    expect(calls.find(c => c.url.endsWith('/api/auth/sign-in'))!.body).toEqual({ identifier: '403 555 0201' });
    const send = calls.find(c => c.url.endsWith('/api/auth/sign-in/code'))!;
    expect(send.body).toEqual({ channel: 'sms' });
    expect(send.headers['accept-language']).toBe('en-CA');
    expect(screen.getByText('Resend in 0:45')).toBeInTheDocument();

    await userEvent.type(code, '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByText("That code didn't work. Check it and try again.")).toBeInTheDocument();

    await userEvent.clear(code);
    await userEvent.type(code, '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Signed in. Your cart and bookings are back.');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(assign).toHaveBeenCalledWith('/bff/login?next=%2Fcart');
  });

  it('checks the mobile number with the registration rules', async () => {
    open('/sign-in');
    await userEvent.type(await screen.findByLabelText('Mobile number'), '555-01');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Enter a valid Canadian mobile, e.g. +1 403 555 0148.')).toBeInTheDocument();
  });

  it('shows the rate limit with a countdown', async () => {
    routes['/api/auth/sign-in'] = () => ({ status: 429, body: { code: 'rate_limited', retryAfterSeconds: 60 } });
    open('/sign-in');
    await userEvent.type(await screen.findByLabelText('Mobile number'), '4035550201');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText(/Too many attempts\. Try again in (1:00|0:59)\./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
  });

  it('continues a Google sign-in at the code, for the account’s mobile', async () => {
    const { calls } = open('/sign-in?step=factor&identifier=amara%40example.ca&link=google');
    expect(await screen.findByLabelText('Enter the 6-digit code we sent to the mobile number on your account.')).toBeInTheDocument();
    expect(screen.getByText('Enter the code we sent to your mobile to link your Google account.')).toBeInTheDocument();
    expect(calls.find(c => c.url.endsWith('/api/auth/sign-in'))!.body).toEqual({ identifier: 'amara@example.ca' });
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
    expect(await screen.findByRole('heading', { level: 1, name: 'Connectez-vous pour reprendre où vous en étiez.' })).toBeInTheDocument();
    expect(screen.getByLabelText('Numéro de mobile')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: "Se connecter avec une clé d'accès" })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Retour' })).toBeInTheDocument();
  });
});

describe('create account (design 06 auth, new)', () => {
  it('shows the design, with the legal documents in a new tab', async () => {
    open('/register');
    expect(await screen.findByRole('heading', { level: 1, name: 'One account for groceries, hot food and every trusted local.' })).toBeInTheDocument();
    const c = card();
    expect(within(c).getByText("A phone number is all we need. No passwords — you'll set a passkey.")).toBeInTheDocument();
    for (const name of [/^Terms/, /^Privacy Policy/]) {
      for (const link of within(c).getAllByRole('link', { name })) {
        expect(link).toHaveAttribute('target', '_blank');
        expect(link).toHaveAttribute('rel', 'noopener');
      }
    }
    expect(within(c).getAllByRole('link', { name: /^Terms/ })[0]).toHaveAttribute('href', '/legal/terms.html');
    expect(within(c).getByText('Standard message rates may apply for codes.', { exact: false })).toBeInTheDocument();
    expect(within(c).getByRole('button', { name: 'Send code' })).toBeDisabled();
  });

  it('validates with the exact messages and counts what needs attention', async () => {
    open('/register');
    await userEvent.type(await screen.findByLabelText('Full name'), 'Amara');
    await userEvent.type(screen.getByLabelText('Mobile number'), '4035550201');
    await userEvent.type(screen.getByLabelText('Email (receipts)'), 'amara@');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText('Last name is required.')).toBeInTheDocument();
    expect(screen.getByText("That doesn't look like an email address.")).toBeInTheDocument();
    expect(screen.getByText('2 things need attention.')).toBeInTheDocument();
  });

  it('puts the server’s "already in use" on the mobile field', async () => {
    routes['/api/auth/register'] = () => invalid('phone', 'unique', 'An account already uses this mobile number. Sign in instead.');
    open('/register', 'fr');
    await userEvent.type(await screen.findByLabelText('Nom complet'), 'Amara Osei');
    await userEvent.type(screen.getByLabelText('Numéro de mobile'), '4035550201');
    await userEvent.type(screen.getByLabelText('Courriel (reçus)'), 'amara@example.ca');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Envoyer le code' }));
    expect(await screen.findByText('Un compte utilise déjà ce numéro de mobile. Connectez-vous plutôt.')).toBeInTheDocument();
  });

  it('creates the account with SMS only (no second factor), then asks for the address', async () => {
    const { calls } = open('/register');
    await userEvent.type(await screen.findByLabelText('Full name'), 'Amara Kofi Osei');
    await userEvent.type(screen.getByLabelText('Mobile number'), '4035550201');
    await userEvent.type(screen.getByLabelText('Email (receipts)'), 'amara@example.ca');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }));
    // S-116: the language the Terms were shown in (English: no French text yet) and no request for English
    expect(calls.find(c => c.url.endsWith('/api/auth/register'))!.body).toEqual({ firstName: 'Amara Kofi', lastName: 'Osei', phone: '4035550201', email: 'amara@example.ca', terms: true, termsLanguage: 'en', termsEnglishRequested: false });

    await userEvent.type(await screen.findByLabelText('6-digit code sent to +1 403 555 0201'), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
    const options = await screen.findByRole('radiogroup', { name: 'Second factor' });
    expect(within(options).getAllByRole('radio').map(r => r.textContent)).toEqual([
      'Passkey (Face ID / Touch ID)Recommended · phishing-resistant', 'Authenticator appTime-based codes', 'SMS codeBackup only',
    ]);
    expect(screen.getByRole('button', { name: 'Create passkey' })).toBeInTheDocument();
    await userEvent.click(within(options).getByRole('radio', { name: /SMS code/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Continue with SMS' }));
    expect(await screen.findByRole('status')).toHaveTextContent("Account created. Set your delivery address next so we can show your province's shops.");
    expect(calls.some(c => c.url.endsWith('/api/auth/register/complete') && c.method === 'POST')).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: 'Set my address' }));
    expect(assign).toHaveBeenCalledWith('/bff/login?next=%2Flocation');
  });

  it('can add an authenticator app instead', async () => {
    open('/register');
    await userEvent.type(await screen.findByLabelText('Full name'), 'Amara Osei');
    await userEvent.type(screen.getByLabelText('Mobile number'), '4035550201');
    await userEvent.type(screen.getByLabelText('Email (receipts)'), 'amara@example.ca');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }));
    await userEvent.type(await screen.findByLabelText(/6-digit code sent to/), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
    await userEvent.click(await screen.findByRole('radio', { name: /Authenticator app/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Scan QR code' }));
    expect(await screen.findByRole('img', { name: 'QR code for your authenticator app' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('6-digit code'), '111222');
    await userEvent.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Account created.');
  });

  it('the second-factor options are one Tab stop; the arrow keys move and select (S-140)', async () => {
    open('/register');
    await userEvent.type(await screen.findByLabelText('Full name'), 'Amara Osei');
    await userEvent.type(screen.getByLabelText('Mobile number'), '4035550201');
    await userEvent.type(screen.getByLabelText('Email (receipts)'), 'amara@example.ca');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }));
    await userEvent.type(await screen.findByLabelText(/6-digit code sent to/), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
    const options = await screen.findByRole('radiogroup', { name: 'Second factor' });
    const [passkey, app, sms] = within(options).getAllByRole('radio');
    expect([passkey, app, sms].map(r => r!.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);
    passkey!.focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(app).toHaveFocus();
    expect(app).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('button', { name: 'Scan QR code' })).toBeInTheDocument();
    await userEvent.keyboard('{ArrowUp}{ArrowUp}');
    expect(sms).toHaveFocus();
    expect(sms).toHaveAttribute('aria-checked', 'true');
    await userEvent.keyboard('{Home}');
    expect(passkey).toHaveAttribute('aria-checked', 'true');
  });

  it('pre-fills what Google / Apple gave us', async () => {
    open('/register?firstName=Amara&lastName=Osei&email=amara%40example.ca&provider=apple&relay=1');
    expect(await screen.findByLabelText('Full name')).toHaveValue('Amara Osei');
    expect(screen.getByLabelText('Email (receipts)')).toHaveValue('amara@example.ca');
    expect(screen.getByRole('status')).toHaveTextContent('You’re signed in with Apple. Add your mobile number to finish creating your account.');
  });
});

describe('splitName', () => {
  it('keeps the last word as the last name', () => {
    expect(splitName('  Amara   Kofi Osei ')).toEqual({ firstName: 'Amara Kofi', lastName: 'Osei' });
    expect(splitName('Amara')).toEqual({ firstName: 'Amara', lastName: '' });
    expect(splitName('')).toEqual({ firstName: '', lastName: '' });
  });
});
