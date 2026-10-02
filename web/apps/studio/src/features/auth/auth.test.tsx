import { render, screen, waitFor, within } from '@testing-library/react';
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
    calls.push({ path, body, headers: (init?.headers ?? {}) as Record<string, string> });
    const handler = routes[path];
    const reply = handler ? handler(body) : { status: 404 };
    return new Response(reply.body === undefined ? '' : JSON.stringify(reply.body), { status: reply.status, headers: { 'content-type': 'application/json' } });
  }));
});
afterEach(() => vi.unstubAllGlobals());

function renderPage(props: { mode?: AuthMode; next?: string; locale?: 'en' | 'fr' } = {}) {
  const navigate = vi.fn();
  const onModeChange = vi.fn();
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  render(
    <I18nProvider initial={props.locale ?? 'en'}>
      <QueryClientProvider client={qc}>
        <SignedOutPage mode={props.mode ?? 'register'} next={props.next} onModeChange={onModeChange} navigate={navigate} />
      </QueryClientProvider>
    </I18nProvider>,
  );
  return { navigate, onModeChange, ui: userEvent.setup({ delay: null }) };
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
    await expectNoAxeViolations(document.body); // S-109
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
    // S-109 (WCAG 3.3.8): the code pastes as the SMS shows it ("123 456"), not cut to "123 45" by a 6-character limit
    await ui.clear(screen.getByLabelText(/Enter the 6-digit code/));
    await ui.click(screen.getByLabelText(/Enter the 6-digit code/));
    await ui.paste('123 456');
    expect((screen.getByLabelText(/Enter the 6-digit code/) as HTMLInputElement).value).toBe('123456');
    await ui.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText('Second factor · required for business accounts')).toBeTruthy();
    expect(screen.getByText('SMS is a backup only — not accepted as the primary factor.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Create passkey' })).toBeTruthy();
    await ui.click(screen.getByRole('radio', { name: /Authenticator app/ }));
    await ui.click(screen.getByRole('button', { name: 'Scan QR code' }));

    expect((await screen.findByRole('img', { name: 'QR code for your authenticator app' })).getAttribute('src')).toBe('data:image/png;base64,AAAA');
    await expectNoAxeViolations(document.body); // S-109
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

describe('Create account — code delivery (S-8)', () => {
  it('sends the UI language, so the code is worded in it', async () => {
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));
    await screen.findByLabelText(/code/i);
    expect(calls.find(c => c.path === '/api/auth/register')?.headers['accept-language']).toBe('en-CA');
  });

  it('the provider could not send the first code: says so, the form stays', async () => {
    routes['/api/auth/register'] = () => ({ status: 503, body: { code: 'code_not_sent', detail: 'x' } });
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));
    expect(await screen.findByText("We couldn't send a code to this number right now. Try again in a moment.")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Send verification code' })).toBeTruthy();
  });

  it('a call that could not be placed offers the text message instead', async () => {
    routes['/api/auth/register/resend'] = () => ({ status: 503, body: { code: 'code_not_sent', detail: 'x' } });
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));
    await ui.click(await screen.findByRole('button', { name: 'Call me instead' }));
    expect(await screen.findByText("We couldn't call this number. Try again in a moment, or resend the code by text.")).toBeTruthy();
  });
});

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

  it('a mobile app sent the browser here (S-29): goes back to its authorization request, not to the BFF', async () => {
    const authorize = 'http://localhost:9000/oauth2/authorize?response_type=code&client_id=mobile-consumer&state=s29&continue';
    routes['/api/auth/sign-in/totp'] = () => ok({ user, acr: 'mfa', continueTo: authorize });
    const { ui, navigate } = renderPage({ mode: 'signin' });
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.click(await screen.findByRole('radio', { name: /Authenticator app/ }));
    await ui.type(screen.getByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    await screen.findByRole('status');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith(authorize);
  });

  it('never follows a continueTo that is not an authorization request on northline-auth', async () => {
    routes['/api/auth/sign-in/totp'] = () => ok({ user, acr: 'mfa', continueTo: 'https://evil.example/oauth2/authorize?x' });
    const { ui, navigate } = renderPage({ mode: 'signin' });
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.click(await screen.findByRole('radio', { name: /Authenticator app/ }));
    await ui.type(screen.getByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    await screen.findByRole('status');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    expect(navigate).toHaveBeenCalledWith('/bff/login?next=%2F');
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

// ── S-9 rate limits: 429 rate_limited + Retry-After ─────────────────────────────────────────────────────────────────

const limited = (seconds: number): Reply => ({ status: 429, body: { code: 'rate_limited', retryAfterSeconds: seconds, detail: 'Too many attempts. Wait a moment and try again.' } });

describe('Rate limits (429 rate_limited)', () => {
  it('sign-in: the identifier step shows the wait and blocks Continue until it is over', async () => {
    routes['/api/auth/sign-in'] = () => limited(90);
    const { ui } = renderPage({ mode: 'signin' });
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    const alert = await screen.findByText(/^Too many attempts\. Try again in 1:(30|29)\.$/);
    expect(alert.closest('[role="alert"]')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Continue' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByText('Second factor required for ravi@prairiewrench.ca.')).toBeNull();
  });

  it('sign-in: a locked factor counts down, then lets the person try again', async () => {
    routes['/api/auth/sign-in/totp'] = () => limited(1);
    const { ui } = renderPage({ mode: 'signin' });
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.click(await screen.findByRole('radio', { name: /Authenticator app/ }));
    await ui.type(screen.getByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByText('Too many attempts. Try again in 0:01.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Verify code' }) as HTMLButtonElement).disabled).toBe(true);
    // The 1 s countdown runs on real time; the wait is setup.ts's asyncUtilTimeout.
    await waitFor(() => expect(screen.queryByText(/Too many attempts/)).toBeNull());
    expect((screen.getByRole('button', { name: 'Verify code' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('sign-in in French', async () => {
    routes['/api/auth/sign-in'] = () => limited(300);
    const { ui } = renderPage({ mode: 'signin', locale: 'fr' });
    await ui.type(screen.getByLabelText('Courriel ou mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Continuer' }));
    expect(await screen.findByText(/^Trop de tentatives\. Réessayez dans (5:00|4:59)\.$/)).toBeTruthy();
  });

  it('register: sending the code is refused with the wait', async () => {
    routes['/api/auth/register'] = () => limited(3600);
    const { ui } = renderPage();
    await fillRegistration(ui);
    await ui.click(screen.getByRole('button', { name: 'Send verification code' }));
    expect(await screen.findByText(/^Too many attempts\. Try again in (60:00|59:5\d)\.$/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Send verification code' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('register: wrong phone codes and resends that hit the limit show the wait', async () => {
    routes['/api/auth/register/verify'] = () => limited(900);
    routes['/api/auth/register/resend'] = () => limited(900);
    const { ui } = renderPage({ locale: 'fr' });
    await ui.type(screen.getByLabelText('Prénom'), 'Amara');
    await ui.type(screen.getByLabelText('Nom'), 'Osei');
    await ui.type(screen.getByLabelText('Numéro de mobile'), '+1 403 555 0148');
    await ui.type(screen.getByLabelText('Courriel'), 'amara@example.ca');
    await ui.click(screen.getByRole('checkbox'));
    await ui.click(screen.getByRole('button', { name: 'Envoyer le code de vérification' }));
    await ui.click(await screen.findByRole('button', { name: 'M’appeler plutôt' }));
    expect(await screen.findByText(/^Trop de tentatives\. Réessayez dans 1[45]:\d\d\.$/)).toBeTruthy();
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

  it('asks for the second factor before linking an existing account, and signs in with it', async () => {
    const ui = userEvent.setup({ delay: null });
    page({ mode: 'signin', resumeIdentifier: 'ravi@prairiewrench.ca', federation: { provider: 'google', linking: true, relay: false } });
    expect(screen.getByText('Confirm it’s you with your passkey, authenticator app or a backup code to link your Google account.')).toBeTruthy();
    await waitFor(() => expect(calls.some(c => c.path === '/api/auth/sign-in' && c.body.identifier === 'ravi@prairiewrench.ca')).toBe(true));
    await ui.click(await screen.findByText('Authenticator app'));
    await ui.type(await screen.findByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: /verify/i }));
    await waitFor(() => expect(calls.some(c => c.path === '/api/auth/sign-in/totp')).toBe(true));
  });

  it('continues a new Apple account with the private relay email pre-filled', () => {
    page({ mode: 'register', prefill: { firstName: 'Élodie', lastName: 'Tremblay', email: 'x1y2@privaterelay.appleid.com' }, federation: { provider: 'apple', linking: false, relay: true } });
    expect(screen.getByText(/You’re signed in with Apple\. Add your mobile number and a second factor/)).toBeTruthy();
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
    await ui.type(screen.getByLabelText('Email or mobile'), 'ravi@prairiewrench.ca');
    await ui.click(screen.getByRole('button', { name: 'Continue' }));
    await ui.click(await screen.findByRole('radio', { name: /Authenticator app/ }));
    await ui.type(screen.getByLabelText('6-digit code'), '654321');
    await ui.click(screen.getByRole('button', { name: 'Verify code' }));
    expect(await screen.findByText('Signing in is paused for a few minutes while we fix a problem on our side. Try again shortly.')).toBeTruthy();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('sending the phone code explains the pause, in French too', async () => {
    routes['/api/auth/register'] = () => paused;
    const { ui } = renderPage({ locale: 'fr' });
    await ui.type(screen.getByLabelText('Prénom'), 'Amara');
    await ui.type(screen.getByLabelText('Nom'), 'Osei');
    await ui.type(screen.getByLabelText('Numéro de mobile'), '+1 403 555 0148');
    await ui.type(screen.getByLabelText('Courriel'), 'amara@example.ca');
    await ui.click(screen.getByRole('checkbox'));
    await ui.click(screen.getByRole('button', { name: 'Envoyer le code de vérification' }));
    expect(await screen.findByText('La connexion est suspendue quelques minutes, le temps de régler un problème de notre côté. Réessayez sous peu.')).toBeTruthy();
  });
});
