import { useState, type FormEvent } from 'react';
import { Alert, Button, Field, TextInput, codeValue } from '@northline/ui';
import { codeSchema, firstIssue, passwordSchema } from './api';
import { fieldErrors } from './errors';
import { useAuthKitT } from './messages';
import { PasswordField } from './account';
import { authenticatorSetup, changePassword, replaceAuthenticator } from './security';

/**
 * S-154, Settings › Security (Studio) and Account › Security (consumer site): change the password and set up the
 * authenticator app again. `run` is the app's security-change runner — it opens the step-up dialog on a 403
 * `step_up_required` and retries — and resolves false when the change didn't happen.
 */
type Runner = (fn: () => Promise<void>) => Promise<void>;

export function ChangePasswordForm({ hasPassword, current, run, onDone, onCancel }: {
  hasPassword: boolean; current?: string | null; run: Runner; onDone: () => void; onCancel: () => void;
}) {
  const t = useAuthKitT();
  const [old, setOld] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const errors = {
    currentPassword: (tried && hasPassword && !old ? t('currentPasswordRequired') : undefined) ?? server.currentPassword,
    password: (tried ? firstIssue(passwordSchema(t), password) : undefined) ?? server.password,
    passwordConfirm: (tried && confirm !== password ? t('passwordConfirm') : undefined) ?? server.passwordConfirm,
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if ((hasPassword && !old) || firstIssue(passwordSchema(t), password) || confirm !== password) return;
    void run(async () => {
      try {
        await changePassword({ currentPassword: hasPassword ? old : undefined, password, passwordConfirm: confirm }, current);
        onDone();
      } catch (err) {
        const fields = fieldErrors(err, t);
        if (Object.keys(fields).length === 0) throw err;
        setServer(fields);
      }
    });
  };
  return (
    <form noValidate onSubmit={submit} className="nl-acct-step">
      {hasPassword && <PasswordField label={t('currentPassword')} name="currentPassword" value={old} error={errors.currentPassword} onChange={v => { setOld(v); setServer({}); }} />}
      <PasswordField label={t('newPassword')} hint={t('passwordHint')} autoComplete="new-password" value={password} error={errors.password} onChange={v => { setPassword(v); setServer({}); }} />
      <PasswordField label={t('passwordConfirmLabel')} name="passwordConfirm" autoComplete="new-password" value={confirm} error={errors.passwordConfirm} onChange={setConfirm} />
      <div className="nl-acct-row">
        <Button type="submit">{t('savePassword')}</Button>
        <Button type="button" variant="ghost" onClick={onCancel}>{t('cancelChange')}</Button>
      </div>
    </form>
  );
}

export function AuthenticatorSetupForm({ email, run, onDone, onCancel }: { email: string; run: Runner; onDone: () => void; onCancel: () => void }) {
  const t = useAuthKitT();
  const [setup, setSetup] = useState<{ secret: string; qrCode: string } | null>(null);
  const [code, setCode] = useState('');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState('');
  const [failure, setFailure] = useState('');
  const start = async () => {
    setFailure('');
    try { setSetup(await authenticatorSetup()); } catch { setFailure(t('restart')); }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (firstIssue(codeSchema(t), code)) return;
    void run(async () => {
      try { await replaceAuthenticator(code); onDone(); } catch (err) {
        const f = fieldErrors(err, t).code;
        if (!f) throw err;
        setServer(f);
      }
    });
  };
  if (!setup) {
    return (
      <div className="nl-acct-row">
        <Button onClick={() => void start()}>{t('enrolStart')}</Button>
        <Button variant="ghost" onClick={onCancel}>{t('cancelChange')}</Button>
        {failure && <Alert tone="error">{failure}</Alert>}
      </div>
    );
  }
  return (
    <form noValidate onSubmit={submit} className="nl-acct-step">
      <p className="nl-acct-p">{t('enrolScan')}</p>
      <div className="nl-acct-qr">
        <img src={setup.qrCode} alt={t('totpQrAlt')} width={168} height={168} />
        <div className="nl-acct-qr-text">
          <span>{t('enrolManual')}</span>
          <code className="nl-acct-key">{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</code>
          <span className="nl-acct-small">{t('enrolAccount', { email })}</span>
        </div>
      </div>
      <Field label={t('enrolCodeLabel')} error={((tried ? firstIssue(codeSchema(t), code) : undefined) ?? server) || undefined}>
        <TextInput className="nl-acct-code" inputMode="numeric" autoComplete="one-time-code" placeholder="123456" value={code}
          onChange={e => { setCode(codeValue(e.target.value)); setServer(''); }} />
      </Field>
      <div className="nl-acct-row">
        <Button type="submit">{t('enrolConfirm')}</Button>
        <Button type="button" variant="ghost" onClick={onCancel}>{t('cancelChange')}</Button>
      </div>
    </form>
  );
}
