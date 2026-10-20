import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nProvider } from '@northline/ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SignedOutPage, type AuthMode } from './SignedOutPage';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

// ── fake northline-auth ───────────────────────────────────────────────────────────────────────────────────────────

type Reply = { status: number; body?: unknown };
type Handler = (body: Record<string, unknown>) => Reply;
let routes: Record<string, Handler>;
let calls: { path: string; body: Record<string, unknown>; headers: Record<string, string> }[];

const user = { id: '01J9ZD3V00000000000000RAV1', firstName: 'Ravi', lastName: 'Sandhu', email: 'ravi@prairiewrench.ca', phone: '+14035550148', initials: 'RS', locale: 'en-CA', memberSince: '2026-01-05' };
const amara = { ...user, id: '01KAMARA000000000000000001', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', initials: 'AO' };
const ok = (body: unknown): Reply => ({ status: 200, body });
const invalid = (field: string, rule: string, message: string): Reply => ({ status: 422, body: { errors: [{ field, rule, message }] } });
const CODES = ['abcde-fghij', 'bcdef-ghijk', 'cdefg-hijkm', 'defgh-ijkmn', 'efghi-jkmnp', 'fghij-kmnpq', 'ghijk-mnpqr', 'hijkm-npqrs', 'ijkmn-pqrst', 'jkmnp-qrstu'];
const PASSWORD = 'correct horse battery staple';

beforeEach(() => {
  calls = [];
  routes = {
    '/api/auth/accounts': () => ({ status: 202, body: { step: 'verify-email', email: 'amara@example.ca', resendAfterSeconds: 60 } }),
    '/api/auth/accounts/resend': () => ({ status: 202, body: { step: 'verify-email', email: 'amara@example.ca', resendAfterSeconds: 60 } }),
    '/api/auth/accounts/verify': b => (b.code === '482913' ? { status: 201, body: { user: amara, acr: null, needsSecondFactor: true } } : invalid('code', 'mismatch', "That code doesn't match. Check it and try again.")),
    '/api/auth/enroll/totp': () => ok({ secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Northline:amara@example.ca?secret=JBSW&issuer=Northline', qrCode: 'data:image/png;base64,AAAA' }),
    '/api/auth/enroll/totp/verify': b => (b.code === '111222' ? ok({ session: { user: amara, acr: 'mfa', needsSecondFactor: false }, backupCodes: CODES }) : invalid('code', 'mismatch', "That code doesn't match. Check it and try again.")),
    '/api/auth/sign-in/password': b => (b.password === PASSWORD ? ok({ step: 'factor', factors: ['totp', 'backup_code'], email: b.email }) : invalid('password', 'mismatch', "That email and password don't match. Check them and try again.")),
    '/api/auth/sign-in/totp': b => (b.code === '654321' ? ok({ user, acr: 'mfa' }) : invalid('code', 'mismatch', "That code didn't work. Check it and try again.")),
    '/api/auth/sign-in/backup-code': () => invalid('code', 'mismatch', "That backup code didn't work, or it was already used."),
    '/api/auth/password/forgot': b => ({ status: 202, body: { step: 'verify-email', email: b.email, resendAfterSeconds: 60 } }),
    '/api/auth/password/reset': b => (b.code === '730215' ? ok({ email: 'ravi@prairiewrench.ca' }) : invalid('code', 'mismatch', "That code doesn't match. Check it and try again.")),
  };
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://localhost').pathname;
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {};
    calls.push({ path, body, headers: (init?.headers ?? {}) as Record<string, string> });
    const handler = routes[path];
    const reply = handler ? handler(body) : { status: 404 };
    return new Response(reply.body === undefined ? '' : JSON.stringify(reply.body), { status: reply.status, headers: { 'content-type': 'application/json' } });
  }));
});
afterEach(() => vi.unstubAllGlobals());

function renderPage(props: { mode?: AuthMode; next?: string; locale?: 'en' | 'fr'; forgotOnLoad?: boolean } = {}) {
  const navigate = vi.fn();
  const onModeChange = vi.fn();
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  render(
    <I18nProvider initial={props.locale ?? 'en'}>
      <QueryClientProvider client={qc}>
        <SignedOutPage mode={props.mode ?? 'register'} next={props.next} forgotOnLoad={props.forgotOnLoad} onModeChange={onModeChange} navigate={navigate} />
      </QueryClientProvider>
    </I18nProvider>,
  );
  return { navigate, onModeChange, ui: userEvent.setup({ delay: null }) };
}

type UI = ReturnType<typeof userEvent.setup>;
async function fillRegistration(ui: UI, password = PASSWORD) {
  await ui.type(screen.getByLabelText('First name'), 'Amara');
  await ui.type(screen.getByLabelText('Last name'), 'Osei');
  await ui.type(screen.getByLabelText('Email'), 'amara@example.ca');
  await ui.type(screen.getByLabelText('Password'), password);
  await ui.type(screen.getByLabelText('Confirm password'), password);
  await ui.click(screen.getByRole('checkbox'));
}
async function signInWithPassword(ui: UI, email = 'ravi@prairiewrench.ca', password = PASSWORD) {
  await ui.type(screen.getByLabelText('Email'), email);
  await ui.type(screen.getByLabelText('Password'), password);
  await ui.click(screen.getByRole('button', { name: 'Sign in' }));
}

// ── Create account ────────────────────────────────────────────────────────────────────────────────────────────────

describe('Create account — validation', () => {
  it('shows nothing before touch, then every required message and the summary on submit', async () => {
    const { ui } = renderPage();
    expect(screen.getByRole('heading', { name: 'Create your account' })).toBeTruthy();
    expect(screen.queryAllByRole('alert')).toHaveLength(0);

    await ui.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('First name is required.')).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Last name is required.')).toBeTruthy();
    expect(screen.getByText('Email is required.')).toBeTruthy();
    expect(screen.getByText('Enter a password.')).toBeTruthy();
    expect(screen.getByText('You need to accept the Terms and Privacy Policy.')).toBeTruthy();
    expect(screen.getByText('5 things need attention.')).toBeTruthy();
    // the mobile is optional
    expect(screen.queryByText('Mobile number is required for verification.')).toBeNull();
    expect(calls.filter(c => c.path === '/api/auth/accounts')).toHaveLength(0);
  });

  it('password rules: 12 characters, the confirmation, not the email (no composition rules)', async () => {
    const { ui } = renderPage();
    await ui.type(screen.getByLabelText('Email'), 'amara@example.ca');
    await ui.type(screen.getByLabelText('Password'), 'short one');
    expect(await screen.findByText('Use at least 12 characters.')).toBeTruthy();
    await ui.clear(screen.getByLabelText('Password'));
    await ui.type(screen.getByLabelText('Password'), 'amara@example.ca');
    expect(await screen.findByText("Your password can't be your email address.")).toBeTruthy();
    await ui.clear(screen.getByLabelText('Password'));
    await ui.type(screen.getByLabelText('Password'), 'all lower case words');
    await ui.type(screen.getByLabelText('Confirm password'), 'all lower case word');
    expect(await screen.findByText("The passwords don't match.")).toBeTruthy();
    expect(screen.queryByText('Use at least 12 characters.')).toBeNull();
  });

  it('shows and hides the password', async () => {
    const { ui } = renderPage();
    const field = screen.getByLabelText('Password') as HTMLInputElement;
    expect(field.type).toBe('password');
    await ui.click(screen.getAllByRole('button', { name: 'Show password' })[0]!);
    expect(field.type).toBe('text');
    expect(screen.getAllByRole('button', { name: 'Hide password' })[0]!.getAttribute('aria-pressed')).toBe('true');
  });

  it('maps a server 422 (common password) onto the password field, in French too', async () => {
    routes['/api/auth/accounts'] = () => invalid('password', 'common', "This password is too common. Choose one that's harder to guess.");
    const { ui } = renderPage({ locale: 'fr' });
    await ui.type(screen.getByLabelText('Prénom'), 'Amara');
    await ui.type(screen.getByLabelText('Nom'), 'Osei');
    await ui.type(screen.getByLabelText('Courriel'), 'amara@example.ca');
    await ui.type(screen.getByLabelText('Mot de passe'), '123456789012');
    await ui.type(screen.getByLabelText('Confirmez le mot de passe'), '123456789012');
    await ui.click(screen.getByRole('checkbox'));
    await ui.click(screen.getByRole('button', { name: 'Créer le compte' }));
    expect(await screen.findByText('Ce mot de passe est trop courant. Choisissez-en un plus difficile à deviner.')).toBeTruthy();
    expect(screen.getByLabelText('Mot de passe').getAttribute('aria-invalid')).toBe('true');
  });

  it('S-157: an unticked offers checkbox with the server’s wording; ticked, it is sent with its version (en + fr)', async () => {
    routes['/api/auth/accounts/consent-wordings'] = () => ok({
      requester: 'Northline Marketplace Inc. · 1200 – 8th Avenue SW · support@northline.ca',
      items: [{ category: 'marketing_email', channel: 'email', wordingVersion: 'studio.email.2026-10', wording: 'Yes, Northline Marketplace Inc. may email me news, offers and tips for businesses selling on Northline.' }],
    });
    const { ui } = renderPage();
    const box = await screen.findByRole('checkbox', { name: /may email me news, offers and tips/ });
    expect((box as HTMLInputElement).checked).toBe(false);
    expect(screen.getByText(/Leave this unticked if you don’t want it\..*Sent by Northline Marketplace Inc\./)).toBeTruthy();
    await ui.type(screen.getByLabelText('First name'), 'Amara');
    await ui.type(screen.getByLabelText('Last name'), 'Osei');
    await ui.type(screen.getByLabelText('Email'), 'amara@example.ca');
    await ui.type(screen.getByLabelText('Password'), PASSWORD);
    await ui.type(screen.getByLabelText('Confirm password'), PASSWORD);
    await ui.click(screen.getByRole('checkbox', { name: /Terms of Service/ }));
    await ui.click(box);
    await ui.click(screen.getByRole('button', { name: 'Create account' }));
    await screen.findByRole('heading', { name: 'Check your email' });
    expect(calls.find(c => c.path === '/api/auth/accounts')!.body.marketing).toEqual([{ category: 'marketing_email', wordingVersion: 'studio.email.2026-10' }]);
    expect(calls.find(c => c.path === '/api/auth/accounts/consent-wordings')).toBeTruthy();
  });

  it('S-157: the offers box in French, and a stale wording comes back on it', async () => {
    routes['/api/auth/accounts/consent-wordings'] = () => ok({ requester: 'Northline Marketplace Inc.', items: [{ category: 'marketing_email', channel: 'email', wordingVersion: 'studio.email.2026-10', wording: 'Oui, Northline Marketplace Inc. peut m’envoyer par courriel des nouvelles.' }] });
    routes['/api/auth/accounts'] = () => invalid('marketing', 'stale', 'The consent wording has changed. Read it again before you agree.');
    const { ui } = renderPage({ locale: 'fr' });
    expect(await screen.findByText('Offres et nouvelles (facultatif)')).toBeTruthy();
    await ui.type(screen.getByLabelText('Prénom'), 'Amara');
    await ui.type(screen.getByLabelText('Nom'), 'Osei');
    await ui.type(screen.getByLabelText('Courriel'), 'amara@example.ca');
    await ui.type(screen.getByLabelText('Mot de passe'), PASSWORD);
    await ui.type(screen.getByLabelText('Confirmez le mot de passe'), PASSWORD);
    await ui.click(screen.getByRole('checkbox', { name: /Conditions/ }));
    await ui.click(screen.getByRole('checkbox', { name: /peut m’envoyer par courriel/ }));
    await ui.click(screen.getByRole('button', { name: 'Créer le compte' }));
    expect(await screen.findByText('Le texte du consentement a changé. Relisez-le avant d’accepter.')).toBeTruthy();
  });

  it('links the Terms and Privacy Policy in a new tab', () => {
    renderPage();
    expect((screen.getByRole('link', { name: 'Terms of Service' })).getAttribute('href')).toBe('/legal/terms.html');
    expect((screen.getByRole('link', { name: 'Terms of Service' })).getAttribute('target')).toBe('_blank');
    expect((screen.getByRole('link', { name: 'Privacy Policy' })).getAttribute('href')).toBe('/legal/privacy.html');
  });
});

describe('Create account — email code → second factor → backup codes', () => {
  it('form → code from the email → authenticator app → backup codes → onboarding hand-off', async () => {
    const { ui, navigate } = renderPage();
    await fillRegistration(ui);
    await ui.type(screen.getByLabelText(/Mobile number/), '+1 403 555 0148');
    await ui.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeTruthy();
    expect(screen.getByText(/We sent a 6-digit code to amara@example\.ca/)).toBeTruthy();
    expect(screen.getByText(/^New code in (1:00|0:5\d)$/)).toBeTruthy();
    expect(screen.getByText(/Mailpit: http:\/\/localhost:8025/)).toBeTruthy(); // jsdom runs on localhost
    expect(calls.find(c => c.path === '/api/auth/accounts')).toMatchObject({ path: '/api/auth/accounts', body: { firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', password: PASSWORD, phone: '+1 403 555 0148', terms: true, app: 'studio' } });

    await ui.type(screen.getByLabelText('Code from the email'), '000000');
    await ui.click(screen.getByRole('button', { name: 'Confirm email' }));
    expect(await screen.findByText("That code doesn't match. Check it and try again.")).toBeTruthy();
    await ui.clear(screen.getByLabelText('Code from the email'));
    await ui.type(screen.getByLabelText('Code from the email'), '482 913');
    await ui.click(screen.getByRole('button', { name: 'Confirm email' }));

    // Business accounts can't skip the second factor.
    expect(await screen.findByRole('heading', { name: 'Set up your second factor' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Skip for now' })).toBeNull();
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByAltText('QR code to add Northline to your authenticator app')).toBeTruthy();
    expect(screen.getByText('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP')).toBeTruthy();
    expect(screen.getByText('Account: amara@example.ca · Issuer: Northline')).toBeTruthy();
    await ui.type(screen.getByLabelText('6-digit code from the app'), '111222');
    await ui.click(screen.getByRole('button', { name: 'Confirm code' }));

    expect(await screen.findByRole('heading', { name: 'Save your backup codes' })).toBeTruthy();
    expect(screen.getAllByRole('listitem').filter(li => li.textContent && CODES.includes(li.textContent))).toHaveLength(10);
    await expectNoAxeViolations(document.body);
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Confirm that you saved your backup codes.')).toBeTruthy();
    await ui.click(screen.getByRole('checkbox', { name: 'I saved my backup codes somewhere safe' }));
    await ui.click(screen.getByRole('button', { name: 'Continue' }));

    expect((await screen.findByRole('status')).textContent).toContain('Amara Osei · email confirmed · second factor set up.');
    await ui.click(screen.getByRole('button', { name: 'Continue to business onboarding' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Fonboarding');
  });

  it('a new code after the cool-down; a throttled one shows the wait', async () => {
    routes['/api/auth/accounts'] = () => ({ status: 202, body: { step: 'verify-email', email: 'amara@example.ca', resendAfterSeconds: 0 } });
    routes['/api/auth/accounts/resend'] = () => ({ status: 429, body: { code: 'otp_throttled', retryAfterSeconds: 42 } });
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Create account' }));
    await ui.click(await screen.findByRole('button', { name: 'Send a new code' }));
    expect(await screen.findByText('New code in 0:42')).toBeTruthy();
  });

  it('passkey needs WebAuthn — explains when the browser has none', async () => {
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Create account' }));
    await ui.type(await screen.findByLabelText('Code from the email'), '482913');
    await ui.click(screen.getByRole('button', { name: 'Confirm email' }));
    await ui.click(await screen.findByRole('radio', { name: /Passkey/ }));
    await ui.click(screen.getByRole('button', { name: 'Create a passkey' }));
    expect(await screen.findByText("This browser can't use passkeys. Choose another method.")).toBeTruthy();
  });

  it('the second-factor picker: one Tab stop, arrow keys (S-140)', async () => {
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Create account' }));
    await ui.type(await screen.findByLabelText('Code from the email'), '482913');
    await ui.click(screen.getByRole('button', { name: 'Confirm email' }));
    const app = await screen.findByRole('radio', { name: /Authenticator app/ });
    expect(screen.getAllByRole('radio').filter(r => r.getAttribute('tabindex') === '0')).toEqual([app]);
    app.focus();
    await ui.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: /Passkey/ }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('button', { name: 'Create a passkey' })).toBeTruthy();
  });
});

// ── Sign in ───────────────────────────────────────────────────────────────────────────────────────────────────────

describe('Sign in', () => {
  it('email + password → the account’s own factors → authenticator code → hand-off to next', async () => {
    const { ui, navigate } = renderPage({ mode: 'signin', next: '/b/01J9ZD3V00000000000000PWM1/orders' });
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement).disabled).toBe(true);
    await signInWithPassword(ui, 'ravi@prairiewrench.ca', 'wrong password!!');
    expect(await screen.findByText("That email and password don't match. Check them and try again.")).toBeTruthy();
    expect(screen.getByText(/Use “Forgot password\?” to set one by email/)).toBeTruthy();
    await ui.clear(screen.getByLabelText('Password'));
    await ui.type(screen.getByLabelText('Password'), PASSWORD);
    await ui.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Confirm it’s you, ravi@prairiewrench.ca.')).toBeTruthy();
    // Only the factors the account has; the authenticator first, others behind "Use another method".
    expect(screen.getAllByRole('radio')).toHaveLength(1);
    expect(screen.queryByRole('radio', { name: /Passkey/ })).toBeNull();
    await ui.click(screen.getByRole('button', { name: 'Use another method' }));
    expect(screen.getAllByRole('radio').map(r => r.textContent)).toEqual([expect.stringContaining('Authenticator app'), expect.stringContaining('Backup code')]);
    await ui.type(screen.getByLabelText('6-digit code'), '000000');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByText("That code didn't work. Check it and try again.")).toBeTruthy();
    await ui.clear(screen.getByLabelText('6-digit code'));
    await ui.type(screen.getByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));

    expect((await screen.findByRole('status')).textContent).toContain('Signed in. Welcome back, Ravi.');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Fb%2F01J9ZD3V00000000000000PWM1%2Forders');
    expect(calls.filter(c => c.path === '/api/auth/sign-in/password').at(-1)?.body).toEqual({ email: 'ravi@prairiewrench.ca', password: PASSWORD });
  });

  it('an account without a second factor goes to enrolment — never to a dead end', async () => {
    routes['/api/auth/sign-in/password'] = () => ok({ step: 'enroll', session: { user: amara, acr: null, needsSecondFactor: true } });
    const { ui } = renderPage({ mode: 'signin', next: '/onboarding?type=provider' });
    await signInWithPassword(ui, 'amara@example.ca');
    expect(await screen.findByRole('heading', { name: 'Set up your second factor' })).toBeTruthy();
    expect(screen.queryByText(/Confirm it’s you/)).toBeNull();
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.type(await screen.findByLabelText('6-digit code from the app'), '111222');
    await ui.click(screen.getByRole('button', { name: 'Confirm code' }));
    await ui.click(await screen.findByRole('checkbox', { name: 'I saved my backup codes somewhere safe' }));
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect((await screen.findByRole('status')).textContent).toContain('Welcome back, Amara.');
  });

  it('an account whose email is not confirmed yet gets a new code', async () => {
    routes['/api/auth/sign-in/password'] = () => ok({ step: 'verify-email', email: 'amara@example.ca', resendAfterSeconds: 60 });
    const { ui } = renderPage({ mode: 'signin' });
    await signInWithPassword(ui, 'amara@example.ca');
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeTruthy();
    await ui.type(screen.getByLabelText('Code from the email'), '482913');
    await ui.click(screen.getByRole('button', { name: 'Confirm email' }));
    expect(await screen.findByRole('heading', { name: 'Set up your second factor' })).toBeTruthy();
  });

  it('a mobile app sent the browser here (S-29): goes back to its authorization request, not to the BFF', async () => {
    const authorize = 'http://localhost:9000/oauth2/authorize?response_type=code&client_id=mobile-consumer&state=s29&continue';
    routes['/api/auth/sign-in/totp'] = () => ok({ user, acr: 'mfa', continueTo: authorize });
    const { ui, navigate } = renderPage({ mode: 'signin' });
    await signInWithPassword(ui);
    await ui.type(await screen.findByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    await screen.findByRole('status');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith(authorize);
  });

  it('never follows a continueTo that is not an authorization request on northline-auth', async () => {
    routes['/api/auth/sign-in/totp'] = () => ok({ user, acr: 'mfa', continueTo: 'https://evil.example/oauth2/authorize?x' });
    const { ui, navigate } = renderPage({ mode: 'signin' });
    await signInWithPassword(ui);
    await ui.type(await screen.findByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    await screen.findByRole('status');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2F');
  });

  it('coming from onboarding uses the onboarding copy', async () => {
    const { ui, navigate } = renderPage({ mode: 'signin', next: '/onboarding' });
    expect(screen.getByRole('heading', { name: 'Sign in to start onboarding' })).toBeTruthy();
    await signInWithPassword(ui);
    await ui.type(await screen.findByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect((await screen.findByRole('status')).textContent).toContain('Welcome back, Ravi. Your existing login and passkey carry over to the business.');
    await ui.click(screen.getByRole('button', { name: 'Continue to onboarding' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Fonboarding');
  });

  it('"Recover with a backup code" opens the backup-code factor after the password', async () => {
    const { ui } = renderPage({ mode: 'signin' });
    await ui.click(screen.getByRole('button', { name: 'Recover with a backup code' }));
    await signInWithPassword(ui);
    expect((await screen.findByRole('radio', { name: /Backup code/ })).getAttribute('aria-checked')).toBe('true');
    await ui.click(screen.getByRole('button', { name: 'Verify backup code' }));
    expect(await screen.findByText('Enter one of your backup codes.')).toBeTruthy();
    await ui.type(screen.getByLabelText('Backup code'), 'ravis-00001');
    await ui.click(screen.getByRole('button', { name: 'Verify backup code' }));
    expect(await screen.findByText("That backup code didn't work, or it was already used.")).toBeTruthy();
  });

  it('switches tabs through the route', async () => {
    const { ui, onModeChange } = renderPage({ mode: 'signin' });
    await ui.click(screen.getByRole('tab', { name: 'Create account' }));
    expect(onModeChange).toHaveBeenCalledWith('register');
  });

  it('shows the signed-out top bar', () => {
    renderPage({ mode: 'signin' });
    expect(screen.getByText('Signed out')).toBeTruthy();
    expect((screen.getByRole('link', { name: /Google/ })).getAttribute('href')).toBe('http://localhost:9000/oauth2/authorization/google');
  });
});

describe('Forgot password', () => {
  it('email → code + new password → sign in again', async () => {
    const { ui } = renderPage({ mode: 'signin' });
    await ui.click(screen.getByRole('button', { name: 'Forgot password?' }));
    expect(await screen.findByRole('heading', { name: 'Reset your password' })).toBeTruthy();
    await ui.type(screen.getByLabelText('Email'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Send code' }));
    expect(calls.at(-1)).toMatchObject({ path: '/api/auth/password/forgot', body: { email: 'ravi@prairiewrench.ca', app: 'studio' } });
    await ui.type(await screen.findByLabelText('Code from the email'), '730215');
    await ui.type(screen.getByLabelText('New password'), 'short');
    await ui.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByText('Use at least 12 characters.')).toBeTruthy();
    await ui.clear(screen.getByLabelText('New password'));
    await ui.type(screen.getByLabelText('New password'), 'Maple syrup on fresh snow 42');
    await ui.type(screen.getByLabelText('Confirm password'), 'Maple syrup on fresh snow 42');
    await ui.click(screen.getByRole('button', { name: 'Set new password' }));
    expect((await screen.findByRole('status')).textContent).toContain('Password changed.');
    await ui.click(screen.getByRole('button', { name: 'Sign in' }));
    expect((screen.getByLabelText('Email') as HTMLInputElement).value).toBe('ravi@prairiewrench.ca');
  });

  it('opens on /forgot-password', () => {
    renderPage({ mode: 'signin', forgotOnLoad: true });
    expect(screen.getByRole('heading', { name: 'Reset your password' })).toBeTruthy();
  });
});

// ── S-9 rate limits: 429 rate_limited + Retry-After ─────────────────────────────────────────────────────────────────

const limited = (seconds: number): Reply => ({ status: 429, body: { code: 'rate_limited', retryAfterSeconds: seconds, detail: 'Too many attempts. Wait a moment and try again.' } });

describe('Rate limits (429 rate_limited)', () => {
  it('sign-in: too many wrong passwords show the wait and block Sign in until it is over', async () => {
    routes['/api/auth/sign-in/password'] = () => limited(90);
    const { ui } = renderPage({ mode: 'signin' });
    await signInWithPassword(ui);
    const alert = await screen.findByText(/^Too many attempts\. Try again in 1:(30|29)\.$/);
    expect(alert.closest('[role="alert"]')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('sign-in: a locked factor counts down, then lets the person try again', async () => {
    routes['/api/auth/sign-in/totp'] = () => limited(1);
    const { ui } = renderPage({ mode: 'signin' });
    await signInWithPassword(ui);
    await ui.type(await screen.findByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByText('Too many attempts. Try again in 0:01.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Verify code' }) as HTMLButtonElement).disabled).toBe(true);
    await waitFor(() => expect(screen.queryByText(/Too many attempts/)).toBeNull());
    expect((screen.getByRole('button', { name: 'Verify code' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('sign-in in French', async () => {
    routes['/api/auth/sign-in/password'] = () => limited(300);
    const { ui } = renderPage({ mode: 'signin', locale: 'fr' });
    await ui.type(screen.getByLabelText('Courriel'), 'ravi@prairiewrench.ca');
    await ui.type(screen.getByLabelText('Mot de passe'), PASSWORD);
    await ui.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(await screen.findByText(/^Trop de tentatives\. Réessayez dans (5:00|4:59)\.$/)).toBeTruthy();
  });

  it('register: creating the account is refused with the wait', async () => {
    routes['/api/auth/accounts'] = () => limited(3600);
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText(/^Too many attempts\. Try again in (60:00|59:5\d)\.$/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Create account' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('a federated sign-in refused by the limits explains it', () => {
    render(
      <I18nProvider initial="en">
        <QueryClientProvider client={new QueryClient()}>
          <SignedOutPage mode="signin" error="rate_limited" onModeChange={vi.fn()} navigate={vi.fn()} />
        </QueryClientProvider>
      </I18nProvider>,
    );
    expect(screen.getByText('Too many attempts. Wait a moment and try again.')).toBeTruthy();
  });
});

describe('Google and Apple (S-18)', () => {
  const page = (props: Partial<Parameters<typeof SignedOutPage>[0]> & { mode: AuthMode }, locale: 'en' | 'fr' = 'en') => render(
    <I18nProvider initial={locale}>
      <QueryClientProvider client={new QueryClient()}>
        <SignedOutPage onModeChange={vi.fn()} navigate={vi.fn()} {...props} />
      </QueryClientProvider>
    </I18nProvider>,
  );

  it('explains a cancelled or unavailable provider', () => {
    page({ mode: 'signin', error: 'federation_cancelled' });
    expect(screen.getByText('Signing in with Google or Apple was cancelled. Try again or use your email.')).toBeTruthy();
  });

  it('says when the provider is not available here, in French too', () => {
    page({ mode: 'signin', error: 'federation_unavailable' }, 'fr');
    expect(screen.getByText('La connexion avec Google ou Apple n’est pas offerte pour le moment. Utilisez plutôt votre courriel ou votre mobile.')).toBeTruthy();
  });

  it('linking an existing account: the email is filled in, the password and the factor follow', async () => {
    const ui = userEvent.setup({ delay: null });
    page({ mode: 'signin', resumeIdentifier: 'ravi@prairiewrench.ca', federation: { provider: 'google', linking: true, relay: false } });
    expect(screen.getByText('Sign in with your password and your passkey, authenticator app or a backup code to link your Google account.')).toBeTruthy();
    expect((screen.getByLabelText('Email') as HTMLInputElement).value).toBe('ravi@prairiewrench.ca');
    await ui.type(screen.getByLabelText('Password'), PASSWORD);
    await ui.click(screen.getByRole('button', { name: 'Sign in' }));
    await ui.type(await screen.findByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    await waitFor(() => expect(calls.some(c => c.path === '/api/auth/sign-in/totp')).toBe(true));
  });

  it('continues a new Apple account with the private relay email pre-filled', () => {
    page({ mode: 'register', prefill: { firstName: 'Élodie', lastName: 'Tremblay', email: 'x1y2@privaterelay.appleid.com' }, federation: { provider: 'apple', linking: false, relay: true } });
    expect(screen.getByText(/You’re signed in with Apple\. Choose a password, confirm your email and add a second factor/)).toBeTruthy();
    expect(screen.getByText(/Hide My Email/)).toBeTruthy();
    expect((screen.getByLabelText('Email') as HTMLInputElement).value).toBe('x1y2@privaterelay.appleid.com');
    expect((screen.getByLabelText('First name') as HTMLInputElement).value).toBe('Élodie');
  });

  it('points the buttons at the auth server', () => {
    page({ mode: 'signin' });
    expect(screen.getByRole('link', { name: 'Google' }).getAttribute('href')).toBe('http://localhost:9000/oauth2/authorization/google');
    expect(screen.getByRole('link', { name: 'Apple' }).getAttribute('href')).toBe('http://localhost:9000/oauth2/authorization/apple');
  });
});

// ── S-20: limit store down, codes and second factors fail closed (503 sign_in_unavailable) ──────────────────────────

describe('Sign-in paused (503 sign_in_unavailable)', () => {
  const paused: Reply = { status: 503, body: { code: 'sign_in_unavailable', retryAfterSeconds: 30, detail: 'x' } };

  it('the factor step explains the pause', async () => {
    routes['/api/auth/sign-in/totp'] = () => paused;
    const { ui, navigate } = renderPage({ mode: 'signin' });
    await signInWithPassword(ui);
    await ui.type(await screen.findByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByText('Signing in is paused for a few minutes while we fix a problem on our side. Try again shortly.')).toBeTruthy();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('creating the account explains the pause, in French too', async () => {
    routes['/api/auth/accounts'] = () => paused;
    const { ui } = renderPage({ locale: 'fr' });
    await ui.type(screen.getByLabelText('Prénom'), 'Amara');
    await ui.type(screen.getByLabelText('Nom'), 'Osei');
    await ui.type(screen.getByLabelText('Courriel'), 'amara@example.ca');
    await ui.type(screen.getByLabelText('Mot de passe'), PASSWORD);
    await ui.type(screen.getByLabelText('Confirmez le mot de passe'), PASSWORD);
    await ui.click(screen.getByRole('checkbox'));
    await ui.click(screen.getByRole('button', { name: 'Créer le compte' }));
    expect(await screen.findByText('La connexion est suspendue quelques minutes, le temps de régler un problème de notre côté. Réessayez sous peu.')).toBeTruthy();
  });
});
