import { useState, type FormEvent, type ReactNode } from 'react';
import { Button, Field, TextInput, codeValue } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useFinanceT, type FinanceKey } from './messages';
import { StepUpFailed, stepUpWithCode, stepUpWithPasskey } from './stepUp';

const FAILURE: Record<StepUpFailed['reason'], FinanceKey> = {
  cancelled: 'stepUpCancelled', unsupported: 'stepUpUnsupported', rejected: 'stepUpFailed', locked: 'stepUpLocked', signed_out: 'stepUpSignedOut',
};

/**
 * "Confirm with your passkey." — gets a fresh step-up proof, then hands it to `onProof` (which performs the money move
 * and may throw; its error is shown here). Falls back to the authenticator app code.
 */
export function StepUpPanel({ intro, confirmLabel, onProof, onCancel, busy }: {
  intro?: ReactNode; confirmLabel: string; onProof: (proof: string) => Promise<unknown>; onCancel: () => void; busy?: boolean;
}) {
  const t = useFinanceT();
  const [mode, setMode] = useState<'passkey' | 'code'>('passkey');
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string>();
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState(false);

  async function run(get: () => Promise<string>) {
    setError(undefined);
    setPending(true);
    try {
      const proof = await get();
      await onProof(proof);
    } catch (e) {
      if (e instanceof StepUpFailed) setError(t(FAILURE[e.reason]));
      else if (e instanceof ValidationError && e.errors.some(x => x.field === 'code')) setCodeError(e.errors.find(x => x.field === 'code')!.message);
      else setError(e instanceof Error ? e.message : t('stepUpFailed'));
    } finally {
      setPending(false);
    }
  }

  function submitCode(e: FormEvent) {
    e.preventDefault();
    const c = code.trim();
    const problem = !c ? t('codeRequired') : !/^\d{6}$/.test(c) ? t('codeFormat') : undefined;
    setCodeError(problem);
    if (!problem) void run(() => stepUpWithCode(c));
  }

  const working = pending || busy;
  return (
    <div>
      {intro}
      {error ? <div role="alert" className="nl-error" style={{ marginTop: 10 }}>{error}</div> : null}
      {mode === 'passkey' ? (
        <div className="fin-actions">
          <Button type="button" onClick={() => void run(stepUpWithPasskey)} disabled={working} aria-busy={working}>{working ? t('working') : confirmLabel}</Button>
          <Button type="button" variant="ghost" onClick={onCancel} disabled={working}>{t('cancel')}</Button>
          <Button type="button" variant="ghost" className="fin-link" onClick={() => { setMode('code'); setError(undefined); }}>{t('useCode')}</Button>
        </div>
      ) : (
        <form onSubmit={submitCode} noValidate style={{ marginTop: 12 }}>
          <Field label={t('code')} error={codeError}>
            <TextInput inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e => setCode(codeValue(e.target.value))} style={{ maxWidth: 160 }} />
          </Field>
          <div className="fin-actions">
            <Button type="submit" disabled={working} aria-busy={working}>{working ? t('working') : t('confirm')}</Button>
            <Button type="button" variant="ghost" onClick={onCancel} disabled={working}>{t('cancel')}</Button>
            <Button type="button" variant="ghost" className="fin-link" onClick={() => { setMode('passkey'); setCodeError(undefined); }}>{t('usePasskeyInstead')}</Button>
          </div>
        </form>
      )}
    </div>
  );
}
