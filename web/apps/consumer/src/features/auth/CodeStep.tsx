import { useState, type FormEvent } from 'react';
import { Alert, Button, Field, TextInput } from '@northline/ui';
import { codeSchema, fieldErrors, firstIssue, flowError, isRestart, mmss, retryAfter, RateLimitNotice, useCountdown, useRateLimit, type AuthKitKey, type CodeSent } from '@northline/auth-kit';
import { useAuthT } from './messages';

export interface Sent extends CodeSent { at: number }

export interface CodeStepProps {
  label: string;
  sent: Sent;
  onResend: (channel: 'sms' | 'voice') => Promise<CodeSent>;
  onResent: (sent: Sent) => void;
  onVerify: (code: string) => Promise<void>;
  onRestart: () => void;
  /** Message for a wrong code: registration's "doesn't match" or sign-in's "didn't work" (the server's wording). */
  mismatch: AuthKitKey;
}

/** The 6-digit code (design 06 `au.otp`): "Resend in 0:42 · Call me instead", Verify. */
export function CodeStep({ label, sent, onResend, onResent, onVerify, onRestart, mismatch }: CodeStepProps) {
  const t = useAuthT();
  const [code, setCode] = useState('');
  const [touched, setTouched] = useState(false);
  const [server, setServer] = useState('');
  const [notice, setNotice] = useState<{ tone: 'info' | 'error'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [calledFor, setCalledFor] = useState<number | null>(null);
  const limit = useRateLimit();
  const left = useCountdown(sent.at + sent.resendAfterSeconds * 1000);
  const error = (touched ? firstIssue(codeSchema(t), code) : undefined) ?? server;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (firstIssue(codeSchema(t), code)) return;
    setBusy(true); setNotice(null);
    try { await onVerify(code); limit.clear(); } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { onRestart(); return; }
      setServer(fieldErrors(err, t, mismatch).code ?? '');
      const f = flowError(err, t);
      if (f) setNotice({ tone: 'error', text: f });
    } finally { setBusy(false); }
  };
  const again = async (channel: 'sms' | 'voice') => {
    setNotice(null);
    try {
      const next = await onResend(channel);
      const at = Date.now();
      onResent({ ...next, at });
      setServer('');
      if (channel === 'voice') setCalledFor(at);
      setNotice({ tone: 'info', text: t(channel === 'voice' ? 'calling' : 'resent') });
    } catch (err) {
      if (limit.hold(err)) return;
      if (isRestart(err)) { onRestart(); return; }
      const wait = retryAfter(err);
      setNotice({ tone: 'error', text: wait ? t('resendIn', { time: mmss(wait) }) : flowError(err, t, channel) ?? '' });
    }
  };
  return (
    <form noValidate onSubmit={e => void submit(e)}>
      <Field label={label} error={error || undefined}>
        <TextInput className="nl-auth-code" name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder={t('codePh')}
          value={code} onBlur={() => setTouched(true)} onChange={e => { setCode(e.target.value.replace(/\s/g, '')); setServer(''); }} />
      </Field>
      <div className="nl-auth-hint">
        {left > 0
          ? <span aria-live="polite">{t('resendIn', { time: mmss(left) })}</span>
          : <button type="button" className="nl-auth-link" onClick={() => void again('sms')} disabled={limit.limited}>{t('resend')}</button>}
        {' · '}
        <button type="button" className="nl-auth-link" onClick={() => void again('voice')} disabled={limit.limited || calledFor !== null && calledFor >= sent.at}>{t('callMe')}</button>
      </div>
      {notice && !limit.limited && <Alert tone={notice.tone} role={notice.tone === 'error' ? 'alert' : 'status'}>{notice.text}</Alert>}
      <RateLimitNotice left={limit.left} />
      <Button type="submit" className="nl-auth-primary" disabled={busy || limit.limited} aria-busy={busy || undefined}>{t('verify')}</Button>
    </form>
  );
}
