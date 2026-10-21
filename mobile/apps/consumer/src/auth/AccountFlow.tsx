import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';

import type { PendingSignIn } from '@northline/mobile-kit';

import type { AuthSessionAnswer, MarketingConsent, TotpSetup } from '../api/auth';
import { services } from '../services';
import { useAuth } from './AuthProvider';
import { HandoffError } from './errors';
import { splitName, type SignUpForm } from './rules';

export { HandoffError };

/**
 * Journey A's state between its screens, on S-154's email + password accounts (S-159; first S-98's phone flow). It
 * lives in memory only: if the app is closed half-way, the person starts again (the auth server forgets the half-done
 * flow within minutes anyway).
 *
 * create account: sign-up → `accounts` (a code by email) → verify-email → second factor offered (authenticator app,
 *                 or "Not now": customers may skip it, S-154) → done
 * sign in:        sign-in → `sign-in/password` → the account's factor (authenticator or backup code) | enrolment offered
 *                 (an account without one) | its email still to confirm → done
 * by phone:       sign-in-phone → `sign-in` + `sign-in/code` → verify → done (S-62's code to the account's mobile)
 * Every path ends in {@link finish}: the hand-off to DPoP tokens (src/auth/handoff in the kit, S-29's PKCE + DPoP),
 * then AuthProvider.signedIn().
 */
export type FlowMode = 'register' | 'signIn' | 'phone';

interface FlowState {
  mode: FlowMode;
  /** The address the email code went to. */
  email: string;
  /** Epoch ms when "Resend" opens. */
  resendAt: number;
  channel: 'sms' | 'voice';
  draft: SignUpForm;
  /** Sign in by phone: the email or mobile typed. */
  identifier: string;
  /** Sign in: the account's own factors (`totp`, `backup_code`, `passkey`). */
  factors: string[];
  totp: TotpSetup | null;
  /** Shown once after the authenticator is set up. */
  backupCodes: string[] | null;
}

interface FlowValue extends FlowState {
  setDraft(d: SignUpForm): void;
  setIdentifier(v: string): void;
  /** `marketing`: S-157, the sign-up's ticked marketing boxes (CASL express consent). */
  register(form: SignUpForm, marketing?: MarketingConsent[]): Promise<void>;
  resendEmail(): Promise<void>;
  /** The emailed code: an account without a second factor is offered one, else signed in. */
  verifyEmail(code: string): Promise<'enrol' | 'done'>;
  signIn(email: string, password: string): Promise<'factor' | 'enrol' | 'verify-email' | 'done'>;
  signInFactor(kind: 'totp' | 'backup_code', code: string): Promise<void>;
  startTotp(): Promise<TotpSetup>;
  /** The authenticator's code: enrolled; the backup codes are kept for the screen, then {@link finishEnrolment}. */
  finishTotp(code: string): Promise<string[]>;
  /** After the backup codes were seen, or "Not now". */
  finishEnrolment(): Promise<void>;
  signInByPhone(identifier: string): Promise<void>;
  /** The texted code (sign-in by phone). */
  verify(code: string): Promise<'done'>;
  resend(channel: 'sms' | 'voice'): Promise<void>;
  forgotPassword(email: string): Promise<void>;
  resetPassword(code: string, password: string, confirm: string): Promise<void>;
  /** After a lost answer: ask the auth server for the code again (the session is signed in). */
  retryFinish(): Promise<void>;
  reset(): void;
}

const EMPTY: SignUpForm = { fullName: '', email: '', password: '', passwordConfirm: '', phone: '', terms: false };
const FlowContext = createContext<FlowValue | null>(null);

export function AccountFlowProvider({ children, now = Date.now }: { children: ReactNode; now?: () => number }) {
  const { signedIn } = useAuth();
  const [state, setState] = useState<FlowState>({ mode: 'register', email: '', resendAt: 0, channel: 'sms', draft: EMPTY, identifier: '', factors: [], totp: null, backupCodes: null });
  const pending = useRef<PendingSignIn | null>(null);
  const continueTo = useRef<string | null>(null);

  const begin = useCallback(async () => {
    pending.current = await services().appSignIn.begin();
    continueTo.current = null;
  }, []);

  /** Keeps the answer's `continueTo` (the authorization request northline-auth hands back once). */
  const keep = (answer: AuthSessionAnswer | null | undefined) => {
    if (answer?.continueTo) continueTo.current = answer.continueTo;
  };

  const finish = useCallback(
    async (answer: AuthSessionAnswer | null) => {
      if (!pending.current) throw new Error('no sign-in in progress');
      keep(answer);
      try {
        await services().appSignIn.complete(continueTo.current, pending.current);
      } catch (e) {
        throw new HandoffError(e);
      }
      pending.current = null;
      signedIn();
    },
    [signedIn],
  );

  const sent = useCallback((email: string, resendAfterSeconds: number) => {
    setState((s) => ({ ...s, email, resendAt: now() + resendAfterSeconds * 1000 }));
  }, [now]);

  const register = useCallback(
    async (form: SignUpForm, marketing: MarketingConsent[] = []) => {
      await begin();
      const { firstName, lastName } = splitName(form.fullName);
      const answer = await services().authApi.createAccount({ firstName, lastName, email: form.email, password: form.password, passwordConfirm: form.passwordConfirm, phone: form.phone, terms: form.terms, marketing });
      setState((s) => ({ ...s, mode: 'register', draft: form, totp: null, backupCodes: null }));
      sent(answer.email, answer.resendAfterSeconds);
    },
    [begin, sent],
  );

  const resendEmail = useCallback(async () => {
    const answer = await services().authApi.resendVerification();
    sent(answer.email, answer.resendAfterSeconds);
  }, [sent]);

  const verifyEmail = useCallback(
    async (code: string): Promise<'enrol' | 'done'> => {
      const answer = await services().authApi.verifyEmail(code);
      keep(answer);
      if (answer.needsSecondFactor) return 'enrol';
      await finish(answer);
      return 'done';
    },
    [finish],
  );

  const signIn = useCallback(
    async (email: string, password: string) => {
      await begin();
      const step = await services().authApi.signInWithPassword(email, password);
      setState((s) => ({ ...s, mode: 'signIn', identifier: email, factors: step.factors ?? [], totp: null, backupCodes: null }));
      if (step.step === 'factor') return 'factor' as const;
      if (step.step === 'verify-email') {
        sent(step.email ?? email, step.resendAfterSeconds ?? 0);
        return 'verify-email' as const;
      }
      // signed in with the password, no second factor yet
      keep(step.session);
      return 'enrol' as const;
    },
    [begin, sent],
  );

  const signInFactor = useCallback(
    async (kind: 'totp' | 'backup_code', code: string) => {
      const api = services().authApi;
      await finish(kind === 'totp' ? await api.signInTotp(code) : await api.signInBackupCode(code));
    },
    [finish],
  );

  const startTotp = useCallback(async () => {
    const totp = state.totp ?? (await services().authApi.enrolTotpSetup());
    setState((s) => ({ ...s, totp }));
    return totp;
  }, [state.totp]);

  const finishTotp = useCallback(async (code: string) => {
    const enrolled = await services().authApi.enrolTotp(code);
    keep(enrolled.session);
    setState((s) => ({ ...s, backupCodes: enrolled.backupCodes }));
    return enrolled.backupCodes;
  }, []);

  const finishEnrolment = useCallback(async () => finish(null), [finish]);

  const signInByPhone = useCallback(
    async (identifier: string) => {
      await begin();
      const api = services().authApi;
      await api.startSignIn(identifier);
      const code = await api.sendSignInCode('sms');
      setState((s) => ({ ...s, mode: 'phone', identifier, channel: code.channel, resendAt: now() + code.resendAfterSeconds * 1000 }));
    },
    [begin, now],
  );

  const verify = useCallback(
    async (code: string): Promise<'done'> => {
      await finish(await services().authApi.signInWithCode(code));
      return 'done';
    },
    [finish],
  );

  const resend = useCallback(
    async (channel: 'sms' | 'voice') => {
      const code = await services().authApi.sendSignInCode(channel);
      setState((s) => ({ ...s, channel: code.channel, resendAt: now() + code.resendAfterSeconds * 1000 }));
    },
    [now],
  );

  const forgotPassword = useCallback(
    async (email: string) => {
      const answer = await services().authApi.forgotPassword(email);
      setState((s) => ({ ...s, identifier: email }));
      sent(answer.email, answer.resendAfterSeconds);
    },
    [sent],
  );

  const resetPassword = useCallback(async (code: string, password: string, confirm: string) => {
    await services().authApi.resetPassword(code, password, confirm);
  }, []);

  const retryFinish = useCallback(async () => finish(null), [finish]);

  const reset = useCallback(() => {
    pending.current = null;
    continueTo.current = null;
    setState((s) => ({ ...s, email: '', resendAt: 0, totp: null, backupCodes: null, factors: [] }));
  }, []);

  const value = useMemo<FlowValue>(
    () => ({
      ...state,
      setDraft: (draft) => setState((s) => ({ ...s, draft })),
      setIdentifier: (identifier) => setState((s) => ({ ...s, identifier })),
      register,
      resendEmail,
      verifyEmail,
      signIn,
      signInFactor,
      startTotp,
      finishTotp,
      finishEnrolment,
      signInByPhone,
      verify,
      resend,
      forgotPassword,
      resetPassword,
      retryFinish,
      reset,
    }),
    [state, register, resendEmail, verifyEmail, signIn, signInFactor, startTotp, finishTotp, finishEnrolment, signInByPhone, verify, resend, forgotPassword, resetPassword, retryFinish, reset],
  );
  return <FlowContext.Provider value={value}>{children}</FlowContext.Provider>;
}

export function useAccountFlow(): FlowValue {
  const ctx = useContext(FlowContext);
  if (!ctx) throw new Error('useAccountFlow outside AccountFlowProvider');
  return ctx;
}
