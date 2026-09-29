import { useEffect, useState, type FormEvent } from 'react';
import { Alert, Button, Field, OptionCard, StepBars, TextInput } from '@northline/ui';
import { authApi, backupCodeSchema, codeSchema, firstIssue, type AuthSession } from './api';
import { fieldErrors, flowError, isRestart } from './errors';
import { useAuthT, type AuthKey } from './messages';
import { RateLimitNotice, useRateLimit } from './rateLimit';
import { SocialButtons } from './SocialButtons';
import { getPasskey, PasskeyError, passkeysSupported } from './webauthn';

type Step = 'id' | 'factor' | 'done';
const IDENTIFIER_ID = 'nl-sign-in-identifier';
const STEP_INDEX: Record<Step, number> = { id: 0, factor: 1, done: 2 };
export type SignInFactor = 'passkey' | 'totp' | 'backup_code';
const FACTORS: readonly { key: SignInFactor; name: AuthKey; desc: AuthKey; cta: AuthKey }[] = [
  { key: 'passkey', name: 'factorPasskey', desc: 'factorPasskeyDesc', cta: 'usePasskey' },
  { key: 'totp', name: 'factorTotp', desc: 'factorTotpDesc', cta: 'verifyCode' },
  { key: 'backup_code', name: 'factorBackup', desc: 'factorBackupDesc', cta: 'verifyBackup' },
];

export interface SignInFlowProps {
  /** Signing in to start onboarding (`?next=/onboarding…`) — changes the done copy. */
  onboarding: boolean;
  /** Returning from Google/Apple: continue at the factor step for this email. */
  resumeIdentifier?: string;
  /** Incremented by "Recover with a backup code". */
  recover: number;
  onRegister: () => void;
  onFinished: (session: AuthSession) => void;
}

/** Sign in: email or mobile → second factor (passkey, authenticator app, backup code) → done. */
export function SignInFlow({ onboarding, resumeIdentifier, recover, onRegister, onFinished }: SignInFlowProps) {
  const t = useAuthT();
  const [step, setStep] = useState<Step>('id');
  const [identifier, setIdentifier] = useState(resumeIdentifier ?? '');
  const [factor, setFactor] = useState<SignInFactor>('passkey');
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [codeError, setCodeError] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [session, setSession] = useState<AuthSession | null>(null);
  const limit = useRateLimit();

  const run = async (fn: () => Promise<void>, mismatch: AuthKey = 'signInCodeWrong') => {
    setFailure(''); setBusy(true);
    try { await fn(); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { setStep('id'); setFailure(t('restart')); return; }
      const fields = fieldErrors(err, t, mismatch);
      setCodeError(fields.code ?? '');
      setFailure(fields.credential ?? fields.identifier ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const start = (id: string) => run(async () => {
    await authApi.startSignIn(id);
    setCode(''); setCodeError(''); setTried(false);
    setStep('factor');
  });
  const done = (s: AuthSession) => { setSession(s); setStep('done'); };
  const passkey = () => run(async () => {
    if (!passkeysSupported()) throw new PasskeyError('unsupported');
    const options = await authApi.passkeySignInOptions();
    done(await authApi.signInPasskey(await getPasskey(options)));
  });

  // Back from Google/Apple with a known email: straight to the factor step.
  useEffect(() => { if (resumeIdentifier) void start(resumeIdentifier); /* once */ }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // "Recover with a backup code".
  useEffect(() => {
    if (!recover) return;
    setFactor('backup_code');
    if (step === 'id') { if (identifier.trim()) void start(identifier); else document.getElementById(IDENTIFIER_ID)?.focus(); }
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

  return (
    <>
      <StepBars total={3} done={STEP_INDEX[step]} label={t('progress')} />
      {step === 'id' && (
        <form noValidate onSubmit={e => { e.preventDefault(); if (identifier.trim()) void start(identifier); }}>
          <p className="nl-auth-p">{t('signInLede')}</p>
          <Field label={t('identifier')}>
            <TextInput id={IDENTIFIER_ID} name="identifier" autoComplete="username webauthn" placeholder={t('emailPh')} value={identifier}
              onChange={e => { setIdentifier(e.target.value); setFailure(''); }} />
          </Field>
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <Button type="submit" className="nl-auth-primary" disabled={!identifier.trim() || busy || limit.limited} aria-busy={busy || undefined}>{t('continue')}</Button>
          <SocialButtons onPasskey={() => void passkey()} passkeyBusy={busy || limit.limited} />
          <div className="nl-auth-switch">{t('newHere')}<button type="button" className="nl-auth-link" onClick={onRegister}>{t('createFirst')}</button>{t('newHereAfter')}</div>
        </form>
      )}
      {step === 'factor' && (
        <form noValidate onSubmit={verify}>
          <p className="nl-auth-p">{t('factorFor', { identifier: identifier.trim() })}</p>
          <div className="nl-auth-options" role="radiogroup" aria-label={t('factorFor', { identifier: identifier.trim() })}>
            {FACTORS.map(f => (
              <OptionCard key={f.key} role="radio" aria-checked={factor === f.key} selected={factor === f.key} title={t(f.name)} description={t(f.desc)}
                onClick={() => { setFactor(f.key); setCode(''); setCodeError(''); setTried(false); setFailure(''); }} />
            ))}
          </div>
          {factor !== 'passkey' && (
            <Field label={factor === 'totp' ? t('code6') : t('backupLabel')} error={shownCodeError || undefined} className="nl-auth-factor-code">
              <TextInput className={factor === 'totp' ? 'nl-auth-code' : undefined} name="code" autoComplete="one-time-code"
                inputMode={factor === 'totp' ? 'numeric' : 'text'} maxLength={factor === 'totp' ? 6 : 16}
                placeholder={factor === 'totp' ? t('codePh') : t('backupPh')} value={code}
                onChange={e => { setCode(e.target.value); setCodeError(''); }} />
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
