import { useMemo, useState, type FormEvent, type InputHTMLAttributes } from 'react';
import { useForm, useStore } from '@tanstack/react-form';
import { Alert, Button, Checkbox, Field, StepBars, TextInput, useLocale } from '@northline/ui';
import { visibleError } from '../../lib/forms';
import {
  authApi, createAccountSchema, useAuthMutation, type AuthSession, type CreateAccountValues, type EmailSent, fieldErrors, flowError, RateLimitNotice, useRateLimit,
  PasswordField, EmailCodeStep, EnrolSecondFactor,
} from '@northline/auth-kit';
import { useAuthT } from './messages';
import { SocialButtons } from './SocialButtons';

type Step = 'form' | 'verify' | 'mfa' | 'done';
const PROGRESS: Record<Step, number> = { form: 0, verify: 0, mfa: 1, done: 2 };
const FIELDS = ['firstName', 'lastName', 'email', 'password', 'passwordConfirm', 'phone', 'terms'] as const;

export interface RegisterFlowProps {
  prefill?: Partial<Pick<CreateAccountValues, 'firstName' | 'lastName' | 'email'>>;
  onSignIn: () => void;
  /** "Continue to business onboarding" — the BFF hand-off. */
  onFinished: (session: AuthSession) => void;
}

/**
 * Create account (S-154): name, email, password, optional mobile, terms → the 6-digit code from the email (Mailpit
 * locally) → a second factor (authenticator app or passkey; required for business accounts) and the backup codes →
 * done. The answer to the form is the same whether or not the email already has an account (that address is told
 * so by email).
 */
export function RegisterFlow({ prefill, onSignIn, onFinished }: RegisterFlowProps) {
  const [step, setStep] = useState<Step>('form');
  const [values, setValues] = useState<CreateAccountValues>({ firstName: prefill?.firstName ?? '', lastName: prefill?.lastName ?? '', email: prefill?.email ?? '', password: '', passwordConfirm: '', phone: '', terms: false });
  const [sent, setSent] = useState<EmailSent | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [restartNotice, setRestartNotice] = useState('');
  const t = useAuthT();

  const restart = () => { setRestartNotice(t('restart')); setStep('form'); };
  return (
    <>
      <StepBars total={3} done={PROGRESS[step]} label={t('progress')} />
      {step === 'form' && (
        <RegisterForm initial={values} notice={restartNotice} onSignIn={onSignIn}
          onSent={(v, info) => { setValues({ ...v, password: '', passwordConfirm: '' }); setSent(info); setRestartNotice(''); setStep('verify'); }} />
      )}
      {step === 'verify' && sent && (
        <EmailCodeStep sent={sent} onVerified={s => { setSession(s); setStep(s.needsSecondFactor === false ? 'done' : 'mfa'); }} onBack={() => setStep('form')} onRestart={restart} />
      )}
      {step === 'mfa' && session && (
        <EnrolSecondFactor email={session.user.email ?? values.email.trim()} required onDone={s => { setSession(s); setStep('done'); }} />
      )}
      {step === 'done' && session && (
        <>
          <Alert tone="info" title={t('accountCreated')} role="status">
            {t('accountCreatedBody', { name: `${session.user.firstName} ${session.user.lastName}`.trim() })}
          </Alert>
          <Button className="nl-auth-primary" onClick={() => onFinished(session)}>{t('continueOnboarding')}</Button>
        </>
      )}
    </>
  );
}

// ── step 1: the form ──────────────────────────────────────────────────────────────────────────────────────────────

function RegisterForm({ initial, notice, onSignIn, onSent }: { initial: CreateAccountValues; notice: string; onSignIn: () => void; onSent: (v: CreateAccountValues, info: EmailSent) => void }) {
  const t = useAuthT();
  const schema = useMemo(() => createAccountSchema(t), [t]);
  const [server, setServer] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState('');
  const limit = useRateLimit();
  const { locale } = useLocale();
  const send = useAuthMutation((v: CreateAccountValues) => authApi.createAccount({ ...v, termsLanguage: locale === 'fr' ? 'fr' : 'en' }, 'studio', locale));
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
  const errorOf = (name: keyof CreateAccountValues) => {
    const meta = metas[name];
    return (meta ? visibleError(meta, submitted) : undefined) ?? server[name];
  };
  const attention = submitted ? FIELDS.filter(k => errorOf(k)).length : 0;
  const clearServer = (name: string) => setServer(s => { if (!(name in s)) return s; const { [name]: _, ...rest } = s; return rest; });
  const submit = (e: FormEvent) => { e.preventDefault(); e.stopPropagation(); void form.handleSubmit(); };

  const text = (name: 'firstName' | 'lastName' | 'phone' | 'email', label: string, placeholder: string, extra: InputHTMLAttributes<HTMLInputElement> = {}, note?: string) => (
    <form.Field name={name}>
      {field => (
        <Field label={label} note={note} error={errorOf(name)}>
          <TextInput name={name} placeholder={placeholder} value={field.state.value} onBlur={field.handleBlur}
            onChange={e => { clearServer(name); field.handleChange(e.target.value); }} {...extra} />
        </Field>
      )}
    </form.Field>
  );
  const secret = (name: 'password' | 'passwordConfirm', label: string, hint?: string) => (
    <form.Field name={name}>
      {field => (
        <PasswordField label={label} hint={hint} name={name} autoComplete="new-password" value={field.state.value} error={errorOf(name)}
          onBlur={field.handleBlur} onChange={v => { clearServer(name); field.handleChange(v); }} />
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
        {text('email', t('email'), t('emailPh'), { type: 'email', autoComplete: 'email' })}
        {secret('password', t('passwordLabel'), t('passwordHint'))}
        {secret('passwordConfirm', t('passwordConfirmLabel'))}
        {text('phone', t('mobile'), t('mobilePh'), { type: 'tel', inputMode: 'tel', autoComplete: 'tel' }, t('optional'))}
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
      <Button type="submit" className="nl-auth-primary" disabled={send.isPending || limit.limited} aria-busy={send.isPending || undefined}>{send.isPending ? t('creating') : t('createAccount')}</Button>
      <SocialButtons />
      <div className="nl-auth-switch">{t('alreadyCustomer')}<button type="button" className="nl-auth-link" onClick={onSignIn}>{t('tabSignIn')}</button>{t('alreadyCustomerAfter')}</div>
    </form>
  );
}
