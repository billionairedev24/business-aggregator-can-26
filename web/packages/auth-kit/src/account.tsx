import { useEffect, useRef, useState, type FormEvent, type InputHTMLAttributes, type ReactNode } from 'react';
import { Alert, Button, Checkbox, Field, OptionCard, RadioGroup, TextInput, codeValue, useLocale } from '@northline/ui';
import { authApi, codeSchema, firstIssue, passwordSchema, type AccountApp, type AuthSession, type EmailSent, type Enrolled, type TotpSetup } from './api';
import { fieldErrors, flowError, isRestart, retryAfter } from './errors';
import { useAuthKitT } from './messages';
import { mmss, useCountdown } from './countdown';
import { RateLimitNotice, useRateLimit } from './RateLimit';
import { createPasskey, PasskeyError, passkeysSupported } from './webauthn';
import './account.css';

/**
 * S-154: the pieces of the email + password account flow every app shows the same way (Studio, consumer site,
 * console): the password field, the email code, enrolling the second factor with its backup codes, forgot password,
 * the email's links. Each app keeps its own page and copy around them.
 */

/** A password input with a show / hide toggle (never autocompleted into a visible field). */
export function PasswordField({ label, error, hint, value, onChange, autoComplete = 'current-password', name = 'password', ...rest }: {
  label: ReactNode; error?: string; hint?: ReactNode; value: string; onChange: (v: string) => void;
} & Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'type'>) {
  const t = useAuthKitT();
  const [shown, setShown] = useState(false);
  return (
    <div className="nl-acct-password">
      <Field label={label} error={error || undefined} hint={hint}>
        <TextInput {...rest} name={name} type={shown ? 'text' : 'password'} autoComplete={autoComplete} spellCheck={false} autoCapitalize="none"
          value={value} onChange={e => onChange(e.target.value)} />
      </Field>
      <button type="button" className="nl-acct-toggle" aria-pressed={shown} onClick={() => setShown(s => !s)}>{shown ? t('hidePassword') : t('showPassword')}</button>
    </div>
  );
}

/** Is this the developer's machine (Mailpit hint)? */
const local = () => typeof window !== 'undefined' && /^(localhost|127\.0\.0\.1)$/.test(window.location.hostname);

/**
 * "Check your email": the 6-digit code from the verification email (or its button, which opens `/verify-email`).
 * Resend after the cool-down. On success the browser is signed in with the password (`session.needsSecondFactor`).
 */
export function EmailCodeStep({ sent, onVerified, onBack, onRestart }: {
  sent: EmailSent; onVerified: (s: AuthSession) => void; onBack?: () => void; onRestart: () => void;
}) {
  const t = useAuthKitT();
  const { locale } = useLocale();
  const [info, setInfo] = useState({ ...sent, at: Date.now() });
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState('');
  const [notice, setNotice] = useState<{ tone: 'info' | 'error'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const limit = useRateLimit();
  const left = useCountdown(info.at + info.resendAfterSeconds * 1000);
  const error = (tried ? firstIssue(codeSchema(t), code) : undefined) ?? server;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(codeSchema(t), code)) return;
    setBusy(true); setNotice(null);
    try { onVerified(await authApi.verifyEmail(code)); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) return onRestart();
      setServer(fieldErrors(err, t).code ?? '');
      const f = flowError(err, t);
      if (f) setNotice({ tone: 'error', text: f });
    } finally { setBusy(false); }
  };
  const resend = async () => {
    setNotice(null);
    try {
      const next = await authApi.resendVerification(locale);
      setInfo({ ...next, at: Date.now() }); setServer('');
      setNotice({ tone: 'info', text: t('emailResent') });
    } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) return onRestart();
      const wait = retryAfter(err);
      setNotice({ tone: 'error', text: wait ? t('resendEmailIn', { time: mmss(wait) }) : flowError(err, t) ?? '' });
    }
  };
  return (
    <form noValidate onSubmit={e => void submit(e)} className="nl-acct-step">
      <h3 className="nl-acct-h">{t('verifyTitle')}</h3>
      <p className="nl-acct-p">{t('verifyBody', { email: info.email })}</p>
      <Field label={t('verifyCodeLabel')} error={error || undefined}>
        <TextInput className="nl-acct-code" name="code" inputMode="numeric" autoComplete="one-time-code" placeholder="123456" value={code}
          onChange={e => { setCode(codeValue(e.target.value)); setServer(''); }} />
      </Field>
      <div className="nl-acct-hint">
        {left > 0 ? <span aria-live="polite">{t('resendEmailIn', { time: mmss(left) })}</span>
          : <button type="button" className="nl-acct-link" onClick={() => void resend()} disabled={limit.limited}>{t('resendEmail')}</button>}
      </div>
      {local() && <p className="nl-acct-dev">{t('devMailpit')}</p>}
      {notice && !limit.limited && <Alert tone={notice.tone} role={notice.tone === 'error' ? 'alert' : 'status'}>{notice.text}</Alert>}
      <RateLimitNotice left={limit.left} />
      <div className="nl-acct-row">
        <Button type="submit" disabled={busy || limit.limited} aria-busy={busy || undefined}>{t('verifyCta')}</Button>
        {onBack && <Button type="button" variant="ghost" onClick={onBack}>{t('goBack')}</Button>}
      </div>
    </form>
  );
}

/**
 * The first second factor (S-154): authenticator app (QR code, the key to type, a code from the app) or a passkey;
 * then the ten backup codes, once. `required` (Studio, console): no way past it; otherwise "Skip for now".
 */
export function EnrolSecondFactor({ email, required, onDone, onSkip }: {
  email: string; required: boolean; onDone: (s: AuthSession) => void; onSkip?: () => void;
}) {
  const t = useAuthKitT();
  const [method, setMethod] = useState<'totp' | 'passkey'>('totp');
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [enrolled, setEnrolled] = useState<Enrolled | null>(null);
  const limit = useRateLimit();

  const run = async (fn: () => Promise<void>) => {
    setFailure(''); setBusy(true);
    try { await fn(); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      setServer(fieldErrors(err, t).code ?? '');
      setFailure(fieldErrors(err, t).credential ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const start = () => run(async () => {
    if (method === 'totp') { setSetup(await authApi.enrolTotpSetup()); return; }
    if (!passkeysSupported()) throw new PasskeyError('unsupported');
    const options = await authApi.enrolPasskeyOptions();
    setEnrolled(await authApi.enrolPasskey(await createPasskey(options)));
  });
  const confirm = (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(codeSchema(t), code)) return;
    void run(async () => setEnrolled(await authApi.enrolTotp(code)));
  };
  const codeError = (tried ? firstIssue(codeSchema(t), code) : undefined) ?? server;

  if (enrolled) return <BackupCodesStep email={email} codes={enrolled.backupCodes} onContinue={() => onDone(enrolled.session)} />;
  return (
    <div className="nl-acct-step">
      <h3 className="nl-acct-h">{t('enrolTitle')}</h3>
      <p className="nl-acct-p">{t(required ? 'enrolRequired' : 'enrolOptional')}</p>
      {!setup ? (
        <>
          <RadioGroup className="nl-acct-options" aria-label={t('enrolTitle')}>
            <OptionCard role="radio" aria-checked={method === 'totp'} selected={method === 'totp'} title={t('enrolTotp')} description={t('enrolTotpDesc')} onClick={() => { setMethod('totp'); setFailure(''); }} />
            <OptionCard role="radio" aria-checked={method === 'passkey'} selected={method === 'passkey'} title={t('enrolPasskey')} description={t('enrolPasskeyDesc')} onClick={() => { setMethod('passkey'); setFailure(''); }} />
          </RadioGroup>
          {failure && <Alert tone="error">{failure}</Alert>}
          <div className="nl-acct-row">
            <Button onClick={() => void start()} disabled={busy} aria-busy={busy || undefined}>{method === 'passkey' ? t('enrolCreatePasskey') : t('enrolStart')}</Button>
            {!required && onSkip && <Button variant="ghost" onClick={onSkip}>{t('skipForNow')}</Button>}
          </div>
        </>
      ) : (
        <form noValidate onSubmit={confirm}>
          <p className="nl-acct-p">{t('enrolScan')}</p>
          <div className="nl-acct-qr">
            <img src={setup.qrCode} alt={t('totpQrAlt')} width={168} height={168} />
            <div className="nl-acct-qr-text">
              <span>{t('enrolManual')}</span>
              <code className="nl-acct-key" aria-label={t('enrolManual')}>{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</code>
              <span className="nl-acct-small">{t('enrolAccount', { email })}</span>
            </div>
          </div>
          <Field label={t('enrolCodeLabel')} error={codeError || undefined}>
            <TextInput className="nl-acct-code" name="totp" inputMode="numeric" autoComplete="one-time-code" placeholder="123456" value={code}
              onChange={e => { setCode(codeValue(e.target.value)); setServer(''); }} />
          </Field>
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <div className="nl-acct-row">
            <Button type="submit" disabled={busy || limit.limited} aria-busy={busy || undefined}>{t('enrolConfirm')}</Button>
            <Button type="button" variant="ghost" onClick={() => { setSetup(null); setCode(''); setTried(false); }}>{t('goBack')}</Button>
          </div>
        </form>
      )}
    </div>
  );
}

/** The ten backup codes, shown once: copy, download (.txt), and "I saved them" before going on. */
export function BackupCodesStep({ email, codes, onContinue }: { email: string; codes: string[]; onContinue: () => void }) {
  const t = useAuthKitT();
  const [saved, setSaved] = useState(false);
  const [tried, setTried] = useState(false);
  const [copied, setCopied] = useState(false);
  const text = `${t('backupFile', { email })}\n\n${codes.join('\n')}\n`;
  const copy = async () => { try { await navigator.clipboard.writeText(text); setCopied(true); } catch { setCopied(false); } };
  const download = () => {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url; a.download = 'backup-codes.txt';
    a.click();
    URL.revokeObjectURL(url);
  };
  return (
    <div className="nl-acct-step">
      <h3 className="nl-acct-h">{t('backupTitle')}</h3>
      <p className="nl-acct-p">{t('backupBody')}</p>
      <ol className="nl-acct-codes" aria-label={t('backupTitle')}>{codes.map(c => <li key={c}><code>{c}</code></li>)}</ol>
      <div className="nl-acct-row">
        <Button variant="secondary" onClick={() => void copy()}>{copied ? t('codesCopied') : t('copyCodes')}</Button>
        <Button variant="secondary" onClick={download}>{t('downloadCodes')}</Button>
      </div>
      <Checkbox name="saved" checked={saved} onChange={c => setSaved(c)} label={t('savedThem')} />
      {tried && !saved && <div role="alert" className="nl-error">{t('savedThemRequired')}</div>}
      <Button className="nl-acct-primary" onClick={() => { setTried(true); if (saved) onContinue(); }}>{t('backupContinue')}</Button>
    </div>
  );
}

/**
 * Forgot password: email → a code and a link by email → the code and the new password here (or the link's page,
 * {@link ResetWithLink}). Every session ends; the sign-in that follows still asks for the second factor.
 */
export function ForgotPasswordFlow({ app, initialEmail = '', onDone, onCancel }: {
  app: AccountApp; initialEmail?: string; onDone: (email: string) => void; onCancel?: () => void;
}) {
  const t = useAuthKitT();
  const { locale } = useLocale();
  const [email, setEmail] = useState(initialEmail);
  const [sent, setSent] = useState<(EmailSent & { at: number }) | null>(null);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const limit = useRateLimit();
  const left = useCountdown(sent ? sent.at + sent.resendAfterSeconds * 1000 : 0);

  const send = async (e?: FormEvent) => {
    e?.preventDefault();
    if (!email.trim()) { setServer({ email: t('emailRequired') }); return; }
    setBusy(true); setFailure(''); setServer({});
    try { const s = await authApi.forgotPassword(email, app, locale); setSent({ ...s, at: Date.now() }); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      const wait = retryAfter(err);
      setServer(fieldErrors(err, t));
      setFailure(wait ? t('resendEmailIn', { time: mmss(wait) }) : flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const errors = {
    code: (tried ? firstIssue(codeSchema(t), code) : undefined) ?? server.code,
    password: (tried ? firstIssue(passwordSchema(t), password) : undefined) ?? server.password,
    passwordConfirm: (tried && confirm !== password ? t('passwordConfirm') : undefined) ?? server.passwordConfirm,
  };
  const reset = async (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(codeSchema(t), code) || firstIssue(passwordSchema(t), password) || confirm !== password) return;
    setBusy(true); setFailure(''); setServer({});
    try { const r = await authApi.resetPassword({ code, password, passwordConfirm: confirm }); onDone(r.email); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { setSent(null); setFailure(t('restart')); return; }
      setServer(fieldErrors(err, t));
      setFailure(flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };

  return (
    <div className="nl-acct-step">
      <h3 className="nl-acct-h">{t('forgotTitle')}</h3>
      {!sent ? (
        <form noValidate onSubmit={e => void send(e)}>
          <p className="nl-acct-p">{t('forgotLede')}</p>
          <Field label={t('emailLabel')} error={server.email}>
            <TextInput name="email" type="email" autoComplete="username" placeholder={t('emailPlaceholder')} value={email} onChange={e => setEmail(e.target.value)} />
          </Field>
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <div className="nl-acct-row">
            <Button type="submit" disabled={busy || limit.limited} aria-busy={busy || undefined}>{busy ? t('sendingEmail') : t('sendResetCode')}</Button>
            {onCancel && <Button type="button" variant="ghost" onClick={onCancel}>{t('goBack')}</Button>}
          </div>
        </form>
      ) : (
        <form noValidate onSubmit={e => void reset(e)}>
          <p className="nl-acct-p">{t('verifyBody', { email: sent.email })}</p>
          <Field label={t('resetCodeLabel')} error={errors.code}>
            <TextInput className="nl-acct-code" name="code" inputMode="numeric" autoComplete="one-time-code" placeholder="123456" value={code}
              onChange={e => { setCode(codeValue(e.target.value)); setServer(s => ({ ...s, code: '' })); }} />
          </Field>
          <PasswordField label={t('newPassword')} hint={t('passwordHint')} autoComplete="new-password" value={password} error={errors.password}
            onChange={v => { setPassword(v); setServer(s => ({ ...s, password: '' })); }} />
          <PasswordField label={t('passwordConfirmLabel')} name="passwordConfirm" autoComplete="new-password" value={confirm} error={errors.passwordConfirm} onChange={setConfirm} />
          <div className="nl-acct-hint">
            {left > 0 ? <span aria-live="polite">{t('resendEmailIn', { time: mmss(left) })}</span>
              : <button type="button" className="nl-acct-link" onClick={() => void send()} disabled={limit.limited}>{t('resendEmail')}</button>}
          </div>
          {local() && <p className="nl-acct-dev">{t('devMailpit')}</p>}
          {failure && <Alert tone="error">{failure}</Alert>}
          <RateLimitNotice left={limit.left} />
          <div className="nl-acct-row">
            <Button type="submit" disabled={busy || limit.limited} aria-busy={busy || undefined}>{t('resetCta')}</Button>
            {onCancel && <Button type="button" variant="ghost" onClick={onCancel}>{t('goBack')}</Button>}
          </div>
        </form>
      )}
    </div>
  );
}

/** The password-reset email's link (`/reset-password?token=`): the new password only. */
export function ResetWithLink({ token, onDone }: { token: string; onDone: (email: string) => void }) {
  const t = useAuthKitT();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const errors = {
    password: (tried ? firstIssue(passwordSchema(t), password) : undefined) ?? server.password,
    passwordConfirm: (tried && confirm !== password ? t('passwordConfirm') : undefined) ?? server.passwordConfirm,
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(passwordSchema(t), password) || confirm !== password) return;
    setBusy(true); setFailure(''); setServer({});
    try { onDone((await authApi.resetPassword({ token, password, passwordConfirm: confirm })).email); } catch (err) {
      const fields = fieldErrors(err, t);
      setServer(fields);
      setFailure(fields.token ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  return (
    <form noValidate onSubmit={e => void submit(e)} className="nl-acct-step">
      <h3 className="nl-acct-h">{t('forgotTitle')}</h3>
      <PasswordField label={t('newPassword')} hint={t('passwordHint')} autoComplete="new-password" value={password} error={errors.password} onChange={setPassword} />
      <PasswordField label={t('passwordConfirmLabel')} name="passwordConfirm" autoComplete="new-password" value={confirm} error={errors.passwordConfirm} onChange={setConfirm} />
      {failure && <Alert tone="error">{failure}</Alert>}
      <Button type="submit" className="nl-acct-primary" disabled={busy} aria-busy={busy || undefined}>{t('resetCta')}</Button>
    </form>
  );
}

/**
 * The verification email's link (`/verify-email?token=`): confirms the address. The browser that sent the form is
 * signed in (`onSignedIn`, then enrolment); any other one is asked to sign in (`signInAction`).
 */
export function VerifyEmailLink({ token, onSignedIn, signInAction }: {
  token: string; onSignedIn: (s: AuthSession) => void; signInAction: (email: string) => ReactNode;
}) {
  const t = useAuthKitT();
  const [state, setState] = useState<{ kind: 'checking' } | { kind: 'confirmed'; email: string } | { kind: 'failed'; message: string }>({ kind: 'checking' });
  const once = useRef(false);
  useEffect(() => {
    if (once.current) return;
    once.current = true;
    authApi.verifyEmailLink(token)
      .then(r => { if (r.signedIn && r.session) onSignedIn(r.session); else setState({ kind: 'confirmed', email: r.email }); })
      .catch((err: unknown) => setState({ kind: 'failed', message: fieldErrors(err, t).token ?? flowError(err, t) ?? t('linkExpired') }));
  }, [token]); // eslint-disable-line react-hooks/exhaustive-deps
  if (state.kind === 'checking') return <p className="nl-acct-p" role="status">{t('linkChecking')}</p>;
  if (state.kind === 'failed') return <Alert tone="error">{state.message}</Alert>;
  return (
    <div className="nl-acct-step">
      <Alert tone="info" role="status" title={t('linkConfirmed')}>{t('linkConfirmedSignIn')}</Alert>
      {signInAction(state.email)}
    </div>
  );
}
