import { useEffect, useState, type FormEvent } from 'react';
import { Alert, Button, Field, Logo, OptionCard, RadioGroup, StepBars, TextInput, codeValue } from '@northline/ui';
import {
  authApi, backupCodeSchema, codeSchema, fieldErrors, firstIssue, flowError, getPasskey, isRestart, PasskeyError, passkeysSupported,
  RateLimitNotice, useAuthKitT, useRateLimit, type AuthKitKey, type AuthSession, type EmailSent, PasswordField, EmailCodeStep, EnrolSecondFactor, ForgotPasswordFlow,
} from '@northline/auth-kit';
import { bffLoginUrl, safeNext } from '../../lib/auth-server';
import { Brand } from '../shell/Brand';
import { useShellT } from '../shell/messages';
import { useSignInT, type SignInKey } from './messages';
import './auth.css';

type Step = 'id' | 'factor' | 'verify' | 'enroll' | 'forgot' | 'reset-done' | 'done';
type Factor = 'passkey' | 'totp' | 'backup_code';
const STEP_INDEX: Record<Step, number> = { id: 0, forgot: 0, 'reset-done': 0, factor: 1, verify: 1, enroll: 1, done: 2 };
const FACTORS: readonly { key: Factor; name: SignInKey; desc: SignInKey; cta: SignInKey }[] = [
  { key: 'passkey', name: 'factorPasskey', desc: 'factorPasskeyDesc', cta: 'usePasskey' },
  { key: 'totp', name: 'factorTotp', desc: 'factorTotpDesc', cta: 'verifyCode' },
  { key: 'backup_code', name: 'factorBackup', desc: 'factorBackupDesc', cta: 'verifyBackup' },
];
const IDENTIFIER_ID = 'nl-console-identifier';
/** `?error=` from the console-bff (StaffGate) or a failed OAuth callback. */
const ERRORS: Record<string, SignInKey> = { staff_only: 'err_staff_only', mfa_required: 'err_mfa_required', signin: 'err_signin' };

export interface SignInPageProps {
  next?: string;
  error?: string;
  /** Navigates to the BFF hand-off (tests replace it). */
  navigate?: (url: string) => void;
}

/**
 * The console's signed-out page (design 03, lines 31–62): the pitch on the left and the sign-in card — email + password
 * (S-154) → the account's own second factor (passkey, authenticator app, backup code; staff without one set it up here,
 * never a dead end) → done — against northline-auth's JSON API, then the
 * console-bff hand-off (`/bff/login?next=`), which admits staff with a second factor only. Staff SSO (Okta / Google)
 * isn't built: the design's SSO buttons are left out (docs/CONSOLE_PLAN.md § Sign-in).
 */
export function SignInPage({ next, error, navigate = url => window.location.assign(url) }: SignInPageProps) {
  const t = useSignInT();
  const shell = useShellT();
  const kit = useAuthKitT();
  const [step, setStep] = useState<Step>('id');
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [passwordError, setPasswordError] = useState('');
  const [factors, setFactors] = useState<Factor[]>([]);
  const [sent, setSent] = useState<EmailSent | null>(null);
  const [factor, setFactor] = useState<Factor>('passkey');
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [codeError, setCodeError] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [recover, setRecover] = useState(0);
  const limit = useRateLimit();
  const points = [[t('point1Title'), t('point1Body')], [t('point2Title'), t('point2Body')], [t('point3Title'), t('point3Body')]] as const;

  const run = async (fn: () => Promise<void>, mismatch: AuthKitKey = 'signInCodeWrong') => {
    setFailure(''); setBusy(true);
    try { await fn(); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { setStep('id'); setFailure(kit('restart')); return; }
      const fields = fieldErrors(err, kit, mismatch);
      setCodeError(fields.code ?? '');
      setPasswordError(fields.password ?? fields.email ?? '');
      setFailure(fields.credential ?? fields.identifier ?? flowError(err, kit) ?? '');
    } finally { setBusy(false); }
  };
  const done = (s: AuthSession) => { setSession(s); setStep(s.needsSecondFactor ? 'enroll' : 'done'); };
  const start = () => run(async () => {
    const r = await authApi.signInWithPassword(identifier, password);
    setCode(''); setCodeError(''); setTried(false); setPassword('');
    if (r.step === 'verify-email') { setSent({ step: 'verify-email', email: r.email ?? identifier.trim(), resendAfterSeconds: r.resendAfterSeconds ?? 60 }); setStep('verify'); return; }
    if (r.step === 'enroll' && r.session) { setSession(r.session); setStep('enroll'); return; }
    const own = (r.factors ?? []).filter((f): f is Factor => FACTORS.some(x => x.key === f));
    setFactors(own);
    setFactor(recover ? 'backup_code' : own.find(f => f !== 'backup_code') ?? own[0] ?? 'totp');
    setStep('factor');
  });
  const passkey = () => run(async () => {
    if (!passkeysSupported()) throw new PasskeyError('unsupported');
    done(await authApi.signInPasskey(await getPasskey(await authApi.passkeySignInOptions())));
  });

  // "Recover with a backup code".
  useEffect(() => {
    if (!recover) return;
    setFactor('backup_code');
    if (step === 'id') document.getElementById(IDENTIFIER_ID)?.focus();
  }, [recover]); // eslint-disable-line react-hooks/exhaustive-deps

  const schema = factor === 'backup_code' ? backupCodeSchema(kit) : codeSchema(kit);
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

  return (
    <div className="nl-auth-page">
      <div className="nl-auth-mobile-brand"><Logo size={30} product={shell('console')} /></div>
      <main className="nl-auth-main" id="main">
        <div className="nl-auth-intro">
          <div className="nl-auth-brand"><Brand label={shell('console')} /><span className="nl-auth-signedout">{t('signedOut')}</span></div>
          <div className="nl-auth-kicker">{t('kicker')}</div>
          <h1 className="nl-auth-hero">{t('heroTitle')}</h1>
          <p className="nl-auth-lede">{t('heroLede')}</p>
          <ol className="nl-auth-points">
            {points.map(([title, body], i) => (
              <li key={title} className="nl-auth-point">
                <span className="nl-auth-point-num" aria-hidden>{i + 1}</span>
                <span><strong>{title}</strong><span className="nl-auth-point-body">{body}</span></span>
              </li>
            ))}
          </ol>
          <div className="nl-auth-support">{t('support')}</div>
        </div>
        <section className="nl-auth-card" aria-labelledby="nl-auth-title">
          <h2 className="nl-auth-title" id="nl-auth-title">{t('title')}</h2>
          <StepBars total={3} done={STEP_INDEX[step]} label={t('progress')} />
          {error && step === 'id' && <Alert tone="error">{t(ERRORS[error] ?? 'err_signin')}</Alert>}
          {step === 'id' && (
            <form noValidate onSubmit={e => { e.preventDefault(); if (identifier.trim() && password) void start(); }}>
              <p className="nl-auth-p">{t('lede')}</p>
              <Field label={kit('emailLabel')}>
                <TextInput id={IDENTIFIER_ID} name="email" type="email" autoComplete="username webauthn" placeholder={t('identifierPh')} value={identifier}
                  onChange={e => { setIdentifier(e.target.value); setFailure(''); setPasswordError(''); }} />
              </Field>
              <PasswordField label={kit('passwordLabel')} value={password} error={passwordError} onChange={v => { setPassword(v); setPasswordError(''); setFailure(''); }} />
              <div className="nl-auth-hint"><button type="button" className="nl-auth-link" onClick={() => { setFailure(''); setStep('forgot'); }}>{kit('forgotLink')}</button></div>
              {failure && <Alert tone="error">{failure}</Alert>}
              <RateLimitNotice left={limit.left} />
              <Button type="submit" className="nl-auth-primary" disabled={!identifier.trim() || !password || busy || limit.limited} aria-busy={busy || undefined}>{t('continue')}</Button>
              <Button type="button" variant="secondary" className="nl-auth-primary" onClick={() => void passkey()} disabled={busy || limit.limited}>{t('usePasskey')}</Button>
            </form>
          )}
          {step === 'factor' && (
            <form noValidate onSubmit={verify}>
              <p className="nl-auth-p">{t('factorFor', { identifier: identifier.trim() })}</p>
              <RadioGroup className="nl-auth-options" aria-label={t('factorFor', { identifier: identifier.trim() })}>
                {FACTORS.filter(f => factors.includes(f.key)).map(f => (
                  <OptionCard key={f.key} role="radio" aria-checked={factor === f.key} selected={factor === f.key} title={t(f.name)} description={t(f.desc)}
                    onClick={() => { setFactor(f.key); setCode(''); setCodeError(''); setTried(false); setFailure(''); }} />
                ))}
              </RadioGroup>
              {factor !== 'passkey' && (
                <Field label={factor === 'totp' ? t('code6') : t('backupLabel')} error={shownCodeError || undefined}>
                  <TextInput className={factor === 'totp' ? 'nl-auth-code' : undefined} name="code" autoComplete="one-time-code"
                    inputMode={factor === 'totp' ? 'numeric' : 'text'} maxLength={factor === 'totp' ? undefined : 16}
                    placeholder={factor === 'totp' ? t('codePh') : t('backupPh')} value={code}
                    onChange={e => { setCode(factor === 'totp' ? codeValue(e.target.value) : e.target.value); setCodeError(''); }} />
                </Field>
              )}
              {failure && <Alert tone="error">{failure}</Alert>}
              <RateLimitNotice left={limit.left} />
              <div className="nl-auth-row">
                <Button type="submit" disabled={busy || limit.limited} aria-busy={busy || undefined}>{t(FACTORS.find(f => f.key === factor)!.cta)}</Button>
                <Button type="button" variant="ghost" onClick={() => { setStep('id'); setFailure(''); }}>{t('back')}</Button>
              </div>
            </form>
          )}
          {step === 'verify' && sent && <EmailCodeStep sent={sent} onVerified={done} onBack={() => setStep('id')} onRestart={() => { setStep('id'); setFailure(kit('restart')); }} />}
          {step === 'enroll' && session && <EnrolSecondFactor email={session.user.email ?? identifier.trim()} required onDone={s => { setSession(s); setStep('done'); }} />}
          {step === 'forgot' && <ForgotPasswordFlow app="console" initialEmail={identifier} onDone={e => { setIdentifier(e); setStep('reset-done'); }} onCancel={() => setStep('id')} />}
          {step === 'reset-done' && (
            <>
              <Alert tone="info" role="status">{kit('resetDone')}</Alert>
              <Button className="nl-auth-primary" onClick={() => setStep('id')}>{kit('resetSignIn')}</Button>
            </>
          )}
          {step === 'done' && session && (
            <>
              <Alert tone="info" title={t('signedIn')} role="status">{t('welcome', { name: session.user.firstName })}</Alert>
              <Button className="nl-auth-primary" onClick={() => navigate(bffLoginUrl(safeNext(next) ?? '/'))}>{t('continue')}</Button>
            </>
          )}
          <p className="nl-auth-footer">
            {t('footerTrouble')}
            <button type="button" className="nl-auth-link" onClick={() => setRecover(r => r + 1)}>{t('footerRecover')}</button>
            {t('footerRest')}
          </p>
        </section>
      </main>
    </div>
  );
}
