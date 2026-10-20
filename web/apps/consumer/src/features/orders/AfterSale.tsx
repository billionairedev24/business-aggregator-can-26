import { useRef, useState } from 'react';
import { Alert, Button, Chip, Field, TextArea, useFormatters } from '@northline/ui';
import { ApiError, ValidationError } from '@northline/client';
import {
  useCancelOrder, useFailureChoice, useRequestReturn, useReturnAction,
  type OrderTracking, type Returnable, type ReturnView,
} from './api';
import { useAfterSaleT, type AfterSaleT } from './afterSaleMessages';

type K = Parameters<AfterSaleT>[0];

const CANCEL_REASONS = ['changed_mind', 'ordered_by_mistake', 'delivery_too_late', 'found_elsewhere', 'other'] as const;
const RETURN_REASONS = ['damaged', 'wrong_item', 'not_as_described', 'missing_parts', 'no_longer_needed', 'other'] as const;
const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_PHOTO = 5 * 1024 * 1024;

/** The server's words for a refusal (409 detail or the first 422 message), else ours. */
function problem(e: unknown, fallback: string): string {
  if (e instanceof ValidationError) return e.errors[0]?.message ?? fallback;
  if (e instanceof ApiError && e.message) return e.message;
  return fallback;
}

/**
 * S-151: "Cancel order" while no shop has packed it (the api says so: `canCancel`) — a reason, an optional note, then
 * the order closes and the card holds are released.
 */
export function CancelOrder({ order }: { order: OrderTracking }) {
  const t = useAfterSaleT();
  const cancel = useCancelOrder(order.orderId);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<string>();
  const [note, setNote] = useState('');
  const [error, setError] = useState<string>();
  if (!order.canCancel) return null;
  if (!open) return <Button type="button" variant="secondary" onClick={() => setOpen(true)}>{t('cancelOrder')}</Button>;
  const submit = () => {
    if (!reason) { setError(t('chooseReason')); return; }
    setError(undefined);
    cancel.mutate({ reason, ...(note.trim() ? { note: note.trim() } : {}) }, { onError: e => setError(problem(e, t('chooseReason'))) });
  };
  return (
    <section className="order-card order-aftersale" aria-labelledby="order-cancel">
      <h2 id="order-cancel" className="order-h2">{t('cancelTitle')}</h2>
      <p className="order-note">{t('cancelLede')}</p>
      <fieldset className="order-fieldset">
        <legend>{t('cancelReason')}</legend>
        <div className="nl-chips">
          {CANCEL_REASONS.map(r => <Chip key={r} selected={reason === r} onClick={() => setReason(r)}>{t(`cr_${r}` as K)}</Chip>)}
        </div>
      </fieldset>
      <Field label={t('noteLabel')}>
        <TextArea value={note} rows={2} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      {error ? <p className="order-error" role="alert">{error}</p> : null}
      <div className="order-actions">
        <Button type="button" disabled={cancel.isPending} aria-busy={cancel.isPending} onClick={submit}>{cancel.isPending ? t('cancelling') : t('confirmCancel')}</Button>
        <Button type="button" variant="ghost" onClick={() => setOpen(false)}>{t('keepOrder')}</Button>
      </div>
    </section>
  );
}

/** S-151: what was cancelled, in the customer's words. */
export function Cancellations({ order }: { order: OrderTracking }) {
  const t = useAfterSaleT();
  const list = order.cancellations ?? [];
  if (!list.length) return null;
  return (
    <>
      {list.map(c => {
        const text = c.reason === 'delivery_failed'
          ? (c.by === 'customer' ? t('refundedAfterMiss') : t('cancelledBy_system'))
          : c.by === 'customer' ? t('cancelledWhole')
            : c.by === 'merchant' ? (c.whole && !c.merchantName ? t('cancelledByWhole_merchant') : t('cancelledBy_merchant', { shop: c.merchantName ?? '' }))
              : t('cancelledBy_staff');
        return <p key={c.at + c.by} className="order-note">{text}</p>;
      })}
    </>
  );
}

/**
 * S-151: the courier couldn't hand the order over — why, until when the customer chooses, and the two choices:
 * re-delivery on the next run or a refund (less the configured fee, when there is one).
 */
export function FailedDelivery({ order }: { order: OrderTracking }) {
  const t = useAfterSaleT();
  const { money, date } = useFormatters();
  const choose = useFailureChoice(order.orderId);
  const [error, setError] = useState<string>();
  const f = order.deliveryFailure;
  if (!f) return null;
  if (f.choice === 'redeliver') return <p className="order-note">{t('chosenRedeliver')}</p>;
  if (f.choice) return null; // refunded: the cancellation line says so
  const pick = (choice: 'redeliver' | 'refund') => {
    setError(undefined);
    choose.mutate(choice, { onError: e => setError(problem(e, t('choiceError'))) });
  };
  return (
    <section className="order-card order-aftersale order-failed" aria-labelledby="order-failed">
      <h2 id="order-failed" className="order-h2">{t('failedChoose')}</h2>
      <p className="order-note">{t(`fr_${f.reason}` as K)}</p>
      <p className="order-note">{t('failedBack', { date: date(f.decideBy, 'dateTime') })}</p>
      {f.feeCents > 0 ? <p className="order-note">{t('failedFee', { fee: money(f.feeCents) })}</p> : null}
      {error ? <p className="order-error" role="alert">{error}</p> : null}
      <div className="order-actions">
        <Button type="button" disabled={choose.isPending} aria-busy={choose.isPending} onClick={() => pick('redeliver')}>{t('redeliver')}</Button>
        <Button type="button" variant="secondary" disabled={choose.isPending} onClick={() => pick('refund')}>{t('refundIt')}</Button>
      </div>
    </section>
  );
}

/** S-151: the order's returns, their state, and the return form while something is still returnable. */
export function Returns({ order }: { order: OrderTracking }) {
  const t = useAfterSaleT();
  const returns = order.returns ?? [];
  const returnable = order.returnable ?? [];
  if (!returns.length && !returnable.length) return null;
  return (
    <section className="order-card order-aftersale" aria-labelledby="order-returns">
      <h2 id="order-returns" className="order-h2">{t('returnsTitle')}</h2>
      {returns.length ? <ul className="order-returns">{returns.map(r => <ReturnRow key={r.id} order={order} r={r} />)}</ul> : null}
      {returnable.length ? <ReturnForm order={order} lines={returnable} /> : null}
    </section>
  );
}

function stateText(t: AfterSaleT, r: ReturnView, money: (c: number) => string): string {
  const shop = r.merchantName ?? '';
  if (r.state === 'approved') return t(r.method === 'pickup' ? 'rs_approved_pickup' : 'rs_approved_dropoff', { shop });
  return t(`rs_${r.state}` as K, { shop, amount: money(r.amountCents) });
}

function ReturnRow({ order, r }: { order: OrderTracking; r: ReturnView }) {
  const t = useAfterSaleT();
  const { money } = useFormatters();
  const act = useReturnAction(order.orderId);
  const [statement, setStatement] = useState('');
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string>();
  const run = (a: Parameters<typeof act.mutate>[0]) => {
    setError(undefined);
    act.mutate(a, { onError: e => setError(problem(e, t('returnError'))) });
  };
  return (
    <li className="order-return">
      <div className="order-return-head">
        <strong>{r.ref}</strong> · {r.lines.map(l => (l.qty > 1 ? `${l.title} ×${l.qty}` : l.title)).join(', ')}
      </div>
      <p className="order-note">{stateText(t, r, money)}</p>
      {r.state === 'declined' && r.declineReason ? <p className="order-note">{t('declinedBecause', { reason: r.declineReason })}</p> : null}
      {error ? <p className="order-error" role="alert">{error}</p> : null}
      <div className="order-actions">
        {r.canWithdraw ? <Button type="button" variant="ghost" disabled={act.isPending} onClick={() => run({ returnId: r.id, action: 'withdraw' })}>{t('withdraw')}</Button> : null}
        {r.canEscalate && !asking ? <Button type="button" variant="secondary" onClick={() => setAsking(true)}>{t('escalate')}</Button> : null}
      </div>
      {asking ? (
        <div className="order-escalate">
          <Field label={t('statement')}>
            <TextArea value={statement} rows={3} maxLength={1000} onChange={e => setStatement(e.target.value)} />
          </Field>
          <Button type="button" disabled={act.isPending}
            onClick={() => (statement.trim() ? run({ returnId: r.id, action: 'escalate', statement: statement.trim() }) : setError(t('statementNeeded')))}>
            {t('sendToAgent')}
          </Button>
        </div>
      ) : null}
    </li>
  );
}

function ReturnForm({ order, lines }: { order: OrderTracking; lines: Returnable[] }) {
  const t = useAfterSaleT();
  const { money, date } = useFormatters();
  const request = useRequestReturn(order.orderId);
  const [open, setOpen] = useState(false);
  const [chosen, setChosen] = useState<string[]>([]);
  const [reason, setReason] = useState<string>();
  const [note, setNote] = useState('');
  const [photo, setPhoto] = useState<File>();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const file = useRef<HTMLInputElement>(null);
  if (!open) return <Button type="button" variant="secondary" onClick={() => setOpen(true)}>{t('returnItems')}</Button>;
  const toggle = (id: string) => setChosen(xs => (xs.includes(id) ? xs.filter(x => x !== id) : [...xs, id]));
  const submit = () => {
    const next: Record<string, string> = {};
    const shops = new Set(lines.filter(l => chosen.includes(l.lineId)).map(l => l.merchantId));
    if (!chosen.length) next.lineIds = t('chooseItems');
    else if (shops.size > 1) next.lineIds = t('oneShop');
    if (!reason) next.reason = t('chooseReason');
    if (!photo) next.photo = t('addPhoto');
    setErrors(next);
    if (Object.keys(next).length) return;
    request.mutate({ lineIds: chosen, reason: reason!, ...(note.trim() ? { note: note.trim() } : {}), photo }, {
      onSuccess: () => { setOpen(false); setChosen([]); setReason(undefined); setNote(''); setPhoto(undefined); },
      onError: e => setErrors(e instanceof ValidationError ? e.byField() : { _form: problem(e, t('returnError')) }),
    });
  };
  const pickPhoto = (f: File | undefined) => {
    if (!f) return;
    if (!PHOTO_TYPES.includes(f.type) || f.size > MAX_PHOTO) { setErrors(e => ({ ...e, photo: t('photoFile') })); return; }
    setPhoto(f);
    setErrors(({ photo: _, ...rest }) => rest);
  };
  return (
    <form className="order-return-form" onSubmit={e => { e.preventDefault(); submit(); }}>
      <p className="order-note">{t('returnLede')}</p>
      <fieldset className="order-fieldset">
        <legend>{t('returnItems')}</legend>
        {lines.map(l => (
          <label key={l.lineId} className="order-return-line">
            <input type="checkbox" checked={chosen.includes(l.lineId)} onChange={() => toggle(l.lineId)} />
            <span>{l.qty > 1 ? `${l.title} ×${l.qty}` : l.title} · {money(l.amountCents)}</span>
            <span className="order-note">{t('returnUntil', { date: date(l.returnUntil, 'date') })}</span>
          </label>
        ))}
        {errors.lineIds ? <p className="order-error" role="alert">{errors.lineIds}</p> : null}
      </fieldset>
      <fieldset className="order-fieldset">
        <legend>{t('returnReason')}</legend>
        <div className="nl-chips">
          {RETURN_REASONS.map(r => <Chip key={r} selected={reason === r} onClick={() => setReason(r)}>{t(`rr_${r}` as K)}</Chip>)}
        </div>
        {errors.reason ? <p className="order-error" role="alert">{errors.reason}</p> : null}
      </fieldset>
      <Field label={t('photoLabel')} hint={t('photoHint')} error={errors.photo}>
        <input ref={file} type="file" accept={PHOTO_TYPES.join(',')} onChange={e => pickPhoto(e.target.files?.[0])} />
      </Field>
      <Field label={t('noteLabel')} error={errors.note}>
        <TextArea value={note} rows={2} maxLength={1000} onChange={e => setNote(e.target.value)} />
      </Field>
      {errors._form ? <Alert tone="error" role="alert">{errors._form}</Alert> : null}
      <Button type="submit" disabled={request.isPending} aria-busy={request.isPending}>{request.isPending ? t('sending') : t('sendReturn')}</Button>
    </form>
  );
}
