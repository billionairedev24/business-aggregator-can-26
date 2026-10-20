import { useEffect, useState, type FormEvent } from 'react';
import { Alert, Button, Field, OptionCard, RadioGroup, StepBars, TextInput, codeValue, useLocale } from '@northline/ui';
import {
  authApi, backupCodeSchema, codeSchema, firstIssue, type AuthSession, type EmailSent, fieldErrors, flowError, isRestart, RateLimitNotice, useRateLimit,
  getPasskey, PasskeyError, passkeysSupported, type AuthKitKey, PasswordField, EmailCodeStep, EnrolSecondFactor, ForgotPasswordFlow,
} from '@northline/auth-kit';
import { useAuthT, type AuthKey } from './messages';
import { SocialButtons } from './SocialButtons';

type Step = 'id' | 'factor' | 'verify' | 'enroll' | 'forgot' | 'reset-done' | 'done';
const EMAIL_ID = 'nl-sign-in-email';
const STEP_INDEX: Record<Step, number> = { id: 0, forgot: 0, 'reset-done': 0, verify: 1, factor: 1, enroll: 1, done: 2 };
export type SignInFactor = 'passkey' | 'totp' | 'backup_code';
const FACTORS: readonly { key: SignInFactor; name: AuthKey; desc: AuthKey; cta: AuthKey }[] = [
  { key: 'passkey', name: 'factorPasskey', desc: 'factorPasskeyDesc', cta: 'usePasskey' },
  { key: 'totp', name: 'factorTotp', desc: 'factorTotpDesc', cta: 'verifyCode' },
  { key: 'backup_code', name: 'factorBackup', desc: 'factorBackupDesc', cta: 'verifyBackup' },
];

export interface SignInFlowProps {
  /** Signing in to start onboarding (`?next=/onboarding…`) — changes the done copy. */
  onboarding: boolean;
  /** Returning from Google/Apple (or a confirmed email link): the email to sign in with. */
  resumeIdentifier?: string;
  /** Incremented by "Recover with a backup code". */
  recover: number;
  /** Opened on "Forgot password?" (`/forgot-password`). */
  forgotOnLoad?: boolean;
  onRegister: () => void;
  onFinished: (session: AuthSession) => void;
}

/**
 * Sign in (S-154): email + password → the account's own second factor (passkey, authenticator app, backup code; "Use
 * another method") → done. An account without a second factor goes straight to setting one up — never to a dead end —
 * and one whose email isn't confirmed yet gets a new code. "Sign in with a passkey" still works on its own.
 */
export function SignInFlow({ onboarding, resumeIdentifier, recover, forgotOnLoad, onRegister, onFinished }: SignInFlowProps) {
  const t = useAuthT();
  const { locale } = useLocale();
  const [step, setStep] = useState<Step>(forgotOnLoad ? 'forgot' : 'id');
  const [email, setEmail] = useState(resumeIdentifier ?? '');
  const [password, setPassword] = useState('');
  const [factors, setFactors] = useState<SignInFactor[]>([]);
  const [factor, setFactor] = useState<SignInFactor>('totp');
  const [others, setOthers] = useState(false);
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [codeError, setCodeError] = useState('');
  const [passwordError, setPasswordError] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<EmailSent | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const limit = useRateLimit();

  const run = async (fn: () => Promise<void>, mismatch: AuthKitKey = 'signInCodeWrong') => {
    setFailure(''); setBusy(true);
    try { await fn(); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { setStep('id'); setFailure(t('restart')); return; }
      const fields = fieldErrors(err, t, mismatch);
      setCodeError(fields.code ?? '');
      setPasswordError(fields.password ?? fields.email ?? '');
      setFailure(fields.credential ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const done = (s: AuthSession) => {
    setSession(s);
    setStep(s.needsSecondFactor ? 'enroll' : 'done');
  };
  const start = () => run(async () => {
    const r = await authApi.signInWithPassword(email, password, locale);
    setCode(''); setCodeError(''); setTried(false); setPassword('');
    if (r.step === 'verify-email') { setSent({ step: 'verify-email', email: r.email ?? email.trim(), resendAfterSeconds: r.resendAfterSeconds ?? 60 }); setStep('verify'); return; }
    if (r.step === 'enroll' && r.session) { setSession(r.session); setStep('enroll'); return; }
    const own = (r.factors ?? []).filter((f): f is SignInFactor => FACTORS.some(x => x.key === f));
    setFactors(own);
    setFactor(recover ? 'backup_code' : own.find(f => f !== 'backup_code') ?? own[0] ?? 'totp');
    setOthers(false);
    setStep('factor');
  });
  const passkey = () => run(async () => {
    if (!passkeysSupported()) throw new PasskeyError('unsupported');
    const options = await authApi.passkeySignInOptions();
    done(await authApi.signInPasskey(await getPasskey(options)));
  });

  // "Recover with a backup code": the factor step opens on it once the password is in.
  useEffect(() => {
    if (!recover) return;
    setFactor('backup_code');
    setOthers(true);
    if (step === 'id') document.getElementById(EMAIL_ID)?.focus();
  }, [recover]); // eslint-disable-line react-hooks/exhaustive-deps

  const schema = factor === 'backup_code' ? backupCodeSchema(t) : codeSchema(t);
  const shownCodeError = (tried ? firstIssue(schema, code) : undefined) ?? codeError;
  const verify = (e: FormEvent) => {
    e.preventDefault();
    if (factor === 'passkey') { void passkey(); return; }
    setTried(true);
    if (firstIssue(schema, code)) return;
    void (factor === 'totp'
      ? run(async () => done(await authApi.signInTotp(code)))
      : run(async () => done(await authApi.signInBackupCode(code)), 'backupWrong'));
  };
  const cta = FACTORS.find(f => f.key === factor)!.cta;
  const shown = others ? FACTORS.filter(f => factors.includes(f.key)) : FACTORS.filter(f => f.key === factor);

  return (
    <>
      <StepBars total={3} done={STEP_INDEX[step]} label={t('progress')} />
      {step === 'id' && (
        <form noValidate onSubmit={e => { e.preventDefault(); if (email.trim() && password) void start(); }}>
          <p className="nl-auth-p">{t('signInLede')}</p>
          <div className="nl-auth-fields">
            <Field label={t('email')}>
              <TextInput id={EMAIL_ID} name="email" type="email" autoComplete="username webauthn" placeholder={t('emailPh')} value={email}
                onChange={e => { setEmail(e.target.value); setFailure(''); setPasswordError(''); }} />
            </Field>
            <PasswordField label={t('passwordLabel')} value={password} error={passwordError}
              onChange={v => { setPassword(v); setPasswordError(''); setFailure(''); }} />
          </div>
          <div className="nl-auth-hint"><button type="button" className="nl-auth-link" onClick={() => { setFailure(''); setStep('forgot'); }}>{t('forgotLink')}</button></div>
          {passwordError && <p className="nl-auth-hint">{t('noPasswordHint')}</p>}
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={!email.trim() || !password || busy || limit.limited} aria-busy={busy || undefined}>{t('signInCta')}</Button>
          <SocialButtons onPasskey={() => void passkey()} passkeyBusy={busy || limit.limited} />
          <div className="nl-auth-switch">{t('newHere')}<button type="button" className="nl-auth-link" onClick={onRegister}>{t('createFirst')}</button>{t('newHereAfter')}</div>
        </form>
      )}
      {step === 'factor' && (
        <form noValidate onSubmit={verify}>
          <p className="nl-auth-p">{t('factorFor', { identifier: email.trim() })}</p>
          <RadioGroup className="nl-auth-options" aria-label={t('factorFor', { identifier: email.trim() })}>
            {shown.map(f => (
              <OptionCard key={f.key} role="radio" aria-checked={factor === f.key} selected={factor === f.key} title={t(f.name)} description={t(f.desc)}
                onClick={() => { setFactor(f.key); setCode(''); setCodeError(''); setTried(false); setFailure(''); }} />
            ))}
          </RadioGroup>
          {!others && factors.length > 1 && (
            <div className="nl-auth-hint"><button type="button" className="nl-auth-link" onClick={() => setOthers(true)}>{t('useAnother')}</button></div>
          )}
          {factor !== 'passkey' && (
            <Field label={factor === 'totp' ? t('code6') : t('backupLabel')} error={shownCodeError || undefined} className="nl-auth-factor-code">
              <TextInput className={factor === 'totp' ? 'nl-auth-code' : undefined} name="code" autoComplete="one-time-code"
                inputMode={factor === 'totp' ? 'numeric' : 'text'} maxLength={factor === 'totp' ? undefined : 16}
                placeholder={factor === 'totp' ? t('codePh') : t('backupPh')} value={code}
                onChange={e => { setCode(factor === 'totp' ? codeValue(e.target.value) : e.target.value); setCodeError(''); }} />
            </Field>
          )}
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <div className="nl-auth-row">
            <Button type="submit" disabled={busy || limit.limited} aria-busy={busy || undefined}>{t(cta)}</Button>
            <Button type="button" variant="ghost" onClick={() => { setStep('id'); setFailure(''); }}>{t('back')}</Button>
          </div>
        </form>
      )}
      {step === 'verify' && sent && (
        <EmailCodeStep sent={sent} onVerified={done} onBack={() => setStep('id')} onRestart={() => { setStep('id'); setFailure(t('restart')); }} />
      )}
      {step === 'enroll' && session && (
        <EnrolSecondFactor email={session.user.email ?? email.trim()} required onDone={s => { setSession(s); setStep('done'); }} />
      )}
      {step === 'forgot' && (
        <ForgotPasswordFlow app="studio" initialEmail={email} onDone={e => { setEmail(e); setStep('reset-done'); }} onCancel={() => setStep('id')} />
      )}
      {step === 'reset-done' && (
        <>
          <Alert tone="info" role="status">{t('resetDone')}</Alert>
          <Button className="nl-auth-primary" onClick={() => setStep('id')}>{t('resetSignIn')}</Button>
        </>
      )}
      {step === 'done' && session && (
        <>
          <Alert tone="info" title={t('signedIn')} role="status">
            {t(onboarding ? 'welcomeOnboarding' : 'welcome', { name: session.user.firstName })}
          </Alert>
          <Button className="nl-auth-primary" onClick={() => onFinished(session)}>{t(onboarding ? 'continueToOnboarding' : 'continue')}</Button>
        </>
      )}
    </>
  );
}
