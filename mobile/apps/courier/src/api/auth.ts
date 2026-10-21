import { apiErrorOf, NetworkError, type Locale } from '@northline/mobile-kit';

/**
 * S-159: northline-auth's JSON sign-in API (S-154: email + password, then the account's second factor) from the
 * courier app's own sign-in screen, in the app's HTTP session (the cookie stays in the platform's store, never in
 * JS; `credentials: 'include'`). The answer that signs in carries `continueTo` (S-29), which the kit's AppSignIn
 * follows to the DPoP token exchange. Errors: `ApiError` (422 rules, 409 restart, 429), `NetworkError`.
 */
export interface SignedIn {
  user: { id: string; firstName: string; lastName: string };
  acr?: string | null;
  continueTo?: string | null;
  needsSecondFactor?: boolean;
}
export interface PasswordStep {
  step: 'factor' | 'enroll' | 'verify-email';
  factors?: string[] | null;
  session?: SignedIn | null;
  email?: string | null;
  resendAfterSeconds?: number | null;
}
export interface TotpSetup { secret: string; otpauthUri: string; qrCode: string }
export interface Enrolled { session: SignedIn; backupCodes: string[] }
export interface EmailSent { step: 'verify-email'; email: string; resendAfterSeconds: number }

export class CourierAuthApi {
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
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'Accept-Language': this.language() === 'fr-CA' ? 'fr-CA' : 'en-CA' },
        body: JSON.stringify(body),
      });
    } catch (e) {
      throw new NetworkError(e);
    }
    if (!res.ok) throw await apiErrorOf(res);
    const text = await res.text();
    return (text ? JSON.parse(text) : {}) as T;
  }

  signInWithPassword = (email: string, password: string) => this.post<PasswordStep>('/api/auth/sign-in/password', { email: email.trim(), password });
  signInTotp = (code: string) => this.post<SignedIn>('/api/auth/sign-in/totp', { code: code.trim() });
  signInBackupCode = (code: string) => this.post<SignedIn>('/api/auth/sign-in/backup-code', { code: code.trim() });
  enrolTotpSetup = () => this.post<TotpSetup>('/api/auth/enroll/totp');
  enrolTotp = (code: string) => this.post<Enrolled>('/api/auth/enroll/totp/verify', { code: code.trim() });
  verifyEmail = (code: string) => this.post<SignedIn>('/api/auth/accounts/verify', { code: code.trim() });
  resendVerification = () => this.post<EmailSent>('/api/auth/accounts/resend');
  /** The reset email's link goes to the consumer site (couriers have no site of their own); the app uses the code. */
  forgotPassword = (email: string) => this.post<EmailSent>('/api/auth/password/forgot', { email: email.trim(), app: 'consumer' });
  resetPassword = (code: string, password: string, passwordConfirm: string) => this.post<{ email: string }>('/api/auth/password/reset', { code: code.trim(), password, passwordConfirm });
}
