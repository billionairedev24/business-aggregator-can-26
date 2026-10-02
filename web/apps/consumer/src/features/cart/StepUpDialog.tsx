import { useState } from 'react';
import { Dialog, Field, TextInput, codeValue } from '@northline/ui';
import { signInHref } from '../session/api';
import { useCartT } from './messages';
import { enrolPasskey, stepUpWithCode, stepUpWithPasskey, StepUpFailed } from './stepUp';

/**
 * "Confirm it's you" before paying (S-51 step-up rule): the account's passkey first, its authenticator code instead;
 * or, for an account with no second factor, "Add a passkey to pay".
 */
export function StepUpDialog({ mode, onProof, onClose }: { mode: 'required' | 'enrol'; onProof: (proof: string) => void; onClose: () => void }) {
  const t = useCartT();
  const [useCode, setUseCode] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string>();
  const [signedOut, setSignedOut] = useState(false);
  const [busy, setBusy] = useState(false);
  const run = async (step: () => Promise<string>) => {
    setBusy(true); setError(undefined);
    try { onProof(await step()); } catch (e) {
      const reason = e instanceof StepUpFailed ? e.reason : 'rejected';
      setSignedOut(reason === 'signed_out');
      setError(t(`stepUpFailed_${reason}`));
    } finally { setBusy(false); }
  };
  const actions = mode === 'enrol'
    ? <><button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={busy} aria-busy={busy} onClick={() => void run(enrolPasskey)}>{t('addPasskey')}</button></>
    : useCode
      ? <><button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={busy || !/^\d{6}$/.test(code.trim())} aria-busy={busy} onClick={() => void run(() => stepUpWithCode(code))}>{t('confirmCode')}</button></>
      : <><button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={busy} aria-busy={busy} onClick={() => void run(stepUpWithPasskey)}>{t('usePasskey')}</button></>;
  return (
    <Dialog open onClose={onClose} title={mode === 'enrol' ? t('enrolTitle') : t('stepUpTitle')} actions={actions}>
      <p>{mode === 'enrol' ? t('enrolBody') : t('stepUpBody')}</p>
      {mode === 'required' && !useCode ? <button type="button" className="btn btn-ghost" onClick={() => setUseCode(true)}>{t('useCode')}</button> : null}
      {mode === 'required' && useCode ? (
        <Field label={t('code')} error={error && !signedOut ? error : undefined}>
          <TextInput value={code} onChange={e => setCode(codeValue(e.target.value))} inputMode="numeric" autoComplete="one-time-code" />
        </Field>
      ) : null}
      {error && (signedOut || !useCode) ? <p className="cart-field-error" role="alert">{error}{signedOut ? <> <a href={signInHref('/cart')}>{t('signInToPay')}</a></> : null}</p> : null}
    </Dialog>
  );
}
