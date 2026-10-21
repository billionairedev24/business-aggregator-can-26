import { apiErrorOf, NetworkError, type Locale } from '@northline/mobile-kit';

/**
 * northline-auth's JSON sign-in API (the one the consumer site's S-62 pages use), called from the app's own screens in
 * the app's HTTP session: the auth server keeps the registration / sign-in in a cookie session (the platform's cookie
 * store, never JS), so every call sends `credentials: 'include'`. A native request has no `Origin`, which the auth
 * server's origin check lets through (WebSecurityConfig). Errors: `ApiError` (422 field rules, 409 restart, 429…),
 * `NetworkError`.
 */
export interface AuthUser {
  id: string;
  firstName: string;
  lastName: string;
  email?: string | null;
  phone?: string | null;
  initials: string;
}
/**
 * A signed-in auth session; `continueTo` = the app's authorization request to go back to (S-29); `needsSecondFactor`
 * (S-154) = the account has no passkey and no authenticator yet (enrolment is offered, never required, for customers).
 */
export interface AuthSessionAnswer {
  user: AuthUser;
  acr?: string | null;
  continueTo?: string | null;
  needsSecondFactor?: boolean;
}
/** S-154: an account email is on its way (the same answer whether or not the address has an account). */
export interface EmailSent {
  step: 'verify-email';
  email: string;
  resendAfterSeconds: number;
}
/** S-154: after email + password — the account's own factors, enrolment (signed in), or its email still to confirm. */
export interface PasswordStep {
  step: 'factor' | 'enroll' | 'verify-email';
  factors?: string[] | null;
  session?: AuthSessionAnswer | null;
  email?: string | null;
  resendAfterSeconds?: number | null;
}
/** S-154: the authenticator is set up (acr=mfa); the ten backup codes are shown once. */
export interface Enrolled {
  session: AuthSessionAnswer;
  backupCodes: string[];
}
export interface CreateAccountValues {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  passwordConfirm: string;
  phone: string;
  terms: boolean;
  /** S-157: the ticked marketing boxes (CASL express consent), with the wording version shown */
  marketing?: MarketingConsent[];
}
export interface CodeSent {
  resendAfterSeconds: number;
  channel: 'sms' | 'voice';
}
export interface TotpSetup {
  secret: string;
  otpauthUri: string;
  /** A PNG data URI of the otpauth:// URI. */
  qrCode: string;
}
/** S-157: a ticked marketing checkbox. */
export interface MarketingConsent {
  category: 'marketing_email' | 'marketing_sms';
  wordingVersion: string;
}
/** S-157: `GET /api/auth/accounts/consent-wordings?app=consumer` — the sign-up's marketing checkboxes. */
export interface ConsentWordings {
  requester: string;
  items: { category: 'marketing_email' | 'marketing_sms'; channel: string; wordingVersion: string; wording: string }[];
}

export class AuthApi {
  constructor(
    private readonly issuer: string,
    private readonly fetchImpl: typeof fetch,
    private readonly language: () => Locale,
  ) {}

  private async post<T>(path: string, body: unknown = {}): Promise<T> {
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.issuer}${path}`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          // the SMS / voice code and the emails are worded in it (S-8, S-154)
          'Accept-Language': this.language() === 'fr-CA' ? 'fr-CA' : 'en-CA',
        },
        body: JSON.stringify(body),
      });
    } catch (e) {
      throw new NetworkError(e);
    }
    if (!res.ok) throw await apiErrorOf(res);
    const text = await res.text();
    return (text ? JSON.parse(text) : {}) as T;
  }

  // ── S-154: email + password accounts (the consumer site's API, `app: consumer` for the emails' links) ─────────────
  createAccount(v: CreateAccountValues) {
    return this.post<EmailSent>('/api/auth/accounts', {
      firstName: v.firstName.trim(),
      lastName: v.lastName.trim(),
      email: v.email.trim(),
      password: v.password,
      passwordConfirm: v.passwordConfirm,
      phone: v.phone.trim() || null,
      terms: v.terms,
      termsLanguage: this.language() === 'fr-CA' ? 'fr' : 'en',
      app: 'consumer',
      // S-157: the ticked offers boxes (CASL express consent); the accounts endpoint records them as the app's sign-up
      ...(v.marketing?.length ? { marketing: v.marketing } : {}),
    });
  }

  /** S-157: the wordings of the sign-up's marketing checkboxes, in the app's language. */
  async consentWordings(): Promise<ConsentWordings> {
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.issuer}/api/auth/accounts/consent-wordings?app=consumer`, {
        credentials: 'include',
        headers: { Accept: 'application/json', 'Accept-Language': this.language() === 'fr-CA' ? 'fr-CA' : 'en-CA' },
      });
    } catch (e) {
      throw new NetworkError(e);
    }
    if (!res.ok) throw await apiErrorOf(res);
    return (await res.json()) as ConsentWordings;
  }
  resendVerification() {
    return this.post<EmailSent>('/api/auth/accounts/resend');
  }
  verifyEmail(code: string) {
    return this.post<AuthSessionAnswer>('/api/auth/accounts/verify', { code: code.trim() });
  }
  signInWithPassword(email: string, password: string) {
    return this.post<PasswordStep>('/api/auth/sign-in/password', { email: email.trim(), password });
  }
  signInTotp(code: string) {
    return this.post<AuthSessionAnswer>('/api/auth/sign-in/totp', { code: code.trim() });
  }
  signInBackupCode(code: string) {
    return this.post<AuthSessionAnswer>('/api/auth/sign-in/backup-code', { code: code.trim() });
  }
  enrolTotpSetup() {
    return this.post<TotpSetup>('/api/auth/enroll/totp');
  }
  enrolTotp(code: string) {
    return this.post<Enrolled>('/api/auth/enroll/totp/verify', { code: code.trim() });
  }
  forgotPassword(email: string, app: 'consumer' = 'consumer') {
    return this.post<EmailSent>('/api/auth/password/forgot', { email: email.trim(), app });
  }
  resetPassword(code: string, password: string, passwordConfirm: string) {
    return this.post<{ email: string }>('/api/auth/password/reset', { code: code.trim(), password, passwordConfirm });
  }

  // ── the phone code (S-62): sign-in stays as an option ────────────────────────────────────────────────────────────
  startSignIn(identifier: string) {
    return this.post<{ identifier: string; factors: string[] }>('/api/auth/sign-in', { identifier: identifier.trim() });
  }
  sendSignInCode(channel: 'sms' | 'voice' = 'sms') {
    return this.post<CodeSent>('/api/auth/sign-in/code', { channel });
  }
  signInWithCode(code: string) {
    return this.post<AuthSessionAnswer>('/api/auth/sign-in/code/verify', { code: code.trim() });
  }
}
