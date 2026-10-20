import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Field, Segmented, SheetSection, TextArea, useFormatters } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { afterSaleQuery, STAFF_CANCEL_REASONS, useStaffCancel, type AfterSale } from './api';
import { useOrdersT, type OrdersKey } from './messages';

const CLOSED = ['cancelled', 'refunded', 'returned', 'delivered', 'completed'];

/**
 * S-151: an order's cancellations, failed delivery and returns in the orders sheet, and — for a role with the support
 * action — cancelling it (a reason, an internal note, restock). The api refuses what the role may not do (403) and
 * what is too late (409); its words are shown.
 */
export function AfterSalePanel({ orderId }: { orderId: string }) {
  const t = useOrdersT();
  const { can } = useGrant();
  const q = useQuery(afterSaleQuery(orderId));
  if (q.isPending) return null;
  if (q.isError) return <SheetSection title={t('secAfterSale')}><p className="nl-or-note">{t('afterSaleError')}</p></SheetSection>;
  return (
    <>
      <History a={q.data} />
      {can('support') && !CLOSED.includes(q.data.state) ? <StaffCancel orderId={orderId} /> : null}
    </>
  );
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
    ...a.returns.map(r => t('as_return', { ref: r.ref, shop: r.merchantName ?? '—', state: t(`rs_${r.state}` as OrdersKey), amount: fmt.money(r.amountCents) })),
  ];
  return (
    <SheetSection title={t('secAfterSale')}>
      {items.length ? <ul className="nl-or-list">{items.map(i => <li key={i}>{i}</li>)}</ul> : <p className="nl-or-note">{t('as_none')}</p>}
    </SheetSection>
  );
}

function StaffCancel({ orderId }: { orderId: string }) {
  const t = useOrdersT();
  const cancel = useStaffCancel(orderId);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [restock, setRestock] = useState(true);
  const [error, setError] = useState<string>();
  if (cancel.isSuccess) return <p className="nl-or-note" role="status">{t('cancelDone')}</p>;
  if (!open) return <div><Button variant="secondary" onClick={() => setOpen(true)}>{t('cancelOrder')}</Button></div>;
  const submit = () => {
    if (!reason) { setError(t('chooseReason')); return; }
    setError(undefined);
    cancel.mutate({ reason, restock, ...(note.trim() ? { note: note.trim() } : {}) }, {
      onError: e => setError(e instanceof ValidationError ? (e.errors[0]?.message ?? t('cancelError')) : e instanceof ApiError && e.message ? e.message : t('cancelError')),
    });
  };
  return (
    <SheetSection title={t('cancelTitle')}>
      <p className="nl-or-note">{t('cancelLede')}</p>
      <Segmented name={`staff-cancel-${orderId}`} aria-label={t('cancelReason')} value={reason}
        options={STAFF_CANCEL_REASONS.map(r => ({ value: r, label: t(`sr_${r}` as OrdersKey) }))} onChange={setReason} />
      <Field label={t('cancelNote')}>
        <TextArea value={note} rows={2} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      <Checkbox checked={restock} onChange={setRestock} label={t('restock')} />
      {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
      <div className="nl-or-actions">
        <Button variant="danger" disabled={cancel.isPending} aria-busy={cancel.isPending} onClick={submit}>{cancel.isPending ? t('cancelling') : t('confirmCancel')}</Button>
        <Button variant="ghost" onClick={() => setOpen(false)}>{t('keep')}</Button>
      </div>
    </SheetSection>
  );
}
