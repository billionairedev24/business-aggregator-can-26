import { useEffect, useState, type FormEvent } from 'react';
import { Alert, Button, Field, OptionCard, RadioGroup, StepBars, TextInput, codeValue } from '@northline/ui';
import {
  authApi, backupCodeSchema, codeSchema, fieldErrors, firstIssue, flowError, getPasskey, isRestart, PasskeyError, passkeysSupported,
  RateLimitNotice, useAuthKitT, useRateLimit, type AuthKitKey, type AuthSession,
} from '@northline/auth-kit';
import { bffLoginUrl, safeNext } from '../../lib/auth-server';
import { Brand } from '../shell/Brand';
import { useShellT } from '../shell/messages';
import { useSignInT, type SignInKey } from './messages';
import './auth.css';

type Step = 'id' | 'factor' | 'done';
type Factor = 'passkey' | 'totp' | 'backup_code';
const STEP_INDEX: Record<Step, number> = { id: 0, factor: 1, done: 2 };
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
 * The console's signed-out page (design 03, lines 31–62): the pitch on the left and the sign-in card — email or mobile
 * → second factor (passkey, authenticator app, backup code) → done — against northline-auth's JSON API, then the
 * console-bff hand-off (`/bff/login?next=`), which admits staff with a second factor only. Staff SSO (Okta / Google)
 * isn't built: the design's SSO buttons are left out (docs/CONSOLE_PLAN.md § Sign-in).
 */
export function SignInPage({ next, error, navigate = url => window.location.assign(url) }: SignInPageProps) {
  const t = useSignInT();
  const shell = useShellT();
  const kit = useAuthKitT();
  const [step, setStep] = useState<Step>('id');
  const [identifier, setIdentifier] = useState('');
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
      setFailure(fields.credential ?? fields.identifier ?? flowError(err, kit) ?? '');
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
    done(await authApi.signInPasskey(await getPasskey(await authApi.passkeySignInOptions())));
  });

  // "Recover with a backup code".
  useEffect(() => {
    if (!recover) return;
    setFactor('backup_code');
    if (step === 'id') { if (identifier.trim()) void start(identifier); else document.getElementById(IDENTIFIER_ID)?.focus(); }
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
      <nav className="nav nl-auth-nav" aria-label="Northline Console">
        <Brand label={shell('console')} />
        <span className="nl-auth-signedout">{t('signedOut')}</span>
      </nav>
      <main className="nl-auth-main">
        <div className="nl-auth-intro">
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
            <form noValidate onSubmit={e => { e.preventDefault(); if (identifier.trim()) void start(identifier); }}>
              <p className="nl-auth-p">{t('lede')}</p>
              <Field label={t('identifier')}>
                <TextInput id={IDENTIFIER_ID} name="identifier" autoComplete="username webauthn" placeholder={t('identifierPh')} value={identifier}
                  onChange={e => { setIdentifier(e.target.value); setFailure(''); }} />
              </Field>
              {failure && <Alert tone="error">{failure}</Alert>}
              <RateLimitNotice left={limit.left} />
              <Button type="submit" className="nl-auth-primary" disabled={!identifier.trim() || busy || limit.limited} aria-busy={busy || undefined}>{t('continue')}</Button>
            </form>
          )}
          {step === 'factor' && (
            <form noValidate onSubmit={verify}>
              <p className="nl-auth-p">{t('factorFor', { identifier: identifier.trim() })}</p>
              <RadioGroup className="nl-auth-options" aria-label={t('factorFor', { identifier: identifier.trim() })}>
                {FACTORS.map(f => (
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
