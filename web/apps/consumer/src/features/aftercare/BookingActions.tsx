import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, newIdempotencyKey } from '@northline/client';
import { Dialog, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { StepUpDialog } from '../cart/StepUpDialog';
import { StripeCard } from '../booking/StripeCard';
import {
  bookingQuery, cancelBooking, cancellationQuery, confirmBalance, payBalance, policyFee,
  type BalancePayment, type Confirmation,
} from '../booking/api';
import { ThreadPanel } from '../messages/ThreadPanel';
import { PhotoStrip } from '../photos/PhotoPicker';
import { percent } from '../services/format';
import { useAftercareT } from './messages';

const CANCELLABLE = ['requested', 'confirmed', 'en_route'];

/**
 * S-153 on the booking page: the late-cancellation terms the booking was made under and "Cancel booking" (what it
 * costs is asked first; the fee is kept from the hold, the rest released), a deposit quote's balance (held
 * automatically before the event; paid here when that failed), the messages with the provider and the job's photos.
 */
export function BookingActions({ booking: b }: { booking: Confirmation }) {
  const t = useAftercareT();
  const { date, money } = useFormatters();
  const { locale } = useLocale();
  const [cancelOpen, setCancelOpen] = useState(false);
  const [messagesOpen, setMessagesOpen] = useState(false);
  const state = b.state ?? 'confirmed';
  const fee = b.cancellation ? policyFee(b.cancellation, money, bps => percent(bps / 100, locale)) : null;
  return (
    <>
      <section className="nl-bkd-card nl-bkd-policy" aria-labelledby="bkd-cancel">
        <h2 id="bkd-cancel" className="nl-bkd-h2">{t('policyTitle')}</h2>
        {b.cancelled ? (
          <p className="nl-after-muted">{b.cancelled.feeCents + b.cancelled.feeTaxCents > 0
            ? t('cancelledFee', { time: date(b.cancelled.at, 'dateTime'), fee: money(b.cancelled.feeCents + b.cancelled.feeTaxCents) })
            : t('cancelled', { time: date(b.cancelled.at, 'dateTime') })}</p>
        ) : (
          <>
            <p className="nl-after-muted">{b.freeCancelUntil ? t('freeCancel', { time: date(b.freeCancelUntil, 'dateTime') }) : fee ? t('noFreeCancel') : t('freeAlways')}</p>
            {fee && b.freeCancelUntil ? <p className="nl-after-muted">{t('lateFee', { fee })}</p> : null}
            {CANCELLABLE.includes(state)
              ? <button type="button" className="btn btn-secondary" onClick={() => setCancelOpen(true)}>{t('cancel')}</button>
              : state !== 'cancelled' ? <p className="nl-after-muted">{t('cannotCancel')}</p> : null}
          </>
        )}
        <p className="nl-after-muted">{t('escrowNote')}</p>
      </section>
      {b.balance && b.balance.state !== 'none' ? <BalanceCard booking={b} /> : null}
      {b.photos && b.photos.length > 0 ? (
        <section className="nl-bkd-card" aria-labelledby="bkd-photos">
          <h2 id="bkd-photos" className="nl-bkd-h2">{t('photosTitle')}</h2>
          <PhotoStrip ids={b.photos.map(p => p.id)} />
        </section>
      ) : null}
      <section className="nl-bkd-card" aria-labelledby="bkd-messages">
        <h2 id="bkd-messages" className="nl-bkd-h2">{t('messageTitle')}</h2>
        <button type="button" className="btn btn-secondary" aria-expanded={messagesOpen} onClick={() => setMessagesOpen(o => !o)}>
          {messagesOpen ? t('hideMessages') : t('message', { name: b.providerName })}
        </button>
        {messagesOpen ? <ThreadPanel thread={{ refType: 'booking', refId: b.bookingId }} name={b.providerName} /> : null}
      </section>
      {cancelOpen ? <CancelDialog booking={b} onClose={() => setCancelOpen(false)} /> : null}
    </>
  );
}

function CancelDialog({ booking: b, onClose }: { booking: Confirmation; onClose: () => void }) {
  const t = useAftercareT();
  const { money } = useFormatters();
  const qc = useQueryClient();
  const terms = useQuery(cancellationQuery(b.bookingId));
  const key = useRef(newIdempotencyKey());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const confirm = async () => {
    setBusy(true); setError(undefined);
    try {
      const updated = await cancelBooking(b.bookingId, key.current);
      qc.setQueryData(bookingQuery(b.bookingId).queryKey, updated);
      void qc.invalidateQueries({ queryKey: ['me'] });
      onClose();
    } catch (e) {
      const code = e instanceof ApiError ? (e.body as { code?: string } | undefined)?.code : undefined;
      setError(code === 'job_state' ? t('cannotCancel') : t('cancelError'));
    } finally { setBusy(false); }
  };
  const d = terms.data;
  return (
    <Dialog open onClose={onClose} title={t('cancelTitle')} role="alertdialog" sheetOnPhone
      actions={<>
        <button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancelKeep')}</button>
        <button type="button" className="btn btn-danger" disabled={busy || !d?.cancellable} aria-busy={busy} onClick={() => void confirm()}>{busy ? t('cancelling') : t('cancelConfirm')}</button>
      </>}>
      {terms.isPending ? <Skeleton height={48} /> : !d ? <p className="nl-error" role="alert">{t('cancelError')}</p> : !d.cancellable ? <p>{t('cannotCancel')}</p> : d.late
        ? <p>{t('cancelLate', { hours: d.windowHours, fee: money(d.feeTotalCents), released: money(d.releasedCents) })}</p>
        : <p>{t('cancelFree', { amount: money(d.releasedCents) })}</p>}
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
    </Dialog>
  );
}

type PayPhase = { step: 'idle' } | { step: 'stepUp'; mode: 'required' | 'enrol' } | { step: 'card'; payment: BalancePayment };

function BalanceCard({ booking: b }: { booking: Confirmation }) {
  const t = useAftercareT();
  const { date, money } = useFormatters();
  const qc = useQueryClient();
  const balance = b.balance!;
  const amount = money(balance.cents + balance.taxCents);
  const [phase, setPhase] = useState<PayPhase>({ step: 'idle' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const key = useRef(newIdempotencyKey());
  const payable = (balance.state === 'failed' || (balance.state === 'pending' && balance.attempts > 0)) && b.state === 'confirmed';
  const done = (c: Confirmation) => { qc.setQueryData(bookingQuery(b.bookingId).queryKey, c); setPhase({ step: 'idle' }); };
  const finish = async () => {
    try { done(await confirmBalance(b.bookingId, `${key.current}-confirm`)); } catch { setError(t('payError')); }
  };
  const pay = async (stepUp?: string) => {
    setBusy(true); setError(undefined);
    try {
      const p = await payBalance(b.bookingId, key.current, { stepUp });
      if (p.status === 'requires_action' || p.status === 'requires_payment_method') {
        if (p.clientSecret && p.publishableKey) setPhase({ step: 'card', payment: p }); else setError(t('payError'));
      } else await finish();
    } catch (e) {
      const code = e instanceof ApiError ? (e.body as { code?: string } | undefined)?.code : undefined;
      if (code === 'step_up_required' || code === 'second_factor_required') setPhase({ step: 'stepUp', mode: code === 'step_up_required' ? 'required' : 'enrol' });
      else setError(t('payError'));
    } finally { setBusy(false); }
  };
  const line = balance.state === 'pending'
    ? balance.attempts > 0 && balance.nextAt ? t('balance_retry', { amount, time: date(balance.nextAt, 'dateTime') }) : t('balance_pending', { amount, time: balance.dueAt ? date(balance.dueAt, 'dateTime') : '' })
    : t(`balance_${balance.state}` as 'balance_held', { amount });
  return (
    <section className="nl-bkd-card" aria-labelledby="bkd-balance">
      <h2 id="bkd-balance" className="nl-bkd-h2">{t('balanceTitle')}</h2>
      <p className="nl-after-muted" role={balance.state === 'failed' ? 'alert' : undefined}>{line}</p>
      {payable && phase.step === 'card' ? (
        <StripeCard clientSecret={phase.payment.clientSecret!} publishableKey={phase.payment.publishableKey!} label={t('confirmCard', { amount })}
          onAuthorized={() => void finish()} onError={setError} />
      ) : payable ? (
        <button type="button" className="btn btn-primary" disabled={busy} aria-busy={busy} onClick={() => void pay()}>{busy ? t('paying') : t('payBalance', { amount })}</button>
      ) : null}
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
      {phase.step === 'stepUp' ? <StepUpDialog mode={phase.mode} onClose={() => setPhase({ step: 'idle' })} onProof={proof => { setPhase({ step: 'idle' }); void pay(proof); }} /> : null}
    </section>
  );
}
