import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Button, Chip, ErrorState, Field, formatMoney, PageSkeleton, Tag, TextArea, TextInput, useLocale } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import { PlaceFilters, type PlaceFilter } from '../shell/PlaceFilters';
import { SCREEN_PATH } from '../shell/screens';
import { caseQuery, disputesQuery, evidenceUrl, OUTCOMES, useCosign, useDecideCase, type Detail, type Item, type Outcome } from './api';
import { useDisputesT } from './messages';
import '../shell/queues.css';
import './disputes.css';

export interface DisputesSearch extends PlaceFilter { case?: string }

/**
 * Disputes & refunds (S-80, design 03 `disputes`): disputes with an agent and refund cases escalated after the
 * seller's 24 h, each with both parties' statements and the evidence; the agent decides (role `decide`) and a decision
 * returning more than $500 waits for a finance co-sign (role `refund`, another person). Admin, trust & safety,
 * finance and support open it.
 */
export function Disputes() {
  const t = useDisputesT();
  const search = useSearch({ strict: false }) as DisputesSearch;
  const filter = { province: search.province, market: search.market };
  const navigate = useNavigate();
  const query = useQuery(disputesQuery(filter));
  const age = useAge();
  const { locale } = useLocale();
  const header = <div className="nl-q-top"><span className="nl-q-kicker">{t('kicker')}</span><PlaceFilters to={SCREEN_PATH.disputes} filter={filter} /></div>;
  if (query.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (query.isError && !query.data) {
    return <div>{header}{query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  const selected = data.items.find(i => `${i.row.kind}:${i.row.id}` === search.case) ?? data.items[0];
  const open = (i: Item) => void navigate({ to: SCREEN_PATH.disputes, search: { ...filter, case: `${i.row.kind}:${i.row.id}` } as never });
  const s = data.summary;
  return (
    <div>
      {header}
      <h1 className="nl-q-title">{t('title', { agent: s.forAgent, window: s.inSellerWindow, closed: s.closedThisWeek })}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      {data.items.length === 0 ? <p className="nl-q-note">{t('empty')}</p> : (
        <div className="nl-ds-cols">
          <ul className="nl-ds-list" aria-label={t('listLabel')}>
            {data.items.map(i => (
              <li key={`${i.row.kind}:${i.row.id}`}>
                <button type="button" className="nl-ds-case" aria-current={i === selected ? 'true' : undefined} onClick={() => open(i)}>
                  <span className="nl-ds-case-head"><strong>{i.row.caseNumber}</strong><span>{formatMoney(i.row.amountCents, locale, { whole: i.row.amountCents % 100 === 0 })}</span></span>
                  <span className="nl-ds-case-title">{i.row.subject}</span>
                  <span className="nl-ds-case-age">{t('opened', { age: age(i.row.openedAt) })}</span>
                </button>
              </li>
            ))}
          </ul>
          {selected ? <CasePanel key={`${selected.row.kind}:${selected.row.id}`} item={selected} /> : <p>{t('pick')}</p>}
        </div>
      )}
    </div>
  );
}

function CasePanel({ item }: { item: Item }) {
  const t = useDisputesT();
  const { locale } = useLocale();
  const age = useAge();
  const { can, roleName } = useGrant();
  const detail = useQuery(caseQuery(item.row.kind, item.row.id));
  const decide = useDecideCase();
  const cosign = useCosign();
  const row = item.row;
  const outcomes = row.kind === 'refund' ? (['full_refund', 'release'] as Outcome[]) : [...OUTCOMES];
  const half = Math.round(row.amountCents / 2);
  const [outcome, setOutcome] = useState<Outcome>(row.kind === 'refund' ? 'full_refund' : 'partial');
  const [amount, setAmount] = useState((half / 100).toFixed(2));
  const [note, setNote] = useState('');
  const [cosignNote, setCosignNote] = useState('');
  const money = (c: number) => formatMoney(c, locale);
  const errors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const refusal = [decide.error, cosign.error].find(e => e && !(e instanceof ValidationError)) as ApiError | Error | undefined;
  const d: Detail | undefined = detail.data;
  const withAmount = outcome === 'partial' || outcome === 'goodwill_credit';
  const decided = row.state === 'decided' || ['approved', 'denied', 'paid'].includes(row.state);
  const submit = () => decide.mutate({
    kind: row.kind, id: row.id, outcome, note: note || undefined,
    refundCents: withAmount ? Math.round(Number(amount.replace(',', '.')) * 100) : undefined,
  });
  return (
    <section aria-labelledby="nl-ds-title">
      <h2 id="nl-ds-title" className="nl-q-h2">{row.caseNumber} · {row.subject}</h2>
      <div className="nl-q-note">{t('meta', { customer: row.customerName ?? '—', seller: item.businessName, amount: money(row.amountCents), age: age(row.openedAt) })}</div>
      <div className="nl-ds-parties">
        <div className="nl-q-panel"><strong>{t('customer')}</strong><br />{row.customerName ?? '—'} · “{row.customerStatement ?? t('noStatement')}”
          {d ? <div className="nl-q-chips nl-ds-tags"><Tag tone="neutral">{t('priorDisputes', { n: d.detail.customerPriorDisputes })}</Tag></div> : null}
        </div>
        <div className="nl-q-panel"><strong>{t('seller')}</strong><br />{item.businessName} · “{row.sellerStatement ?? t('noStatement')}”
          {d ? <div className="nl-q-chips nl-ds-tags">
            {d.sellerQuality != null ? <Tag tone="neutral">{t('quality', { score: d.sellerQuality })}</Tag> : null}
            <Tag tone="neutral">{t('sellerPrior', { n: d.detail.sellerPriorDisputes, won: d.detail.sellerPriorWon })}</Tag>
          </div> : null}
        </div>
      </div>
      <p className="nl-ds-evidence"><strong>{t('evidence')}</strong>{' '}
        {d && d.detail.evidence.length
          ? d.detail.evidence.map((e, i) => <span key={e.id}>{i ? ', ' : ''}{e.file ? <a href={evidenceUrl(row.id, e.id)} target="_blank" rel="noreferrer">{e.name}</a> : e.name}</span>)
          : t('noEvidence')}
      </p>
      {row.pending ? (
        <div className="nl-q-panel" role="status">
          <strong>{t('awaiting', { outcome: t(`o_${row.pending.outcome}`), amount: money(row.pending.refundCents) })}</strong>
          <div className="nl-q-note">{t('awaitingNote')}</div>
          {row.pending.note ? <p>{row.pending.note}</p> : null}
          {can('refund') ? <>
            <Field label={t('cosignNote')}><TextArea value={cosignNote} onChange={e => setCosignNote(e.target.value)} /></Field>
            <div className="nl-q-actions">
              <Button disabled={cosign.isPending} onClick={() => cosign.mutate({ decisionId: row.pending!.id, decision: 'approve', note: cosignNote || undefined })}>{t('cosign')}</Button>
              <Button variant="secondary" disabled={cosign.isPending} onClick={() => cosign.mutate({ decisionId: row.pending!.id, decision: 'decline', note: cosignNote || undefined })}>{t('decline')}</Button>
            </div>
          </> : null}
        </div>
      ) : decided ? (
        <div className="nl-q-actions"><Button disabled>{t('decided')}</Button><span className="nl-q-note">{t('decidedNote')}</span></div>
      ) : (
        <>
          <div className="nl-q-chips nl-ds-outcomes" role="group" aria-label={t('outcome')}>
            {outcomes.map(o => <Chip key={o} selected={outcome === o} onClick={() => setOutcome(o)}>{t(`o_${o}`)}</Chip>)}
          </div>
          {withAmount ? (
            <Field label={t('amount')} error={errors.refundCents}>
              <TextInput inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} />
            </Field>
          ) : null}
          <TextArea className="nl-ds-note" aria-label={t('notePlaceholder')} placeholder={t('notePlaceholder')} value={note} maxLength={1000} onChange={e => setNote(e.target.value)} />
          {errors.outcome ? <p role="alert" className="nl-q-error">{errors.outcome}</p> : null}
          <div className="nl-q-actions">
            <Button disabled={!can('decide') || decide.isPending} onClick={submit}>{t('decide', { outcome: t(`o_${outcome}`) })}</Button>
            <span className="nl-q-note">{can('decide') ? t('decideNote') : t('viewOnly', { role: roleName })}</span>
          </div>
        </>
      )}
      {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
    </section>
  );
}
