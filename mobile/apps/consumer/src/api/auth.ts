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
export interface RegistrationStep {
  step: 'otp' | 'mfa';
  /** The number as the server formats it ("+1 (555) 555-0148"). */
  phone: string;
  resendAfterSeconds: number;
  channel: 'sms' | 'voice';
}
/** A signed-in auth session; `continueTo` = the app's authorization request to go back to (S-29). */
export interface AuthSessionAnswer {
  user: AuthUser;
  acr?: string | null;
  continueTo?: string | null;
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
export interface RegisterValues {
  firstName: string;
  lastName: string;
  phone: string;
  email: string;
  terms: boolean;
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
          // the SMS / voice code is worded in it (S-8)
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

  register(v: RegisterValues) {
    return this.post<RegistrationStep>('/api/auth/register', {
      firstName: v.firstName.trim(),
      lastName: v.lastName.trim(),
      phone: v.phone.trim(),
      email: v.email.trim(),
      terms: v.terms,
    });
  }
  resend(channel: 'sms' | 'voice') {
    return this.post<RegistrationStep>('/api/auth/register/resend', { channel });
  }
  verifyPhone(code: string) {
    return this.post<RegistrationStep>('/api/auth/register/verify', { code: code.trim() });
  }
  totpSetup() {
    return this.post<TotpSetup>('/api/auth/register/totp');
  }
  registerTotp(code: string) {
    return this.post<AuthSessionAnswer>('/api/auth/register/totp/verify', { code: code.trim() });
  }
  /** S-62: the account without a second factor (design "SMS code · backup only"). */
  completeWithoutSecondFactor() {
    return this.post<AuthSessionAnswer>('/api/auth/register/complete');
  }
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
