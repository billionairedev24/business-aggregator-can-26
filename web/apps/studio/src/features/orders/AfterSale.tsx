import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Checkbox, DataTable, Drawer, ErrorState, FactList, Field, Segmented, SheetSection, StatusTag, TextArea, useFormatters, type DataTableColumn, type StatusTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import {
  SELLER_CANCEL_REASONS, returnPhotoUrl, returnsQuery, useCancelOrder, useReturnDecision,
  type Order, type SellerReturn,
} from './api';
import { useOrdersT } from './messages';

type T = ReturnType<typeof useOrdersT>;
type K = Parameters<T>[0];

/** The server's words for a refusal (409 detail or the first 422 message), else ours. */
function problem(e: unknown, fallback: string): string {
  if (e instanceof ValidationError) return e.errors[0]?.message ?? fallback;
  if (e instanceof ApiError && e.message) return e.message;
  return fallback;
}

/** Until the courier has the shop's bags; the api has the last word (409 `too_late`). */
export const sellerMayCancel = (o: Order) =>
  (o.status === 'to_pack' || o.status === 'awaiting_pickup') && !o.courierPickup?.pickedUpAt;

/** S-151: "Cancel order" in the order sheet — a reason, a note, restock (on by default). */
export function CancelOrderPanel({ merchantId, order, onDone }: { merchantId: string; order: Order; onDone: () => void }) {
  const t = useOrdersT();
  const cancel = useCancelOrder(merchantId);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<string>('');
  const [note, setNote] = useState('');
  const [restock, setRestock] = useState(true);
  const [error, setError] = useState<string>();
  if (!sellerMayCancel(order)) return null;
  if (!open) return <button type="button" className="btn btn-secondary" onClick={() => setOpen(true)}>{t('cancelOrder')}</button>;
  const submit = () => {
    if (!reason) { setError(t('chooseReason')); return; }
    setError(undefined);
    cancel.mutate({ orderId: order.id, reason, restock, ...(note.trim() ? { note: note.trim() } : {}) }, {
      onSuccess: onDone,
      onError: e => setError(problem(e, t('cancelError'))),
    });
  };
  return (
    <SheetSection title={t('cancelTitle')}>
      <p className="nl-orders-note">{t('cancelLede')}</p>
      <Segmented name={`cancel-${order.id}`} aria-label={t('cancelReason')} value={reason}
        options={SELLER_CANCEL_REASONS.map(r => ({ value: r, label: t(`cr_${r}` as K) }))} onChange={setReason} />
      <Field label={t('cancelNote')}>
        <TextArea value={note} rows={2} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      <Checkbox checked={restock} onChange={setRestock} label={t('restock')} />
      {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
      <div className="nl-orders-actions">
        <button type="button" className="btn btn-danger" disabled={cancel.isPending} aria-busy={cancel.isPending} onClick={submit}>
          {cancel.isPending ? t('cancelling') : t('confirmCancel')}
        </button>
        <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>{t('keepOrder')}</button>
      </div>
    </SheetSection>
  );
}

/** S-175: waiting on the shop is a warning, a refund done is success, a decline or an escalation is danger. */
export const returnTone = (s: SellerReturn['state']): StatusTone =>
  s === 'requested' || s === 'collected' ? 'warning' : s === 'refunded' || s === 'received' ? 'success' : s === 'declined' || s === 'escalated' ? 'danger' : s === 'approved' ? 'info' : 'neutral';

interface ReturnRow { id: string; ref: string; order: string; items: string; reason: string; amount: number; state: string; raw: SellerReturn['state'] }

/** S-151: the shop's returns — a table, and a sheet to approve (how it comes back, restock), decline or receive. */
export function ReturnsPanel({ merchantId, canDecide, roleName }: { merchantId: string; canDecide: boolean; roleName: string }) {
  const t = useOrdersT();
  const f = useFormatters();
  const q = useQuery(returnsQuery(merchantId));
  const [open, setOpen] = useState<string | null>(null);
  const rows = useMemo<ReturnRow[]>(() => (q.data ?? []).map(r => ({
    id: r.id, ref: r.ref, order: r.orderRef ?? r.orderId,
    items: r.lines.map(l => (l.qty > 1 ? `${l.title} ×${l.qty}` : l.title)).join(', '),
    reason: t(`rr_${r.reason}` as K), amount: r.amountCents, state: t(`rs_${r.state}` as K), raw: r.state,
  })), [q.data, t]);
  if (q.isPending) return null;
  if (q.isError) return <ErrorState message={t('returnsError')} onRetry={() => void q.refetch()} />;
  if (!q.data.length) return null;
  const columns: DataTableColumn<ReturnRow>[] = [
    { key: 'ref', label: t('retTitle', { ref: '' }).trim(), primary: true },
    { key: 'order', label: t('retOrder') },
    { key: 'items', label: t('retItems') },
    { key: 'reason', label: t('retReason') },
    { key: 'amount', label: t('retAmount'), type: 'money', format: v => f.price(v as number) },
    { key: 'state', label: t('colStatus'), type: 'tag', status: r => returnTone(r.raw) },
  ];
  const detail = q.data.find(r => r.id === open);
  return (
    <section className="nl-orders-returns" aria-labelledby="studio-returns">
      <h2 id="studio-returns" className="nl-orders-h2">{t('returnsTitle')}</h2>
      <p className="nl-orders-foot">{t('returnsLede')}</p>
      <DataTable<ReturnRow>
        entity={t('returnsTitle')} plural={t('returnsTitle')} roleName={roleName}
        columns={columns} rows={rows}
        can={{ create: false, update: false, delete: false, export: true }}
        onOpen={r => setOpen(r.id)} emptyText={t('returnsEmpty')}
      />
      <Drawer open={!!detail} onClose={() => setOpen(null)} title={detail ? t('retTitle', { ref: detail.ref }) : ''}>
        {detail ? <ReturnSheet merchantId={merchantId} r={detail} canDecide={canDecide} onDone={() => setOpen(null)} /> : null}
      </Drawer>
    </section>
  );
}

function ReturnSheet({ merchantId, r, canDecide, onDone }: { merchantId: string; r: SellerReturn; canDecide: boolean; onDone: () => void }) {
  const t = useOrdersT();
  const f = useFormatters();
  const decide = useReturnDecision(merchantId);
  const [method, setMethod] = useState<'pickup' | 'dropoff'>('pickup');
  const [restock, setRestock] = useState(false);
  const [declining, setDeclining] = useState(false);
  const [why, setWhy] = useState('');
  const [error, setError] = useState<string>();
  const run = (d: Parameters<typeof decide.mutate>[0]) => {
    setError(undefined);
    decide.mutate(d, { onSuccess: onDone, onError: e => setError(problem(e, t('decisionError'))) });
  };
  const receivable = r.state === 'collected' || (r.state === 'approved' && r.method === 'dropoff');
  return (
    <>
      <div className="nl-orders-status"><StatusTag tone={returnTone(r.state)}>{t(`rs_${r.state}` as K)}</StatusTag></div>
      <SheetSection title={t('retItems')}>
        <FactList rows={[
          [t('retOrder'), r.orderRef ?? r.orderId],
          [t('retItems'), r.lines.map(l => `${l.qty} × ${l.title}`).join(', ')],
          [t('retReason'), t(`rr_${r.reason}` as K)],
          r.note ? [t('retNote'), r.note] : null,
          [t('retAmount'), f.price(r.amountCents)],
          r.method ? [t('approveTitle'), t(`m_${r.method}` as K)] : null,
          r.declineReason ? [t('decline'), r.declineReason] : null,
        ]} />
      </SheetSection>
      {r.hasPhoto ? (
        <SheetSection title={t('retPhoto')}>
          <img className="nl-orders-return-photo" src={returnPhotoUrl(merchantId, r.id)} alt={t('retPhotoAlt')} />
        </SheetSection>
      ) : null}
      {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
      {canDecide && r.state === 'requested' ? (
        <SheetSection title={t('approveTitle')}>
          <Segmented name={`method-${r.id}`} aria-label={t('approveTitle')} value={method}
            options={[{ value: 'pickup', label: t('m_pickup') }, { value: 'dropoff', label: t('m_dropoff') }]} onChange={setMethod} />
          <Checkbox checked={restock} onChange={setRestock} label={t('restockReturn')} />
          <div className="nl-orders-actions">
            <button type="button" className="btn btn-primary" disabled={decide.isPending} onClick={() => run({ returnId: r.id, action: 'approve', method, restock })}>{t('approve')}</button>
            {!declining ? <button type="button" className="btn btn-secondary" onClick={() => setDeclining(true)}>{t('decline')}</button> : null}
          </div>
          {declining ? (
            <>
              <Field label={t('declineReason')}>
                <TextArea value={why} rows={2} maxLength={500} onChange={e => setWhy(e.target.value)} />
              </Field>
              <button type="button" className="btn btn-destructive" disabled={decide.isPending}
                onClick={() => (why.trim() ? run({ returnId: r.id, action: 'decline', reason: why.trim() }) : setError(t('declineNeeded')))}>
                {t('confirmDecline')}
              </button>
            </>
          ) : null}
        </SheetSection>
      ) : null}
      {canDecide && receivable ? (
        <div className="nl-orders-actions">
          <button type="button" className="btn btn-primary" disabled={decide.isPending} onClick={() => run({ returnId: r.id, action: 'received' })}>{t('received')}</button>
        </div>
      ) : null}
    </>
  );
}
