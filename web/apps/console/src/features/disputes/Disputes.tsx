import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import {
  BoHeader, Button, ChoiceTiles, DataTable, ErrorState, Field, formatPrice, formatNumber, KpiStrip, PageSkeleton, Segmented, StatusTag, TextArea, TextInput, UnderlineTabs, useLocale,
  type DataTableColumn, type StatusTone,
} from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import { usePlace, usePlaceName } from '../shell/scope';
import { SCREEN_PATH } from '../shell/screens';
import { SavedViews, statusTone } from '../shell/tables';
import { disputesQuery, OUTCOMES, useCosign, useDecideCase, type Item, type Outcome } from './api';
import { useDisputesT, type DisputesKey } from './messages';
import '../shell/queues.css';
import './disputes.css';

export const CASE_VIEWS = ['agent', 'cosign', 'decided', 'all'] as const;
export type CaseView = (typeof CASE_VIEWS)[number];
export const KINDS = ['all', 'dispute', 'refund'] as const;
export interface DisputesSearch { province?: string; market?: string; view?: CaseView; kind?: (typeof KINDS)[number] }

export type State = 'agent' | 'cosign' | 'decided';
export const isDecided = (row: Item['row']) => row.state === 'decided' || ['approved', 'denied', 'paid'].includes(row.state);
export const stateOf = (row: Item['row']): State => (row.pending ? 'cosign' : isDecided(row) ? 'decided' : 'agent');
/** E-15 colour roles: waiting for an agent is due (warning), a co-sign is information, decided is done. */
export const STATE_TONE: Record<State, StatusTone> = { agent: 'warning', cosign: 'info', decided: 'success' };

interface Row { id: string; ref: string; subject: string; parties: string; amount: number; opened: string; state: string; item: Item }

/**
 * Disputes & refunds (S-80; E-15 S-177): disputes with an agent and refund cases escalated after the seller's 24 h,
 * under the KPI strip of the desk — the states as tabs with counts, the kind filter and saved views, dense rows with
 * status tags. A row opens the case's page (both parties, evidence, history, the decision panel). Admin, trust &
 * safety, finance and support open it; the top bar's scope narrows it.
 */
export function Disputes() {
  const t = useDisputesT();
  const search = useSearch({ strict: false }) as DisputesSearch;
  const filter = usePlace();
  const placeName = usePlaceName();
  const navigate = useNavigate();
  const query = useQuery(disputesQuery(filter));
  const age = useAge();
  const { locale } = useLocale();
  const { roleName } = useGrant();
  const head = (sub?: string) => <BoHeader title={t('title')} sub={sub} actions={<Link className="btn btn-secondary" to="/disputes/card">{t('cardLink')}</Link>} />;
  if (query.isPending) return <div className="bo-page">{head()}<PageSkeleton kpis={3} rows={4} /></div>;
  if (query.isError && !query.data) {
    return <div className="bo-page">{head()}{query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  const data = query.data!;
  const view = search.view ?? 'agent';
  const kind = search.kind ?? 'all';
  const go = (next: Partial<DisputesSearch>) => void navigate({ to: SCREEN_PATH.disputes, search: { ...search, ...next } as never, replace: true });
  const items = data.items.filter(i => kind === 'all' || i.row.kind === kind);
  const count = (v: CaseView) => formatNumber(items.filter(i => v === 'all' || stateOf(i.row) === v).length, locale);
  const s = data.summary;
  const rows: Row[] = items.filter(i => view === 'all' || stateOf(i.row) === view).map(i => ({
    id: `${i.row.kind}:${i.row.id}`, ref: i.row.caseNumber, subject: i.row.subject, parties: t('parties', { customer: i.row.customerName ?? '—', seller: i.businessName }),
    amount: i.row.amountCents, opened: t('opened', { age: age(i.row.openedAt) }), state: t(`st_${stateOf(i.row)}`), item: i,
  }));
  const columns: DataTableColumn<Row>[] = [
    { key: 'ref', label: t('c_case'), sub: 'subject', subLabel: t('c_subject'), primary: true },
    { key: 'parties', label: t('c_parties') },
    { key: 'amount', label: t('c_amount'), type: 'money' },
    { key: 'opened', label: t('c_opened') },
    { key: 'state', label: t('c_state'), type: 'tag', filter: false },
  ];
  return (
    <div className="bo-page">
      {head(t('sub2', { scope: placeName, n: s.forAgent }))}
      <KpiStrip label={t('kpis')} items={[
        { key: 'agent', label: t('k_agent'), value: formatNumber(s.forAgent, locale), onClick: () => go({ view: 'agent' }) },
        { key: 'window', label: t('k_window'), value: formatNumber(s.inSellerWindow, locale) },
        { key: 'closed', label: t('k_closed'), value: formatNumber(s.closedThisWeek, locale) },
      ]} />
      <DataTable<Row> dense entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} pageSize={25}
        views={<UnderlineTabs aria-label={t('views')} value={view} onChange={v => go({ view: v === 'agent' ? undefined : v })}
          options={CASE_VIEWS.map(v => ({ value: v, label: t(`v_${v}` as DisputesKey), count: count(v) }))} />}
        filters={<>
          <Segmented name="disputes-kind" aria-label={t('kindFilter')} value={kind} onChange={k => go({ kind: k === 'all' ? undefined : k })}
            options={KINDS.map(k => ({ value: k, label: t(`kd_${k}` as DisputesKey) }))} />
          <SavedViews screen="disputes" current={{ view: search.view, kind: search.kind }} onApply={v => void navigate({ to: SCREEN_PATH.disputes, search: v as never })} />
        </>}
        rowTones={r => ({ state: statusTone(STATE_TONE[stateOf(r.item.row)]) })}
        can={{ create: false, update: false, delete: false }} roleName={roleName} emptyText={t('empty')} openLabel={t('open')} reportName="disputes"
        onOpen={r => void navigate({ to: '/disputes/$kind/$caseId', params: { kind: r.item.row.kind, caseId: r.item.row.id } })} />
    </div>
  );
}

/** The agent's decision, the co-sign above $500, or the decided state. */
export function Decision({ item }: { item: Item }) {
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
  const money = (c: number) => formatPrice(c, locale);
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
        <div className="nl-q-actions"><StatusTag tone="success">{t('decided')}</StatusTag><span className="nl-q-note">{t('decidedNote')}</span></div>
      ) : (
        <>
          <ChoiceTiles aria-label={t('outcome')} layout="grid" value={outcome} onChange={setOutcome} options={outcomes.map(o => ({ value: o, label: t(`o_${o}`) }))} />
          {withAmount ? (
            <Field label={t('amount')} error={errors.refundCents}>
              <TextInput inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} />
            </Field>
          ) : null}
          <TextArea className="nl-ds-note" aria-label={t('notePlaceholder')} placeholder={t('notePlaceholder')} value={note} maxLength={1000} onChange={e => setNote(e.target.value)} />
          {errors.outcome ? <p role="alert" className="nl-q-error">{errors.outcome}</p> : null}
          <div className="nl-q-actions">
            <Button block disabled={!can('decide') || decide.isPending} onClick={submit}>{t('decide', { outcome: t(`o_${outcome}`) })}</Button>
            <span className="nl-q-note">{can('decide') ? t('decideNote') : t('viewOnly', { role: roleName })}</span>
          </div>
        </>
      )}
      {refusal ? <p role="alert" className="nl-q-error">{refusal.message}</p> : null}
    </>
  );
}
