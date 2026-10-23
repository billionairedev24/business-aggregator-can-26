import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Dialog, ErrorState, Skeleton, StatusTag, useFormatters } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { screenHref } from '../shell/nav';
import { customerPhotoUrl, openCustomerThread, quoteRequestsQuery, useDeclineRequest, type Quote, type QuoteRequest } from './api';
import { useAppointmentsT } from './messages';
import { QuoteComposer } from './QuoteComposer';

type T = ReturnType<typeof useAppointmentsT>;
const validShort = (h: number, t: T) => ([24, 72, 168, 336].includes(h) ? t(`validShort${h as 24 | 72 | 168 | 336}`) : `${h} h`);

export function QuoteRequests() {
  const t = useAppointmentsT();
  const merchantId = useMerchantId();
  const q = useQuery(quoteRequestsQuery(merchantId));
  const [composing, setComposing] = useState<{ id: string; revising?: Quote } | null>(null);
  const open = (q.data ?? []).filter(r => !r.quote || r.quote.state === 'draft').length;
  return (
    <section aria-labelledby="appt-requests" className="bo-card nl-appt-requests">
      <div><h2 id="appt-requests" className="bo-card-title">{t('requestsTitle', { n: open })}</h2>
        <p className="bo-muted nl-appt-note">{t('requestsNote')}</p></div>
      {q.isPending ? <div aria-busy="true" className="nl-appt-skel"><Skeleton height={80} /><Skeleton height={80} /></div>
        : q.isError ? <ErrorState message={t('loadRequestsError')} onRetry={() => void q.refetch()} />
        : q.data.length === 0 ? <p className="nl-muted">{t('noRequests')}</p>
        : q.data.map(r => (
          <RequestCard key={r.id} request={r} composing={composing?.id === r.id ? composing : null}
            onCompose={revising => setComposing({ id: r.id, revising })} onClose={() => setComposing(null)} />
        ))}
    </section>
  );
}

function RequestCard({ request: r, composing, onCompose, onClose }: { request: QuoteRequest; composing: { revising?: Quote } | null; onCompose: (revising?: Quote) => void; onClose: () => void }) {
  const t = useAppointmentsT();
  const f = useFormatters();
  const merchantId = useMerchantId();
  const role = useRole();
  const nav = useNavigate();
  const decline = useDeclineRequest(merchantId);
  const [asking, setAsking] = useState(false);
  const [askError, setAskError] = useState(false);
  const ask = async () => {
    setAsking(true); setAskError(false);
    try {
      const { threadId } = await openCustomerThread(merchantId, 'quote-requests', r.id);
      void nav({ to: screenHref(merchantId, 'messages'), search: { thread: threadId } as never });
    } catch { setAskError(true); } finally { setAsking(false); }
  };
  const [customerView, setCustomerView] = useState(false);
  const canEdit = role !== 'bookkeeper';
  const sent = r.quote && r.quote.state !== 'draft' ? r.quote : null;
  const who = [r.customerName, r.reliability != null ? t('reliability', { score: r.reliability.toFixed(1) }) : null, r.customerNoShows ? t('customerNoShows', { n: r.customerNoShows }) : null, r.area].filter(Boolean).join(' · ');
  return (
    <article className="nl-appt-request" aria-label={r.title}>
      <div className="nl-appt-request-head"><strong>{r.title}</strong><span className="nl-muted nl-small">{who}</span></div>
      {r.body ? <div className="nl-appt-body">“{r.body}”</div> : null}
      {r.photoIds?.length ? <CustomerPhotos merchantId={merchantId} ids={r.photoIds} /> : null}
      {askError ? <div role="alert" className="nl-error">{t('threadError')}</div> : null}
      {composing ? (
        <QuoteComposer request={r} revising={composing.revising} onDone={onClose} onCancel={onClose} />
      ) : sent ? (
        <div className="nl-appt-actions">
          <StatusTag tone={sent.state === 'accepted' ? 'success' : sent.state === 'declined' || sent.state === 'expired' ? 'danger' : 'info'}>{sent.version > 1
            ? t('revisedTag', { version: sent.version, total: f.price(sent.totalCents), lines: sent.lines.length, valid: validShort(sent.validHours, t) })
            : t('sentTag', { total: f.price(sent.totalCents), lines: sent.lines.length, valid: validShort(sent.validHours, t) })}</StatusTag>
          {sent.state !== 'sent' ? <StatusTag tone={sent.state === 'accepted' ? 'success' : sent.state === 'viewed' ? 'info' : 'danger'}>{t(`quoteState_${sent.state as 'viewed' | 'accepted' | 'declined' | 'expired'}`)}</StatusTag> : null}
          <button type="button" className="btn btn-tertiary nl-appt-small" onClick={() => setCustomerView(true)}>{t('viewAsCustomer')}</button>
          {canEdit && (sent.state === 'sent' || sent.state === 'viewed') ? <button type="button" className="btn btn-tertiary nl-appt-small" onClick={() => onCompose(sent)}>{t('revise')}</button> : null}
        </div>
      ) : canEdit ? (
        <div className="nl-appt-actions">
          <button type="button" className="btn btn-primary" onClick={() => onCompose()}>{r.quote ? t('continueDraft') : t('writeQuote')}</button>
          <button type="button" className="btn btn-secondary" disabled={asking} onClick={() => void ask()}>{t('askQuestion')}</button>
          <button type="button" className="btn btn-tertiary" disabled={decline.isPending} onClick={() => decline.mutate(r.id)}>{t('decline')}</button>
        </div>
      ) : null}
      {sent ? <CustomerView open={customerView} onClose={() => setCustomerView(false)} request={r} quote={sent} /> : null}
    </article>
  );
}

/** "View as customer": every line, totals, scope, exclusions, warranty, deposit and validity. */
export function CustomerView({ open, onClose, request, quote: q }: { open: boolean; onClose: () => void; request: QuoteRequest; quote: Quote }) {
  const t = useAppointmentsT();
  const f = useFormatters();
  return (
    <Dialog open={open} onClose={onClose} width={560} title={t('customerViewTitle', { title: request.title, ref: q.ref })}
      actions={<button type="button" className="btn btn-primary" onClick={onClose}>{t('close')}</button>}>
      <p className="nl-small nl-muted">{t('customerViewIntro', { name: request.customerName ?? '—' })}</p>
      <table className="nl-appt-cv">
        <thead><tr><th scope="col">{t('colItem')}</th><th scope="col">{t('colQty')}</th><th scope="col" className="nl-right">{t('colAmount')}</th></tr></thead>
        <tbody>{q.lines.map((l, i) => <tr key={i}><td>{l.description}<span className="nl-appt-cv-kind"> · {t(`kind_${l.kind}`)}</span></td><td>{l.qty}</td><td className="nl-right">{l.amountCents < 0 ? '−' : ''}{f.price(Math.abs(l.amountCents))}</td></tr>)}</tbody>
      </table>
      <dl className="nl-appt-totals">
        <dt>{t('totLabour')}</dt><dd>{f.price(q.labourCents)}</dd>
        <dt>{t('totParts')}</dt><dd>{f.price(q.partsCents)}</dd>
        <dt>{t('totFees')}</dt><dd>{f.price(q.feesCents)}</dd>
        {q.discountCents > 0 ? <><dt>{t('totDiscount')}</dt><dd>−{f.price(q.discountCents)}</dd></> : null}
        <dt>{t('totTax', { pct: q.taxBps / 100 })}</dt><dd>{f.price(q.taxCents)}</dd>
        <dt className="nl-appt-total">{t('totTotal')}</dt><dd className="nl-appt-total nl-appt-total-v">{f.price(q.totalCents)}</dd>
      </dl>
      <h3 className="nl-appt-cv-h">{t('scope')}</h3><p>{q.scope}</p>
      {q.exclusions ? <><h3 className="nl-appt-cv-h">{t('exclusions')}</h3><p>{q.exclusions}</p></> : null}
      <ul className="nl-appt-cv-facts">
        <li>{t('warranty')} · {t(`w_${q.warranty}`)}</li>
        <li>{q.depositKind === 'none' ? t('depositNoneLine') : t('depositLine', { money: f.price(q.depositCents) })}</li>
        {q.proposedAt ? <li>{t('proposed', { date: f.date(q.proposedAt, 'dateTime') })}{q.durationMin ? ` · ${t('durationShort', { min: q.durationMin })}` : ''}</li> : null}
        {q.validUntil ? <li>{t('validUntil', { date: f.date(q.validUntil, 'dateTime') })}</li> : null}
        {q.attachments.length ? <li>{t('attachments')} · {q.attachments.map(a => a.fileName).join(', ')}</li> : null}
      </ul>
    </Dialog>
  );
}

/** S-153: the customer's photos of the job (a quote request's or a booking's), only those the business may see. */
export function CustomerPhotos({ merchantId, ids }: { merchantId: string; ids: string[] }) {
  const t = useAppointmentsT();
  return (
    <ul className="nl-appt-photos" aria-label={t('customerPhotos')}>
      {ids.map((id, i) => (
        <li key={id}><a href={customerPhotoUrl(merchantId, id)} target="_blank" rel="noreferrer">
          <img src={customerPhotoUrl(merchantId, id)} alt={t('customerPhoto', { n: i + 1 })} width={72} height={72} loading="lazy" />
        </a></li>
      ))}
    </ul>
  );
}
