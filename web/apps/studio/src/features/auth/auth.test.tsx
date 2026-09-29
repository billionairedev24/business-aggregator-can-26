import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nProvider } from '@northline/ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SignedOutPage, type AuthMode } from './SignedOutPage';

// ── fake northline-auth ───────────────────────────────────────────────────────────────────────────────────────────

type Reply = { status: number; body?: unknown };
type Handler = (body: Record<string, unknown>) => Reply;
let routes: Record<string, Handler>;
let calls: { path: string; body: Record<string, unknown> }[];

const user = { id: '01J9ZD3V00000000000000RAV1', firstName: 'Ravi', lastName: 'Sandhu', email: 'ravi@prairiewrench.ca', phone: '+14035550148', initials: 'RS', locale: 'en-CA', memberSince: '2026-01-05' };
const ok = (body: unknown): Reply => ({ status: 200, body });
const invalid = (field: string, rule: string, message: string): Reply => ({ status: 422, body: { errors: [{ field, rule, message }] } });

beforeEach(() => {
  calls = [];
  routes = {
    '/api/auth/register': () => ok({ step: 'otp', phone: '+1 403 555 0148', resendAfterSeconds: 45, channel: 'sms' }),
    '/api/auth/register/verify': b => (b.code === '123456' ? ok({ step: 'mfa', phone: '+1 403 555 0148', resendAfterSeconds: 0, channel: 'sms' }) : invalid('code', 'mismatch', "That code doesn't match. Check it and try again.")),
    '/api/auth/register/totp': () => ok({ secret: 'ABCDEFGHIJKLMNOP', otpauthUri: 'otpauth://totp/Northline:a?secret=ABCDEFGHIJKLMNOP', qrCode: 'data:image/png;base64,AAAA' }),
    '/api/auth/register/totp/verify': () => ({ status: 201, body: { user: { ...user, firstName: 'Amara', lastName: 'Osei', initials: 'AO' }, acr: 'mfa' } }),
    '/api/auth/sign-in': b => ok({ identifier: b.identifier, factors: ['passkey', 'totp', 'backup_code'] }),
    '/api/auth/sign-in/totp': b => (b.code === '654321' ? ok({ user, acr: 'mfa' }) : invalid('code', 'mismatch', "That code didn't work. Check it and try again.")),
    '/api/auth/sign-in/backup-code': () => invalid('code', 'mismatch', "That backup code didn't work, or it was already used."),
  };
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://localhost').pathname;
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {};
    calls.push({ path, body });
    const handler = routes[path];
    const reply = handler ? handler(body) : { status: 404 };
    return new Response(reply.body === undefined ? '' : JSON.stringify(reply.body), { status: reply.status, headers: { 'content-type': 'application/json' } });
  }));
});
afterEach(() => vi.unstubAllGlobals());

function renderPage(props: { mode?: AuthMode; next?: string } = {}) {
  const navigate = vi.fn();
  const onModeChange = vi.fn();
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  render(
    <I18nProvider initial="en">
      <QueryClientProvider client={qc}>
        <SignedOutPage mode={props.mode ?? 'register'} next={props.next} onModeChange={onModeChange} navigate={navigate} />
      </QueryClientProvider>
    </I18nProvider>,
  );
  return { navigate, onModeChange, ui: userEvent.setup() };
}

async function fillRegistration(ui: ReturnType<typeof userEvent.setup>) {
  await ui.type(screen.getByLabelText('First name'), 'Amara');
  await ui.type(screen.getByLabelText('Last name'), 'Osei');
  await ui.type(screen.getByLabelText('Mobile number'), '+1 403 555 0148');
  await ui.type(screen.getByLabelText('Email'), 'amara@example.ca');
  await ui.click(screen.getByRole('checkbox'));
}

// ── Create account ────────────────────────────────────────────────────────────────────────────────────────────────

describe('Create account — validation (validation-rules.md § Registration)', () => {
  it('shows nothing before touch, then every required message and the summary on submit', async () => {
    const { ui } = renderPage();
    expect(screen.getByRole('heading', { name: 'Create your account' })).toBeTruthy();
    expect(screen.queryAllByRole('alert')).toHaveLength(0);

    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));

    expect(await screen.findByText('First name is required.')).toBeTruthy();
    expect(screen.getByText('Last name is required.')).toBeTruthy();
    expect(screen.getByText('Mobile number is required for verification.')).toBeTruthy();
    expect(screen.getByText('Email is required.')).toBeTruthy();
    expect(screen.getByText('You need to accept the Terms and Privacy Policy.')).toBeTruthy();
    expect(screen.getByText('5 things need attention.')).toBeTruthy();
    expect(calls).toHaveLength(0);
  });

  it('validates a field once it is touched', async () => {
    const { ui } = renderPage();
    const first = screen.getByLabelText('First name');
    await ui.type(first, 'A');
    await ui.clear(first);
    expect(await screen.findByText('First name is required.')).toBeTruthy();
    expect((first).getAttribute('aria-invalid')).toBe('true');
    // untouched fields stay quiet
    expect(screen.queryByText('Email is required.')).toBeNull();
  });

  it('shows the format messages for phone and email', async () => {
    const { ui } = renderPage();
    await ui.type(screen.getByLabelText('Mobile number'), '555-01');
    await ui.type(screen.getByLabelText('Email'), 'not-an-email');
    expect(await screen.findByText('Enter a valid Canadian mobile, e.g. +1 403 555 0148.')).toBeTruthy();
    expect(screen.getByText("That doesn't look like an email address.")).toBeTruthy();
  });

  it('maps a server 422 (email taken) onto the email field', async () => {
    routes['/api/auth/register'] = () => invalid('email', 'unique', 'An account already uses this email. Sign in instead.');
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));
    const email = screen.getByLabelText('Email');
    expect(await screen.findByText('An account already uses this email. Sign in instead.')).toBeTruthy();
    expect((email).getAttribute('aria-invalid')).toBe('true');
  });

  it('links the Terms and Privacy Policy in a new tab', () => {
    renderPage();
    expect((screen.getByRole('link', { name: 'Terms of Service' })).getAttribute('href')).toBe('/legal/terms.html');
    expect((screen.getByRole('link', { name: 'Terms of Service' })).getAttribute('target')).toBe('_blank');
    expect((screen.getByRole('link', { name: 'Privacy Policy' })).getAttribute('href')).toBe('/legal/privacy.html');
  });
});

describe('Create account — steps', () => {
  it('form → code → authenticator app → account created → onboarding hand-off', async () => {
    const { ui, navigate } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));

    expect(await screen.findByLabelText('Enter the 6-digit code sent to +1 403 555 0148')).toBeTruthy();
    expect(screen.getByText(/^Resend in 0:4\d$/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Call me instead' }) as HTMLButtonElement).disabled).toBe(false);
    expect(calls[0]).toMatchObject({ path: '/api/auth/register', body: { firstName: 'Amara', lastName: 'Osei', phone: '+1 403 555 0148', email: 'amara@example.ca', terms: true } });

    // wrong length (client), then wrong code (server), then the right one
    await ui.type(screen.getByLabelText(/Enter the 6-digit code/), '12');
    await ui.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByText('The code is 6 digits.')).toBeTruthy();
    await ui.clear(screen.getByLabelText(/Enter the 6-digit code/));
    await ui.type(screen.getByLabelText(/Enter the 6-digit code/), '000000');
    await ui.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByText("That code doesn't match. Check it and try again.")).toBeTruthy();
    await ui.clear(screen.getByLabelText(/Enter the 6-digit code/));
    await ui.type(screen.getByLabelText(/Enter the 6-digit code/), '123456');
    await ui.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText('Second factor · required for business accounts')).toBeTruthy();
    expect(screen.getByText('SMS is a backup only — not accepted as the primary factor.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Create passkey' })).toBeTruthy();
    await ui.click(screen.getByRole('radio', { name: /Authenticator app/ }));
    await ui.click(screen.getByRole('button', { name: 'Scan QR code' }));

    expect((await screen.findByRole('img', { name: 'QR code for your authenticator app' })).getAttribute('src')).toBe('data:image/png;base64,AAAA');
    await ui.type(screen.getByLabelText('6-digit code'), '111111');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));

    const done = await screen.findByRole('status');
    expect(within(done).getByText('Account created.')).toBeTruthy();
    expect((done).textContent).toContain('Amara Osei · +1 403 555 0148 verified · second factor registered. This login also works for shopping.');
    await ui.click(screen.getByRole('button', { name: 'Continue to business onboarding' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Fonboarding');
  });

  it('passkey needs WebAuthn — explains when the browser has none', async () => {
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));
    await ui.type(await screen.findByLabelText(/Enter the 6-digit code/), '123456');
    await ui.click(screen.getByRole('button', { name: 'Verify' }));
    await ui.click(await screen.findByRole('button', { name: 'Create passkey' }));
    expect(await screen.findByText("This browser can't use passkeys. Choose another method.")).toBeTruthy();
  });

  it('a throttled resend shows the wait', async () => {
    routes['/api/auth/register/resend'] = () => ({ status: 429, body: { code: 'otp_throttled', retryAfterSeconds: 30, detail: 'Wait 30 s before sending another code.' } });
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));
    await ui.click(await screen.findByRole('button', { name: 'Call me instead' }));
    expect(await screen.findByText('Resend in 0:30')).toBeTruthy();
  });
});

// ── Sign in ───────────────────────────────────────────────────────────────────────────────────────────────────────

describe('Sign in', () => {
  it('email → authenticator code → signed in → hand-off to next', async () => {
    const { ui, navigate } = renderPage({ mode: 'signin', next: '/b/01J9ZD3V00000000000000PWM1/orders' });
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy();
    const cont = screen.getByRole('button', { name: 'Continue' });
    expect((cont as HTMLButtonElement).disabled).toBe(true);
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(cont);

    expect(await screen.findByText('Second factor required for ravi@prairiewrench.ca.')).toBeTruthy();
    expect((screen.getByRole('radio', { name: /Passkey/ })).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('button', { name: 'Use passkey' })).toBeTruthy();
    await ui.click(screen.getByRole('radio', { name: /Authenticator app/ }));
    await ui.type(screen.getByLabelText('6-digit code'), '000000');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByText("That code didn't work. Check it and try again.")).toBeTruthy();
    await ui.clear(screen.getByLabelText('6-digit code'));
    await ui.type(screen.getByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));

    const done = await screen.findByRole('status');
    expect((done).textContent).toContain('Signed in. Welcome back, Ravi.');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Fb%2F01J9ZD3V00000000000000PWM1%2Forders');
  });

  it('coming from onboarding uses the onboarding copy', async () => {
    const { ui, navigate } = renderPage({ mode: 'signin', next: '/onboarding' });
    expect(screen.getByRole('heading', { name: 'Sign in to start onboarding' })).toBeTruthy();
    await ui.type(screen.getByLabelText('Email or mobile'), '403 555 0148');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.click(await screen.findByRole('radio', { name: /Authenticator app/ }));
    await ui.type(screen.getByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect((await screen.findByRole('status')).textContent).toContain('Welcome back, Ravi. Your existing login and passkey carry over to the business.');
    await ui.click(screen.getByRole('button', { name: 'Continue to onboarding' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2Fonboarding');
  });

  it('backup code: required, then the server message for a used code', async () => {
    const { ui } = renderPage({ mode: 'signin' });
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.click(await screen.findByRole('radio', { name: /Backup code/ }));
    await ui.click(screen.getByRole('button', { name: 'Verify backup code' }));
    expect(await screen.findByText('Enter one of your backup codes.')).toBeTruthy();
    await ui.type(screen.getByLabelText('Backup code'), 'ravis-00001');
    await ui.click(screen.getByRole('button', { name: 'Verify backup code' }));
    expect(await screen.findByText("That backup code didn't work, or it was already used.")).toBeTruthy();
  });

  it('"Recover with a backup code" goes to the backup-code factor', async () => {
    const { ui } = renderPage({ mode: 'signin' });
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Recover with a backup code' }));
    expect((await screen.findByRole('radio', { name: /Backup code/ })).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByLabelText('Backup code')).toBeTruthy();
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
