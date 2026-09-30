import { useEffect, useState, type FormEvent } from 'react';
import { Alert, Button, Field, StepBars, TextInput, useLocale } from '@northline/ui';
import { authApi, fieldErrors, firstIssue, flowError, getPasskey, isRestart, PasskeyError, PHONE_PATTERN, passkeysSupported, RateLimitNotice, useRateLimit, type AuthSession } from '@northline/auth-kit';
import { z } from 'zod';
import { CodeStep, type Sent } from './CodeStep';
import { useAuthT, type AuthT } from './messages';
import { SocialButtons } from './Social';

type Step = 'phone' | 'code' | 'done';
const PROGRESS: Record<Step, number> = { phone: 0, code: 1, done: 2 };

const phoneSchema = (t: AuthT) => z.string().trim().min(1, t('phoneRequired')).regex(PHONE_PATTERN, t('phoneFormat'));

export interface SignInFlowProps {
  /** Back from Google / Apple with a known email: straight to the code. */
  resumeIdentifier?: string;
  onRegister: () => void;
  onFinished: (session: AuthSession) => void;
}

/**
 * Sign in (design 06 `auth`, authMode signin): mobile → 6-digit code → signed in; or "Sign in with a passkey" → signed
 * in. Consumers need no second factor (S-62): the code alone signs in; a passkey gives `acr=mfa`.
 */
export function SignInFlow({ resumeIdentifier, onRegister, onFinished }: SignInFlowProps) {
  const t = useAuthT();
  const { locale } = useLocale();
  const [step, setStep] = useState<Step>('phone');
  const [phone, setPhone] = useState('');
  const [identifier, setIdentifier] = useState('');
  const [tried, setTried] = useState(false);
  const [fieldError, setFieldError] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<Sent | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const limit = useRateLimit();

  const run = async (fn: () => Promise<void>) => {
    setFailure(''); setBusy(true);
    try { await fn(); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { setStep('phone'); setFailure(t('restart')); return; }
      const fields = fieldErrors(err, t, 'signInCodeWrong');
      setFieldError(fields.identifier ?? '');
      setFailure(fields.credential ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const start = (id: string) => run(async () => {
    await authApi.startSignIn(id);
    const code = await authApi.sendSignInCode('sms', locale);
    setIdentifier(id.trim());
    setSent({ ...code, at: Date.now() });
    setStep('code');
  });
  const done = (s: AuthSession) => { setSession(s); setStep('done'); };
  const passkey = () => run(async () => {
    if (!passkeysSupported()) throw new PasskeyError('unsupported');
    const options = await authApi.passkeySignInOptions();
    done(await authApi.signInPasskey(await getPasskey(options)));
  });

  // Back from Google / Apple: the account is known by its email — the code goes to its mobile.
  useEffect(() => { if (resumeIdentifier) void start(resumeIdentifier); /* once */ }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const phoneError = (tried ? firstIssue(phoneSchema(t), phone) : undefined) ?? fieldError;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(phoneSchema(t), phone)) return;
    void start(phone);
  };

  return (
    <>
      <StepBars total={3} done={PROGRESS[step]} label={t('progress')} />
      <p className="nl-auth-sub">{t('subSignIn')}</p>
      {step === 'phone' && (
        <form noValidate onSubmit={submit}>
          <Field label={t('mobile')} error={phoneError || undefined}>
            <TextInput name="phone" type="tel" inputMode="tel" autoComplete="tel webauthn" placeholder={t('mobilePh')} value={phone}
              onChange={e => { setPhone(e.target.value); setFieldError(''); setFailure(''); }} />
          </Field>
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={!phone.trim() || busy || limit.limited} aria-busy={busy || undefined}>{t('continue')}</Button>
          <Button type="button" variant="secondary" className="nl-auth-passkey" onClick={() => void passkey()} disabled={busy || limit.limited}>{t('passkeySignIn')}</Button>
          <SocialButtons />
          <div className="nl-auth-switch">{t('newHere')} <button type="button" className="nl-auth-link" onClick={onRegister}>{t('createAccount')}</button></div>
        </form>
      )}
      {step === 'code' && sent && (
        <>
          {failure && <Alert tone="error">{failure}</Alert>}
          <CodeStep
            label={identifier.includes('@') ? t('codeToAccount') : t('codeTo', { phone: identifier })}
            sent={sent}
            mismatch="signInCodeWrong"
            onResend={channel => authApi.sendSignInCode(channel, locale)}
            onResent={setSent}
            onVerify={async code => done(await authApi.signInWithCode(code))}
            onRestart={() => { setStep('phone'); setFailure(t('restart')); }}
          />
        </>
      )}
      {step === 'done' && session && (
        <>
          <div className="nl-auth-done" role="status"><strong>{t('signedIn')}</strong> {t('signedInBody')}</div>
          <Button className="nl-auth-primary" onClick={() => onFinished(session)}>{t('continue')}</Button>
        </>
      )}
    </>
  );
}
