import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, Skeleton, TextInput, useFormatters } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { meQuery } from '../shell/api';
import { useGrant } from '../shell/grant';
import { useDecideRefund } from '../support/api';
import { refundRequestsQuery, type PendingRefund } from './api';
import { useFinanceT } from './messages';

/**
 * Support's refund requests waiting for finance (S-85; S-83 owns the requests and the decision). Approve or decline
 * needs `refund` (admin, finance), and never on a request one asked for oneself (the api answers 409 `request_self`).
 */
export function RefundRequests() {
  const t = useFinanceT();
  const query = useQuery(refundRequestsQuery);
  if (query.isPending) return <Skeleton height={80} />;
  if (query.isError) return <ErrorState message={t('rr_error')} onRetry={() => void query.refetch()} />;
  if (!query.data.length) return <p className="nl-fi-sub">{t('rr_none')}</p>;
  return <ul className="nl-fi-refunds">{query.data.map(p => <RefundRow key={p.request.id} item={p} />)}</ul>;
}

function RefundRow({ item: { request: r, ticket } }: { item: PendingRefund }) {
  const t = useFinanceT();
  const fmt = useFormatters();
  const age = useAge();
  const { can } = useGrant();
  const me = useQuery(meQuery).data?.userId;
  const decide = useDecideRefund();
  const [note, setNote] = useState('');
  const own = r.requestedBy === me;
  const error = decide.error instanceof ValidationError ? Object.values(decide.error.byField())[0]
    : decide.error instanceof ApiError ? decide.error.message : undefined;
  const send = (decision: 'approve' | 'decline') => decide.mutate({ requestId: r.id, decision, note: note.trim() || undefined });
  return (
    <li className="nl-fi-refund">
      <div>
        <strong>{t('rr_line', { code: ticket.code, requester: ticket.requesterName, amount: fmt.money(r.amountCents) })}</strong>
        <div className="nl-fi-sub">{ticket.subject}</div>
        <div className="nl-fi-sub">{t('rr_asked', { who: r.requestedByName ?? t('staff'), age: age(r.requestedAt) })}</div>
        {r.note ? <div className="nl-fi-note">{r.note}</div> : null}
      </div>
      {can('refund') ? (own ? <span className="nl-fi-sub">{t('rr_own')}</span> : (
        <div className="nl-fi-actions">
          <TextInput aria-label={t('decisionNote')} placeholder={t('decisionNote')} maxLength={500} value={note} onChange={e => setNote(e.target.value)} />
          <Button disabled={decide.isPending} onClick={() => send('approve')}>{t('approve')}</Button>
          <Button variant="secondary" disabled={decide.isPending} onClick={() => send('decline')}>{t('decline')}</Button>
        </div>
      )) : null}
      {error ? <p role="alert" className="nl-fi-error">{error}</p> : null}
    </li>
  );
}
