import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Dialog, Field, Segmented, TextArea, useFormatters } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { afterSaleQuery, STAFF_CANCEL_REASONS, useStaffCancel, type AfterSale } from './api';
import { useOrdersT, type OrdersKey } from './messages';

/** An order in one of these states can't be cancelled by staff any more. */
export const CLOSED = ['cancelled', 'refunded', 'returned', 'delivered', 'completed'];

/**
 * S-151 (S-177 on the order page): an order's cancellations, failed delivery and returns — the page's history tab. The
 * staff cancel is a dialog from the page's action panel (`StaffCancelDialog`).
 */
export function AfterSalePanel({ orderId }: { orderId: string }) {
  const t = useOrdersT();
  const q = useQuery(afterSaleQuery(orderId));
  if (q.isPending) return null;
  if (q.isError) return <p className="nl-or-note">{t('afterSaleError')}</p>;
  return <History a={q.data} />;
}

function History({ a }: { a: AfterSale }) {
  const t = useOrdersT();
  const fmt = useFormatters();
  const items: string[] = [
    ...a.cancellations.map(c => t('as_cancelled', {
      when: fmt.date(c.at, 'dateTime'), by: t(`by_${c.by}` as OrdersKey),
      shop: c.merchantName && !c.whole ? t('as_shop', { shop: c.merchantName }) : '', reason: c.reason.replaceAll('_', ' '),
    })),
    ...(a.deliveryFailure ? [
      t('as_failed', { when: fmt.date(a.deliveryFailure.failedAt, 'dateTime'), reason: t(`fr_${a.deliveryFailure.reason}` as OrdersKey), n: a.deliveryFailure.attempts }),
      a.deliveryFailure.choice
        ? t('as_failedChoice', { choice: t(`ch_${a.deliveryFailure.choice}` as OrdersKey) })
        : t('as_failedWaiting', { when: fmt.date(a.deliveryFailure.decideBy, 'dateTime') }),
    ] : []),
    ...a.returns.map(r => t('as_return', { ref: r.ref, shop: r.merchantName ?? '—', state: t(`rs_${r.state}` as OrdersKey), amount: fmt.price(r.amountCents) })),
  ];
  return items.length ? <ul className="nl-or-list">{items.map(i => <li key={i}>{i}</li>)}</ul> : <p className="nl-or-note">{t('as_none')}</p>;
}

/** Cancelling an order (role `support`): a reason, an internal note, restock — destructive, so the red button. */
export function StaffCancelDialog({ orderId, onClose }: { orderId: string; onClose: () => void }) {
  const t = useOrdersT();
  const cancel = useStaffCancel(orderId);
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [restock, setRestock] = useState(true);
  const [error, setError] = useState<string>();
  const submit = () => {
    if (!reason) { setError(t('chooseReason')); return; }
    setError(undefined);
    cancel.mutate({ reason, restock, ...(note.trim() ? { note: note.trim() } : {}) }, {
      onError: e => setError(e instanceof ValidationError ? (e.errors[0]?.message ?? t('cancelError')) : e instanceof ApiError && e.message ? e.message : t('cancelError')),
    });
  };
  return (
    <Dialog open onClose={onClose} title={t('cancelTitle')} actions={cancel.isSuccess
      ? <Button onClick={onClose}>{t('close')}</Button>
      : <><Button variant="secondary" onClick={onClose}>{t('keep')}</Button>
        <Button variant="destructive" disabled={cancel.isPending} aria-busy={cancel.isPending} onClick={submit}>{cancel.isPending ? t('cancelling') : t('confirmCancel')}</Button></>}>
      {cancel.isSuccess ? <p className="nl-or-note" role="status">{t('cancelDone')}</p> : <>
        <p className="nl-or-note">{t('cancelLede')}</p>
        <Segmented name={`staff-cancel-${orderId}`} aria-label={t('cancelReason')} value={reason}
          options={STAFF_CANCEL_REASONS.map(r => ({ value: r, label: t(`sr_${r}` as OrdersKey) }))} onChange={setReason} />
        <Field label={t('cancelNote')}>
          <TextArea value={note} rows={2} maxLength={500} onChange={e => setNote(e.target.value)} />
        </Field>
        <Checkbox checked={restock} onChange={setRestock} label={t('restock')} />
        {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
      </>}
    </Dialog>
  );
}
