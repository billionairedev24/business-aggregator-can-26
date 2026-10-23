import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from '@tanstack/react-router';
import { ActionPanel, ErrorState, PageSkeleton, RecordHeader, RecordHistory, RecordLayout, RecordTabs, StatusTag, Tag, useFormatters } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useAge } from '../shell/age';
import { caseQuery, evidenceUrl, type Detail } from './api';
import { Decision, STATE_TONE, stateOf } from './Disputes';
import { useDisputesT } from './messages';
import '../shell/queues.css';
import './disputes.css';

/**
 * A dispute or refund case (S-80; E-15 S-177 as a page): the head with its state and key facts, both parties'
 * statements, the evidence and the case's history as tabs, and the decision in the right-hand action panel (the agent
 * decides, role `decide`; above $500 a finance co-sign, role `refund`, another person).
 */
export function DisputePage() {
  const t = useDisputesT();
  const { kind, caseId } = useParams({ strict: false }) as { kind: string; caseId: string };
  const query = useQuery(caseQuery(kind, caseId));
  const back = <div className="bo-rec-back"><Link to="/disputes">{t('back')}</Link></div>;
  if (query.isPending) return <div className="bo-page">{back}<PageSkeleton kpis={0} rows={6} /></div>;
  if (query.isError) {
    const missing = query.error instanceof ApiError && query.error.status === 404;
    return <div className="bo-page">{back}{missing ? <p role="alert">{t('notFound')}</p> : <ErrorState message={t('detailError')} onRetry={() => void query.refetch()} />}</div>;
  }
  return <CaseView d={query.data} />;
}

function CaseView({ d }: { d: Detail }) {
  const t = useDisputesT();
  const fmt = useFormatters();
  const age = useAge();
  const item = d.item;
  const row = item.row;
  const state = stateOf(row);
  const history = [
    { key: 'opened', at: row.openedAt, text: t('h_opened') },
    ...(row.pending ? [{ key: 'pending', at: row.pending.decidedAt, text: t('h_pending', { outcome: t(`o_${row.pending.outcome}`), amount: fmt.price(row.pending.refundCents) }) }] : []),
    ...(row.decision ? [{ key: 'decided', at: row.decision.decidedAt, text: t('h_decided', { outcome: t(`o_${row.decision.outcome}`), amount: fmt.price(row.decision.refundCents) }) }] : []),
    ...(row.decision?.cosignedAt ? [{ key: 'cosigned', at: row.decision.cosignedAt, text: t('h_cosigned') }] : []),
    ...d.detail.evidence.map(e => ({ key: e.id, at: e.at, text: `${t('secEvidence')} · ${e.name}` })),
  ].sort((a, b) => b.at.localeCompare(a.at));
  return (
    <div className="bo-page">
      <RecordHeader back={<Link to="/disputes">{t('back')}</Link>} title={`${row.caseNumber} · ${row.subject}`}
        status={<StatusTag tone={STATE_TONE[state]} size="lg">{t(`st_${state}`)}</StatusTag>}
        facts={[
          { label: t('f_kind'), value: t(row.kind === 'refund' ? 'kind_refund' : 'kind_dispute') },
          { label: t('f_parties'), value: t('parties', { customer: row.customerName ?? '—', seller: item.businessName }) },
          { label: t('f_amount'), value: fmt.price(row.amountCents) },
          { label: t('f_opened'), value: t('opened', { age: age(row.openedAt) }) },
        ]} />
      <RecordLayout asideLabel={t('secDecision')} aside={<ActionPanel title={t('secDecision')}><Decision key={`${row.kind}:${row.id}`} item={item} /></ActionPanel>}>
        <RecordTabs label={t('tabsRecord')} tabs={[
          { value: 'parties', label: t('secParties'), render: () => (
            <div className="nl-ds-parties">
              <div className="nl-ds-party"><strong>{t('customer')}</strong><span>{row.customerName ?? '—'} · “{row.customerStatement ?? t('noStatement')}”</span>
                <div className="nl-q-chips"><Tag tone="neutral">{t('priorDisputes', { n: d.detail.customerPriorDisputes })}</Tag></div>
              </div>
              <div className="nl-ds-party"><strong>{t('seller')}</strong><span>{item.businessName} · “{row.sellerStatement ?? t('noStatement')}”</span>
                <div className="nl-q-chips">
                  {d.sellerQuality != null ? <Tag tone="neutral">{t('quality', { score: d.sellerQuality })}</Tag> : null}
                  <Tag tone="neutral">{t('sellerPrior', { n: d.detail.sellerPriorDisputes, won: d.detail.sellerPriorWon })}</Tag>
                </div>
              </div>
            </div>) },
          { value: 'evidence', label: t('secEvidence'), count: d.detail.evidence.length, render: () => (d.detail.evidence.length
            ? <ul className="nl-ds-files">{d.detail.evidence.map(e => <li key={e.id}>{e.file ? <a className="nl-ds-file" href={evidenceUrl(row.id, e.id)} target="_blank" rel="noreferrer">{e.name}</a> : e.name}</li>)}</ul>
            : <p className="bo-muted">{t('noEvidence')}</p>) },
          { value: 'history', label: t('tabHistory'), count: history.length, render: () => <RecordHistory items={history.map(h => ({ key: h.key, when: fmt.date(h.at, 'dateTime'), text: h.text }))} /> },
        ]} />
        <p className="bo-muted">{t('lede')}</p>
      </RecordLayout>
    </div>
  );
}
