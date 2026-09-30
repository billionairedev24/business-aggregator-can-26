import { useMemo, useState, type FormEvent, type InputHTMLAttributes } from 'react';
import { useForm, useStore } from '@tanstack/react-form';
import { Alert, Button, Checkbox, Field, OptionCard, StepBars, TextInput, useLocale } from '@northline/ui';
import { visibleError } from '../../lib/forms';
import { authApi, codeSchema, firstIssue, registerSchema, useAuthMutation, type AuthSession, type RegisterValues, type RegistrationStep, type TotpSetup } from './api';
import { fieldErrors, flowError, isRestart, retryAfter } from './errors';
import { useAuthT } from './messages';
import { RateLimitNotice, useRateLimit } from './rateLimit';
import { SocialButtons } from './SocialButtons';
import { mmss, useCountdown } from './useCountdown';
import { createPasskey, PasskeyError, passkeysSupported } from './webauthn';

type Step = 'form' | 'otp' | 'mfa' | 'done';
/** Design's progress for the register tab: form and code share the first bar (aStep = ⌊nuStep / 3 × 2⌋). */
const PROGRESS: Record<Step, number> = { form: 0, otp: 0, mfa: 1, done: 2 };

export interface RegisterFlowProps {
  prefill?: Partial<Pick<RegisterValues, 'firstName' | 'lastName' | 'email'>>;
  onSignIn: () => void;
  /** "Continue to business onboarding" — the BFF hand-off. */
  onFinished: (session: AuthSession) => void;
}

/** Create account: form → 6-digit code → second factor (passkey or authenticator app) → done. */
export function RegisterFlow({ prefill, onSignIn, onFinished }: RegisterFlowProps) {
  const [step, setStep] = useState<Step>('form');
  const [values, setValues] = useState<RegisterValues>({ firstName: prefill?.firstName ?? '', lastName: prefill?.lastName ?? '', phone: '', email: prefill?.email ?? '', terms: false });
  const [sent, setSent] = useState<{ info: RegistrationStep; at: number } | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [restartNotice, setRestartNotice] = useState('');
  const t = useAuthT();

  const restart = () => { setRestartNotice(t('restart')); setStep('form'); };
  return (
    <>
      <StepBars total={3} done={PROGRESS[step]} label={t('progress')} />
      {step === 'form' && (
        <RegisterForm
          initial={values}
          notice={restartNotice}
          onSignIn={onSignIn}
          onSent={(v, info) => { setValues(v); setSent({ info, at: Date.now() }); setRestartNotice(''); setStep('otp'); }}
        />
      )}
      {step === 'otp' && sent && (
        <PhoneCodeStep
          phone={values.phone.trim()}
          sent={sent}
          onResent={info => setSent({ info, at: Date.now() })}
          onVerified={() => setStep('mfa')}
          onBack={() => setStep('form')}
          onRestart={restart}
        />
      )}
      {step === 'mfa' && (
        <SecondFactorStep
          onCreated={s => { setSession(s); setStep('done'); }}
          onBack={() => setStep('otp')}
          onRestart={restart}
        />
      )}
      {step === 'done' && session && (
        <>
          <Alert tone="info" title={t('accountCreated')} role="status">
            {t('accountCreatedBody', { name: `${session.user.firstName} ${session.user.lastName}`.trim(), phone: values.phone.trim() })}
          </Alert>
          <Button className="nl-auth-primary" onClick={() => onFinished(session)}>{t('continueOnboarding')}</Button>
        </>
      )}
    </>
  );
}

// ── step 1: the form ──────────────────────────────────────────────────────────────────────────────────────────────

function RegisterForm({ initial, notice, onSignIn, onSent }: { initial: RegisterValues; notice: string; onSignIn: () => void; onSent: (v: RegisterValues, info: RegistrationStep) => void }) {
  const t = useAuthT();
  const schema = useMemo(() => registerSchema(t), [t]);
  const [server, setServer] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState('');
  const limit = useRateLimit();
  const { locale } = useLocale();
  const send = useAuthMutation((v: RegisterValues) => authApi.register(v, locale));
  const form = useForm({
    defaultValues: initial,
    validators: { onChange: schema, onSubmit: schema },
    onSubmit: async ({ value }) => {
      setFailure('');
      try {
        onSent(value, await send.mutateAsync(value));
      } catch (e) {
        if (limit.hold(e)) return;
        setServer(fieldErrors(e, t));
        setFailure(flowError(e, t) ?? '');
      }
    },
  });
  const submitted = useStore(form.store, s => s.submissionAttempts > 0);
  const metas = useStore(form.store, s => s.fieldMeta);
  const errorOf = (name: keyof RegisterValues) => {
    const meta = metas[name];
    return (meta ? visibleError(meta, submitted) : undefined) ?? server[name];
  };
  const attention = submitted ? (['firstName', 'lastName', 'phone', 'email', 'terms'] as const).filter(k => errorOf(k)).length : 0;
  const clearServer = (name: string) => setServer(s => { if (!(name in s)) return s; const { [name]: _, ...rest } = s; return rest; });
  const submit = (e: FormEvent) => { e.preventDefault(); e.stopPropagation(); void form.handleSubmit(); };

  const text = (name: 'firstName' | 'lastName' | 'phone' | 'email', label: string, placeholder: string, extra: InputHTMLAttributes<HTMLInputElement> = {}) => (
    <form.Field name={name}>
      {field => (
        <Field label={label} error={errorOf(name)}>
          <TextInput name={name} placeholder={placeholder} value={field.state.value} onBlur={field.handleBlur}
            onChange={e => { clearServer(name); field.handleChange(e.target.value); }} {...extra} />
        </Field>
      )}
    </form.Field>
  );

  return (
    <form noValidate onSubmit={submit}>
      {notice && <Alert tone="error">{notice}</Alert>}
      <p className="nl-auth-p">{t('registerLede')}</p>
      <div className="nl-auth-fields">
        <div className="nl-auth-names">
          {text('firstName', t('firstName'), t('firstNamePh'), { autoComplete: 'given-name' })}
          {text('lastName', t('lastName'), t('lastNamePh'), { autoComplete: 'family-name' })}
        </div>
        {text('phone', t('mobile'), t('mobilePh'), { type: 'tel', inputMode: 'tel', autoComplete: 'tel' })}
        {text('email', t('email'), t('emailPh'), { type: 'email', autoComplete: 'email' })}
      </div>
      <form.Field name="terms">
        {field => (
          <div>
            <Checkbox className="nl-auth-terms" name="terms" checked={field.state.value}
              onChange={c => { clearServer('terms'); field.handleChange(c); field.handleBlur(); }}
              label={<span>{t('termsBefore')}<a href="/legal/terms.html" target="_blank" rel="noopener">{t('terms')}</a>{t('termsAnd')}<a href="/legal/privacy.html" target="_blank" rel="noopener">{t('privacy')}</a>{t('termsAfter')}</span>} />
            {errorOf('terms') && <div role="alert" className="nl-error">{errorOf('terms')}</div>}
          </div>
        )}
      </form.Field>
      {attention > 0 && <Alert tone="error">{t('attention', { count: attention })}</Alert>}
      {failure && <Alert tone="error">{failure}</Alert>}
      <RateLimitNotice left={limit.left} />
      <Button type="submit" className="nl-auth-primary" disabled={send.isPending || limit.limited} aria-busy={send.isPending || undefined}>{send.isPending ? t('sending') : t('sendCode')}</Button>
      <SocialButtons />
      <div className="nl-auth-switch">{t('alreadyCustomer')}<button type="button" className="nl-auth-link" onClick={onSignIn}>{t('tabSignIn')}</button>{t('alreadyCustomerAfter')}</div>
    </form>
  );
}

// ── step 2: the phone code ────────────────────────────────────────────────────────────────────────────────────────

function PhoneCodeStep({ phone, sent, onResent, onVerified, onBack, onRestart }: {
  phone: string; sent: { info: RegistrationStep; at: number }; onResent: (s: RegistrationStep) => void; onVerified: () => void; onBack: () => void; onRestart: () => void;
}) {
  const t = useAuthT();
  const [code, setCode] = useState('');
  const [touched, setTouched] = useState(false);
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState('');
  const [notice, setNotice] = useState<{ tone: 'info' | 'error'; text: string } | null>(null);
  const [calledFor, setCalledFor] = useState<number | null>(null);
  const verify = useAuthMutation(authApi.verifyPhone);
  const { locale } = useLocale();
  const resend = useAuthMutation((channel: 'sms' | 'voice') => authApi.resend(channel, locale));
  const limit = useRateLimit();
  const left = useCountdown(sent.at + sent.info.resendAfterSeconds * 1000);
  const clientError = (touched || tried) ? firstIssue(codeSchema(t), code) : undefined;
  const error = clientError ?? server;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(codeSchema(t), code)) return;
    try { await verify.mutateAsync(code); onVerified(); } catch (err) {
      if (limit.hold(err)) { setNotice(null); return; }
      if (isRestart(err)) return onRestart();
      setServer(fieldErrors(err, t).code ?? '');
      const f = flowError(err, t);
      if (f) setNotice({ tone: 'error', text: f });
    }
  };
  const again = async (channel: 'sms' | 'voice') => {
    setNotice(null);
    try {
      const info = await resend.mutateAsync(channel);
      onResent(info);
      setServer('');
      if (channel === 'voice') setCalledFor(sent.at);
      setNotice({ tone: 'info', text: t(channel === 'voice' ? 'calling' : 'resent') });
    } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) return onRestart();
      const wait = retryAfter(err);
      setNotice({ tone: 'error', text: wait ? t('resendIn', { time: mmss(wait) }) : flowError(err, t, channel) ?? '' });
    }
  };
  return (
    <form noValidate onSubmit={e => void submit(e)}>
      <Field label={t('codeLabel', { phone })} error={error || undefined}>
        <TextInput className="nl-auth-code" name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
          placeholder={t('codePh')} value={code} onBlur={() => setTouched(true)}
          onChange={e => { setCode(e.target.value.replace(/\s/g, '')); setTouched(true); setServer(''); }} />
      </Field>
      <div className="nl-auth-hint">
        {left > 0
          ? <span aria-live="polite">{t('resendIn', { time: mmss(left) })}</span>
          : <button type="button" className="nl-auth-link" onClick={() => void again('sms')} disabled={resend.isPending || limit.limited}>{t('resend')}</button>}
        {' · '}
        <button type="button" className="nl-auth-link" onClick={() => void again('voice')} disabled={resend.isPending || limit.limited || calledFor === sent.at}>{t('callMe')}</button>
      </div>
      {notice && !limit.limited && <Alert tone={notice.tone} role={notice.tone === 'error' ? 'alert' : 'status'}>{notice.text}</Alert>}
      <RateLimitNotice left={limit.left} />
      <div className="nl-auth-row">
        <Button type="submit" disabled={verify.isPending || limit.limited} aria-busy={verify.isPending || undefined}>{t('verify')}</Button>
        <Button type="button" variant="ghost" onClick={onBack}>{t('back')}</Button>
      </div>
    </form>
  );
}

// ── step 3: the second factor ─────────────────────────────────────────────────────────────────────────────────────

function SecondFactorStep({ onCreated, onBack, onRestart }: { onCreated: (s: AuthSession) => void; onBack: () => void; onRestart: () => void }) {
  const t = useAuthT();
  const [method, setMethod] = useState<'passkey' | 'totp'>('passkey');
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<void>) => {
    setFailure(''); setBusy(true);
    try { await fn(); } catch (err) {
      if (isRestart(err)) { onRestart(); return; }
      setServer(fieldErrors(err, t).code ?? '');
      const credential = fieldErrors(err, t).credential;
      setFailure(credential ?? flowError(err, t) ?? '');
    } finally { setBusy(false); }
  };
  const createKey = () => run(async () => {
    if (!passkeysSupported()) throw new PasskeyError('unsupported');
    const options = await authApi.passkeyRegistrationOptions();
    const credential = await createPasskey(options);
    onCreated(await authApi.registerPasskey(credential));
  });
  const startTotp = () => run(async () => { setSetup(await authApi.totpSetup()); });
  const confirmTotp = (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(codeSchema(t), code)) return;
    void run(async () => onCreated(await authApi.registerTotp(code)));
  };
  const codeError = (tried ? firstIssue(codeSchema(t), code) : undefined) ?? server;

  return (
    <div>
      <div className="nl-field">
        <span className="nl-label" id="nl-mfa-label">{t('mfaLabel')}</span>
        <div className="nl-auth-options" role="radiogroup" aria-labelledby="nl-mfa-label">
          <OptionCard role="radio" aria-checked={method === 'passkey'} selected={method === 'passkey'} title={t('mfaPasskey')} description={t('mfaPasskeyDesc')} onClick={() => { setMethod('passkey'); setFailure(''); }} />
          <OptionCard role="radio" aria-checked={method === 'totp'} selected={method === 'totp'} title={t('mfaTotp')} description={t('mfaTotpDesc')} onClick={() => { setMethod('totp'); setFailure(''); }} />
        </div>
      </div>
      <div className="nl-auth-note">{t('smsBackup')}</div>
      {method === 'totp' && setup ? (
        <form noValidate onSubmit={confirmTotp}>
          <div className="nl-auth-qr">
            <img src={setup.qrCode} alt={t('qrAlt')} />
            <div style={{ flex: '1 1 160px', minWidth: 0 }}>
              <p style={{ margin: '0 0 6px' }}>{t('qrHelp')}</p>
              <code>{t('qrKey', { secret: setup.secret })}</code>
            </div>
          </div>
          <Field label={t('code6')} error={codeError || undefined} className="nl-auth-totp">
            <TextInput className="nl-auth-code" name="totp" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder={t('codePh')}
              value={code} onChange={e => { setCode(e.target.value.replace(/\s/g, '')); setServer(''); }} />
          </Field>
          {failure && <Alert tone="error">{failure}</Alert>}
          <div className="nl-auth-row">
            <Button type="submit" disabled={busy} aria-busy={busy || undefined}>{t('verifyCode')}</Button>
            <Button type="button" variant="ghost" onClick={() => { setSetup(null); onBack(); }}>{t('back')}</Button>
          </div>
        </form>
      ) : (
        <>
          {failure && <Alert tone="error">{failure}</Alert>}
          <div className="nl-auth-row">
            <Button onClick={() => void (method === 'passkey' ? createKey() : startTotp())} disabled={busy} aria-busy={busy || undefined}>
              {method === 'passkey' ? t('createPasskey') : t('scanQr')}
            </Button>
            <Button variant="ghost" onClick={onBack}>{t('back')}</Button>
          </div>
        </>
      )}
    </div>
  );
}
