import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';

import type { PendingSignIn } from '@northline/mobile-kit';

import type { AuthSessionAnswer, TotpSetup } from '../api/auth';
import { services } from '../services';
import { useAuth } from './AuthProvider';
import { HandoffError } from './errors';
import { splitName, type SignUpForm } from './rules';

export { HandoffError };

/**
 * Journey A's state between its screens (S-98): which flow (create account or sign in), the authorization request
 * kept for the hand-off, where the code went and when it may be sent again, what was typed (so Back keeps it). It
 * lives in memory only: if the app is closed half-way, the person starts again (the auth server forgets the half-done
 * flow within minutes anyway).
 *
 * create account: sign-up → `register` (code by SMS) → verify → second factor (authenticator app or SMS only)
 * sign in:        sign-in → `sign-in` + `sign-in/code` → verify → done
 * Both end in {@link finish}: the hand-off to DPoP tokens (src/auth/handoff in the kit), then AuthProvider.signedIn().
 */
export type FlowMode = 'register' | 'signIn';

interface FlowState {
  mode: FlowMode;
  /** The number the code went to, as the server formats it (registration), or null (sign-in: the account's). */
  phone: string | null;
  /** Epoch ms when "Resend" opens. */
  resendAt: number;
  channel: 'sms' | 'voice';
  draft: SignUpForm;
  identifier: string;
  totp: TotpSetup | null;
}

interface FlowValue extends FlowState {
  setDraft(d: SignUpForm): void;
  setIdentifier(v: string): void;
  register(form: SignUpForm): Promise<void>;
  signIn(identifier: string): Promise<void>;
  /** The code from the SMS / call: registration moves on to the second factor, sign-in finishes. */
  verify(code: string): Promise<'mfa' | 'done'>;
  resend(channel: 'sms' | 'voice'): Promise<void>;
  startTotp(): Promise<TotpSetup>;
  finishWithTotp(code: string): Promise<void>;
  finishWithSms(): Promise<void>;
  /** After a lost answer: ask the auth server for the code again (the session is signed in). */
  retryFinish(): Promise<void>;
  reset(): void;
}

const EMPTY: SignUpForm = { phone: '', fullName: '', email: '', terms: false };
const FlowContext = createContext<FlowValue | null>(null);

export function AccountFlowProvider({ children, now = Date.now }: { children: ReactNode; now?: () => number }) {
  const { signedIn } = useAuth();
  const [state, setState] = useState<FlowState>({ mode: 'register', phone: null, resendAt: 0, channel: 'sms', draft: EMPTY, identifier: '', totp: null });
  const pending = useRef<PendingSignIn | null>(null);
  const continueTo = useRef<string | null>(null);

  const begin = useCallback(async () => {
    pending.current = await services().appSignIn.begin();
    continueTo.current = null;
  }, []);

  const finish = useCallback(
    async (answer: AuthSessionAnswer | null) => {
      if (!pending.current) throw new Error('no sign-in in progress');
      if (answer) continueTo.current = answer.continueTo ?? null;
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

  const register = useCallback(
    async (form: SignUpForm) => {
      await begin();
      const { firstName, lastName } = splitName(form.fullName);
      const step = await services().authApi.register({ firstName, lastName, phone: form.phone, email: form.email, terms: form.terms });
      setState((s) => ({ ...s, mode: 'register', draft: form, phone: step.phone, channel: step.channel, resendAt: now() + step.resendAfterSeconds * 1000, totp: null }));
    },
    [begin, now],
  );

  const signIn = useCallback(
    async (identifier: string) => {
      await begin();
      const api = services().authApi;
      await api.startSignIn(identifier);
      const sent = await api.sendSignInCode('sms');
      setState((s) => ({ ...s, mode: 'signIn', identifier, phone: null, channel: sent.channel, resendAt: now() + sent.resendAfterSeconds * 1000 }));
    },
    [begin, now],
  );

  const verify = useCallback(
    async (code: string): Promise<'mfa' | 'done'> => {
      const api = services().authApi;
      if (state.mode === 'register') {
        await api.verifyPhone(code);
        return 'mfa';
      }
      await finish(await api.signInWithCode(code));
      return 'done';
    },
    [state.mode, finish],
  );

  const resend = useCallback(
    async (channel: 'sms' | 'voice') => {
      const api = services().authApi;
      const sent = state.mode === 'register' ? await api.resend(channel) : await api.sendSignInCode(channel);
      setState((s) => ({ ...s, channel: sent.channel, resendAt: now() + sent.resendAfterSeconds * 1000 }));
    },
    [state.mode, now],
  );

  const startTotp = useCallback(async () => {
    const totp = state.totp ?? (await services().authApi.totpSetup());
    setState((s) => ({ ...s, totp }));
    return totp;
  }, [state.totp]);

  const finishWithTotp = useCallback(async (code: string) => finish(await services().authApi.registerTotp(code)), [finish]);
  const finishWithSms = useCallback(async () => finish(await services().authApi.completeWithoutSecondFactor()), [finish]);
  const retryFinish = useCallback(async () => finish(null), [finish]);

  const reset = useCallback(() => {
    pending.current = null;
    continueTo.current = null;
    setState((s) => ({ ...s, phone: null, resendAt: 0, totp: null }));
  }, []);

  const value = useMemo<FlowValue>(
    () => ({
      ...state,
      setDraft: (draft) => setState((s) => ({ ...s, draft })),
      setIdentifier: (identifier) => setState((s) => ({ ...s, identifier })),
      register,
      signIn,
      verify,
      resend,
      startTotp,
      finishWithTotp,
      finishWithSms,
      retryFinish,
      reset,
    }),
    [state, register, signIn, verify, resend, startTotp, finishWithTotp, finishWithSms, retryFinish, reset],
  );
  return <FlowContext.Provider value={value}>{children}</FlowContext.Provider>;
}

export function useAccountFlow(): FlowValue {
  const ctx = useContext(FlowContext);
  if (!ctx) throw new Error('useAccountFlow outside AccountFlowProvider');
  return ctx;
}
