import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import {
  BoHeader, Button, Chip, DataTable, Drawer, ErrorState, Field, formatMoney, formatNumber, KpiStrip, PageSkeleton, SheetSection, Tag, TextArea, TextInput, useLocale,
  type DataTableColumn, type DataTableTone,
} from '@northline/ui';
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

type State = 'agent' | 'cosign' | 'decided';
const isDecided = (row: Item['row']) => row.state === 'decided' || ['approved', 'denied', 'paid'].includes(row.state);
const stateOf = (row: Item['row']): State => (row.pending ? 'cosign' : isDecided(row) ? 'decided' : 'agent');
const TONE: Record<State, DataTableTone> = { agent: 'tag-highlight', cosign: 'tag-neutral', decided: 'tag-accent' };
const caseKey = (i: Item) => `${i.row.kind}:${i.row.id}`;

interface Row { id: string; ref: string; subject: string; parties: string; amount: number; opened: string; state: string; item: Item }

/**
 * Disputes & refunds (S-80; redesign § 2.3 → BO `disputes` table + the BO:811 sheet): disputes with an agent and refund
 * cases escalated after the seller's 24 h, under the KPI strip of the desk. A row opens the case in the right sheet
 * (`?case=kind:id`): both parties' statements, the evidence and the decision — the agent decides (role `decide`) and a
 * decision returning more than $500 waits for a finance co-sign (role `refund`, another person). Admin, trust &
 * safety, finance and support open it.
 */
export function Disputes() {
  const t = useDisputesT();
  const search = useSearch({ strict: false }) as DisputesSearch;
  const filter = { province: search.province, market: search.market };
  const navigate = useNavigate();
  const query = useQuery(disputesQuery(filter));
  const age = useAge();
  const { locale } = useLocale();
  const { roleName } = useGrant();
  const head = <BoHeader title={t('title')} sub={t('lede')} actions={<PlaceFilters to={SCREEN_PATH.disputes} filter={filter} />} />;
  if (query.isPending) return <div className="bo-page">{head}<PageSkeleton kpis={3} rows={4} /></div>;
  if (query.isError && !query.data) {
    return <div className="bo-page">{head}{query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  const selected = search.case ? data.items.find(i => caseKey(i) === search.case) : undefined;
  const select = (key: string | undefined) => void navigate({ to: SCREEN_PATH.disputes, search: { ...filter, case: key } as never });
  const s = data.summary;
  const rows: Row[] = data.items.map(i => ({
    id: caseKey(i), ref: i.row.caseNumber, subject: i.row.subject, parties: t('parties', { customer: i.row.customerName ?? '—', seller: i.businessName }),
    amount: i.row.amountCents, opened: t('opened', { age: age(i.row.openedAt) }), state: t(`st_${stateOf(i.row)}`), item: i,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'ref', label: t('c_case'), sub: 'subject', subLabel: t('c_subject'), primary: true },
    { key: 'parties', label: t('c_parties') },
    { key: 'amount', label: t('c_amount'), type: 'money' },
    { key: 'opened', label: t('c_opened') },
    { key: 'state', label: t('c_state'), type: 'tag', filter: 'facet' },
  ];
  return (
    <div className="bo-page">
      {head}
      <KpiStrip label={t('kpis')} items={[
        { key: 'agent', label: t('k_agent'), value: formatNumber(s.forAgent, locale) },
        { key: 'window', label: t('k_window'), value: formatNumber(s.inSellerWindow, locale) },
        { key: 'closed', label: t('k_closed'), value: formatNumber(s.closedThisWeek, locale) },
      ]} />
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={r => ({ state: TONE[stateOf(r.item.row)] })}
        can={{ create: false, update: false, delete: false }} roleName={roleName} emptyText={t('empty')} openLabel={t('open')} onOpen={r => select(r.id)} reportName="disputes" />
      {selected ? (
        <Drawer open onClose={() => select(undefined)} title={`${selected.row.caseNumber} · ${selected.row.subject}`}>
          <CaseBody key={caseKey(selected)} item={selected} />
        </Drawer>
      ) : null}
    </div>
  );
}

function CaseBody({ item }: { item: Item }) {
  const t = useDisputesT();
  const { locale } = useLocale();
  const age = useAge();
  const detail = useQuery(caseQuery(item.row.kind, item.row.id));
  const row = item.row;
  const money = (c: number) => formatMoney(c, locale);
  const d: Detail | undefined = detail.data;
  return (
    <>
      <p className="bo-muted">{t('meta', { customer: row.customerName ?? '—', seller: item.businessName, amount: money(row.amountCents), age: age(row.openedAt) })}</p>
      <SheetSection title={t('secParties')}>
        <div className="nl-ds-parties">
          <div className="nl-ds-party"><strong>{t('customer')}</strong><span>{row.customerName ?? '—'} · “{row.customerStatement ?? t('noStatement')}”</span>
            {d ? <div className="nl-q-chips"><Tag tone="neutral">{t('priorDisputes', { n: d.detail.customerPriorDisputes })}</Tag></div> : null}
          </div>
          <div className="nl-ds-party"><strong>{t('seller')}</strong><span>{item.businessName} · “{row.sellerStatement ?? t('noStatement')}”</span>
            {d ? <div className="nl-q-chips">
              {d.sellerQuality != null ? <Tag tone="neutral">{t('quality', { score: d.sellerQuality })}</Tag> : null}
              <Tag tone="neutral">{t('sellerPrior', { n: d.detail.sellerPriorDisputes, won: d.detail.sellerPriorWon })}</Tag>
            </div> : null}
          </div>
        </div>
      </SheetSection>
      <SheetSection title={t('secEvidence')}>
        {d && d.detail.evidence.length
          ? <ul className="nl-ds-files">{d.detail.evidence.map(e => <li key={e.id}>{e.file ? <a className="nl-ds-file" href={evidenceUrl(row.id, e.id)} target="_blank" rel="noreferrer">{e.name}</a> : e.name}</li>)}</ul>
          : <p className="bo-muted">{t('noEvidence')}</p>}
      </SheetSection>
      <SheetSection title={t('secDecision')}><Decision item={item} /></SheetSection>
    </>
  );
}

/** The agent's decision, the co-sign above $500, or the decided state. */
function Decision({ item }: { item: Item }) {
  const t = useDisputesT();
  const { locale } = useLocale();
  const { can, roleName } = useGrant();
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
  const withAmount = outcome === 'partial' || outcome === 'goodwill_credit';
  const submit = () => decide.mutate({
    kind: row.kind, id: row.id, outcome, note: note || undefined,
    refundCents: withAmount ? Math.round(Number(amount.replace(',', '.')) * 100) : undefined,
  });
  return (
    <>
      {row.pending ? (
        <div className="nl-ds-pending" role="status">
          <strong>{t('awaiting', { outcome: t(`o_${row.pending.outcome}`), amount: money(row.pending.refundCents) })}</strong>
          <span className="nl-q-note">{t('awaitingNote')}</span>
          {row.pending.note ? <p className="nl-ds-quote">{row.pending.note}</p> : null}
          {can('refund') ? <>
            <Field label={t('cosignNote')}><TextArea value={cosignNote} onChange={e => setCosignNote(e.target.value)} /></Field>
            <div className="nl-q-actions">
              <Button disabled={cosign.isPending} onClick={() => cosign.mutate({ decisionId: row.pending!.id, decision: 'approve', note: cosignNote || undefined })}>{t('cosign')}</Button>
              <Button variant="secondary" disabled={cosign.isPending} onClick={() => cosign.mutate({ decisionId: row.pending!.id, decision: 'decline', note: cosignNote || undefined })}>{t('decline')}</Button>
            </div>
          </> : null}
        </div>
      ) : isDecided(row) ? (
        <div className="nl-q-actions"><Button disabled>{t('decided')}</Button><span className="nl-q-note">{t('decidedNote')}</span></div>
      ) : (
        <>
          <div className="nl-q-chips" role="group" aria-label={t('outcome')}>
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
    </>
  );
}
