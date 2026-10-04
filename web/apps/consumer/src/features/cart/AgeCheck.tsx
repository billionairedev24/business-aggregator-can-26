import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { IdentificationCard } from '@phosphor-icons/react';
import { Alert, Button } from '@northline/ui';
import { problemCode } from './api';
import { ageStatusQuery, leave, startAgeCheck, type CheckoutAge } from './age';
import { useAgeT } from './ageMessages';

type AgeT = ReturnType<typeof useAgeT>;
type ClassKey = 'cls_alcohol' | 'cls_tobacco' | 'cls_cannabis';
type ErrKey = 'err_document_expired' | 'err_document_unverified_other' | 'err_selfie_face_mismatch' | 'err_consent_declined' | 'err_canceled' | 'err_other';

export function classList(t: AgeT, classes: readonly string[]): string {
  return classes.map(c => t(`cls_${c}` as ClassKey)).join(t('and'));
}

const reason = (t: AgeT, code: string | null | undefined): string => {
  const key = `err_${code ?? 'other'}` as ErrKey;
  const text = t(key);
  return text === key ? t('err_other') : text;
};

/**
 * Checkout's age step (2026-10-04): shown only when the cart has age-restricted items. The customer verifies once with
 * the identity provider's hosted flow (photo ID + selfie); the provider sends them back here (`?age=done`) and the
 * checkout is asked again. Pay stays off until the customer is verified old enough for the strictest item.
 */
export function AgeCheck({ age, onChanged, returnTo = 'web' }: { age: CheckoutAge; onChanged: () => void; returnTo?: 'web' | 'web_food' }) {
  const t = useAgeT();
  const qc = useQueryClient();
  const status = useQuery({ ...ageStatusQuery, enabled: age.required && age.state === 'pending', refetchInterval: age.state === 'pending' ? 5_000 : false });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  // back from the provider, or a webhook landed: ask the checkout again
  useEffect(() => { if (status.data && status.data.state !== 'pending') onChanged(); }, [status.data?.state]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!age.required) return null;
  const classes = classList(t, age.classes);
  const verify = async () => {
    setBusy(true);
    setError(undefined);
    try {
      const started = await startAgeCheck(returnTo);
      leave.to(started.url);
    } catch (e) {
      setError(problemCode(e) === 'age_check_attempts' ? t('err_attempts') : t('err_start'));
      setBusy(false);
    }
  };
  const done = age.state === 'verified';
  return (
    <section className="cart-section cart-age" aria-labelledby="cart-age">
      <h2 id="cart-age" className="cart-h2"><IdentificationCard weight="duotone" aria-hidden="true" /> {done ? t('titleDone') : t('title')}</h2>
      {done ? (
        <p className="cart-muted">{age.minimumAge > 0 ? t('verified', { age: age.minimumAge }) : t('verifiedNoAge')}</p>
      ) : (
        <>
          <p>{age.minimumAge > 0 ? t('why', { classes, age: age.minimumAge }) : t('whyNoAge', { classes })}</p>
          {age.state === 'under_age' ? <Alert tone="error" role="alert">{t('underAge', { age: age.minimumAge })}</Alert> : null}
          {age.state === 'failed' ? <Alert tone="error" role="alert">{t('failed', { reason: reason(t, status.data?.lastError) })}</Alert> : null}
          {age.state === 'pending' ? (
            <p role="status" className="cart-muted">{t('pending')} <Button variant="ghost" onClick={() => { void qc.invalidateQueries({ queryKey: ageStatusQuery.queryKey }); onChanged(); }}>{t('checkAgain')}</Button></p>
          ) : null}
          {age.state !== 'under_age' && age.state !== 'pending' ? (
            <>
              <p className="cart-note">{t('how')}</p>
              <Button onClick={() => void verify()} disabled={busy} aria-busy={busy}>{busy ? t('starting') : t('verify')}</Button>
            </>
          ) : null}
          {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
        </>
      )}
      <p className="cart-note">{t('door')}</p>
    </section>
  );
}
