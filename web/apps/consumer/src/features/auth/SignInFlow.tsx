import { useEffect, useState, type FormEvent } from 'react';
import { Alert, Button, Field, OptionCard, RadioGroup, StepBars, TextInput, codeValue, useLocale } from '@northline/ui';
import {
  authApi, backupCodeSchema, codeSchema, fieldErrors, firstIssue, flowError, getPasskey, isRestart, PasskeyError, PHONE_PATTERN, passkeysSupported,
  RateLimitNotice, useRateLimit, type AuthKitKey, type AuthSession, type EmailSent, PasswordField, EmailCodeStep, EnrolSecondFactor, ForgotPasswordFlow,
} from '@northline/auth-kit';
import { z } from 'zod';
import { CodeStep, type Sent } from './CodeStep';
import { useAuthT, type AuthT } from './messages';
import { SocialButtons } from './Social';

type Step = 'password' | 'phone' | 'code' | 'factor' | 'verify' | 'enroll' | 'forgot' | 'reset-done' | 'done';
const PROGRESS: Record<Step, number> = { password: 0, phone: 0, forgot: 0, 'reset-done': 0, code: 1, factor: 1, verify: 1, enroll: 1, done: 2 };
type Factor = 'totp' | 'backup_code' | 'passkey';

const phoneSchema = (t: AuthT) => z.string().trim().min(1, t('phoneRequired')).regex(PHONE_PATTERN, t('phoneFormat'));

export interface SignInFlowProps {
  /** Back from Google / Apple (or a confirmed email link): the email to sign in with. */
  resumeIdentifier?: string;
  /** `/forgot-password`. */
  forgotOnLoad?: boolean;
  onRegister: () => void;
  onFinished: (session: AuthSession) => void;
}

/**
 * Sign in (S-154): email + password → the account's own second factor when it has one; or "Sign in with a passkey"; or
 * a code to the phone (S-62: consumers need no second factor). An account without a second factor is offered one
 * (skippable); an account whose email isn't confirmed yet gets a new code.
 */
export function SignInFlow({ resumeIdentifier, forgotOnLoad, onRegister, onFinished }: SignInFlowProps) {
  const t = useAuthT();
  const { locale } = useLocale();
  const [step, setStep] = useState<Step>(forgotOnLoad ? 'forgot' : 'password');
  const [email, setEmail] = useState(resumeIdentifier ?? '');
  const [password, setPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [identifier, setIdentifier] = useState('');
  const [factors, setFactors] = useState<Factor[]>([]);
  const [factor, setFactor] = useState<Factor>('totp');
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [fieldError, setFieldError] = useState('');
  const [codeError, setCodeError] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<Sent | null>(null);
  const [emailSent, setEmailSent] = useState<EmailSent | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const limit = useRateLimit();

  const run = async (fn: () => Promise<void>, mismatch: AuthKitKey = 'signInCodeWrong') => {
    setFailure(''); setBusy(true);
    try { await fn(); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { setStep('password'); setFailure(t('restart')); return; }
      const fields = fieldErrors(err, t, mismatch);
      setFieldError(fields.password ?? fields.email ?? fields.identifier ?? '');
      setCodeError(fields.code ?? '');
      setFailure(fields.credential ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const done = (s: AuthSession) => { setSession(s); setStep(s.needsSecondFactor ? 'enroll' : 'done'); };
  const signIn = () => run(async () => {
    const r = await authApi.signInWithPassword(email, password, locale);
    setPassword(''); setCode(''); setTried(false);
    if (r.step === 'verify-email') { setEmailSent({ step: 'verify-email', email: r.email ?? email.trim(), resendAfterSeconds: r.resendAfterSeconds ?? 60 }); setStep('verify'); return; }
    if (r.step === 'enroll' && r.session) { setSession(r.session); setStep('enroll'); return; }
    const own = (r.factors ?? []).filter((f): f is Factor => f === 'totp' || f === 'backup_code' || f === 'passkey');
    setFactors(own);
    setFactor(own.find(f => f !== 'backup_code') ?? own[0] ?? 'totp');
    setStep('factor');
  });
  const startPhone = (id: string) => run(async () => {
    await authApi.startSignIn(id);
    const sentCode = await authApi.sendSignInCode('sms', locale);
    setIdentifier(id.trim());
    setSent({ ...sentCode, at: Date.now() });
    setStep('code');
  });
  const passkey = () => run(async () => {
    if (!passkeysSupported()) throw new PasskeyError('unsupported');
    const options = await authApi.passkeySignInOptions();
    done(await authApi.signInPasskey(await getPasskey(options)));
  });

  useEffect(() => { if (resumeIdentifier) document.querySelector<HTMLInputElement>('input[name="password"]')?.focus(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const phoneError = (tried ? firstIssue(phoneSchema(t), phone) : undefined) ?? fieldError;
  const schema = factor === 'backup_code' ? backupCodeSchema(t) : codeSchema(t);
  const shownCodeError = (tried ? firstIssue(schema, code) : undefined) ?? codeError;
  const verifyFactor = (e: FormEvent) => {
    e.preventDefault();
    if (factor === 'passkey') { void passkey(); return; }
    setTried(true);
    if (firstIssue(schema, code)) return;
    void (factor === 'totp'
      ? run(async () => done(await authApi.signInTotp(code)))
      : run(async () => done(await authApi.signInBackupCode(code)), 'backupWrong'));
  };
  const factorName = (f: Factor) => (f === 'totp' ? t('mfaTotp') : f === 'passkey' ? t('mfaPasskey') : t('backupCodeFactor'));

  return (
    <>
      <StepBars total={3} done={PROGRESS[step]} label={t('progress')} />
      {(step === 'password' || step === 'phone') && <p className="nl-auth-sub">{t('subSignIn')}</p>}
      {step === 'password' && (
        <form noValidate onSubmit={e => { e.preventDefault(); if (email.trim() && password) void signIn(); }}>
          <div className="nl-auth-fields">
            <Field label={t('emailLabel')}>
              <TextInput name="email" type="email" autoComplete="username webauthn" placeholder={t('emailPh')} value={email}
                onChange={e => { setEmail(e.target.value); setFieldError(''); setFailure(''); }} />
            </Field>
            <PasswordField label={t('passwordLabel')} value={password} error={fieldError} onChange={v => { setPassword(v); setFieldError(''); setFailure(''); }} />
          </div>
          <div className="nl-auth-hint"><button type="button" className="nl-auth-link" onClick={() => { setFailure(''); setStep('forgot'); }}>{t('forgotLink')}</button></div>
          {fieldError && <p className="nl-auth-hint">{t('noPasswordHint')}</p>}
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={!email.trim() || !password || busy || limit.limited} aria-busy={busy || undefined}>{t('signIn')}</Button>
          <Button type="button" variant="secondary" className="nl-auth-passkey" onClick={() => void passkey()} disabled={busy || limit.limited}>{t('passkeySignIn')}</Button>
          <Button type="button" variant="ghost" className="nl-auth-passkey" onClick={() => { setFailure(''); setFieldError(''); setStep('phone'); }}>{t('phoneCodeInstead')}</Button>
          <SocialButtons />
          <div className="nl-auth-switch">{t('newHere')} <button type="button" className="nl-auth-link" onClick={onRegister}>{t('createAccount')}</button></div>
        </form>
      )}
      {step === 'phone' && (
        <form noValidate onSubmit={e => { e.preventDefault(); setTried(true); if (!firstIssue(phoneSchema(t), phone)) void startPhone(phone); }}>
          <Field label={t('mobile')} error={phoneError || undefined}>
            <TextInput name="phone" type="tel" inputMode="tel" autoComplete="tel" placeholder={t('mobilePh')} value={phone}
              onChange={e => { setPhone(e.target.value); setFieldError(''); setFailure(''); }} />
          </Field>
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={!phone.trim() || busy || limit.limited} aria-busy={busy || undefined}>{t('continue')}</Button>
          <Button type="button" variant="ghost" className="nl-auth-passkey" onClick={() => { setTried(false); setFailure(''); setStep('password'); }}>{t('passwordInstead')}</Button>
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
            onVerify={async c => done(await authApi.signInWithCode(c))}
            onRestart={() => { setStep('phone'); setFailure(t('restart')); }}
          />
        </>
      )}
      {step === 'factor' && (
        <form noValidate onSubmit={verifyFactor}>
          <p className="nl-auth-sub">{t('factorTitle', { email: email.trim() })}</p>
          <RadioGroup className="nl-auth-options" aria-label={t('factorTitle', { email: email.trim() })}>
            {factors.map(f => (
              <OptionCard key={f} role="radio" aria-checked={factor === f} selected={factor === f} title={factorName(f)}
                onClick={() => { setFactor(f); setCode(''); setCodeError(''); setTried(false); setFailure(''); }} />
            ))}
          </RadioGroup>
          {factor !== 'passkey' && (
            <Field label={factor === 'totp' ? t('code6') : t('backupCodeFactor')} error={shownCodeError || undefined}>
              <TextInput className={factor === 'totp' ? 'nl-auth-code' : undefined} name="code" autoComplete="one-time-code" inputMode={factor === 'totp' ? 'numeric' : 'text'}
                value={code} onChange={e => { setCode(factor === 'totp' ? codeValue(e.target.value) : e.target.value); setCodeError(''); }} />
            </Field>
          )}
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={busy || limit.limited} aria-busy={busy || undefined}>{factor === 'passkey' ? t('passkeySignIn') : t('verifyCode')}</Button>
        </form>
      )}
      {step === 'verify' && emailSent && (
        <EmailCodeStep sent={emailSent} onVerified={done} onBack={() => setStep('password')} onRestart={() => { setStep('password'); setFailure(t('restart')); }} />
      )}
      {step === 'enroll' && session && (
        <EnrolSecondFactor email={session.user.email ?? email.trim()} required={false} onDone={s => { setSession(s); setStep('done'); }} onSkip={() => setStep('done')} />
      )}
      {step === 'forgot' && (
        <ForgotPasswordFlow app="consumer" initialEmail={email} onDone={e => { setEmail(e); setStep('reset-done'); }} onCancel={() => setStep('password')} />
      )}
      {step === 'reset-done' && (
        <>
          <Alert tone="info" role="status">{t('resetDone')}</Alert>
          <Button className="nl-auth-primary" onClick={() => setStep('password')}>{t('resetSignIn')}</Button>
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
