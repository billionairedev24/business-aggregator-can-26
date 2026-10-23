import { useMemo, useRef, useState, type ChangeEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Image, MapPin, Paperclip } from '@phosphor-icons/react';
import { Alert, BoHeader, Button, DataTable, Dialog, EmptyState, Field, KpiStrip, PageSkeleton, Skeleton, StatusTag, TextArea, useFormatters, useLocale, type DataTableColumn, type DataTableView, type StatusTone } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import {
  actionKey, casesQuery, downloads, useAcceptRefund, useContestDispute, useContestRefund, useFullRefund, useGoodwillOffer, useSaveResponse, useUploadEvidence,
  type CaseDetail, type CaseRow, type CasesOverview,
} from './api';
import { dateTime, pctFromBps, timeOf } from './format';
import { useFinanceT, type FinanceKey } from './messages';
import { QueryState } from './QueryState';
import './finance.css';
import { standingQuery } from '../standing/api';
import { screenHref } from '../shell/nav';

const MAX_BYTES = 10 * 1024 * 1024;
const TYPES = ['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'application/pdf'];

/**
 * Refunds & disputes (S-176, on the Studio dashboard's language): the page head with the month's count, the KPI strip
 * (open disputes, refunds, cases waiting on you, the dispute rate against the tier's floor), each open case as a card
 * with its state, then the history with status tabs.
 */
export function RefundsScreen() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const cases = useQuery(casesQuery(merchantId));
  return (
    <div className="fin-page">
      <QueryState query={cases} skeleton={<><BoHeader title={t('refundsKicker')} /><PageSkeleton kpis={4} rows={5} /></>}>{c => <Body c={c} />}</QueryState>
    </div>
  );
}

/** What an open case waits for, as a status: the business (warning, danger once its reply is due within a day), an agent (info), the customer (neutral). */
export function caseState(d: CaseDetail, now = Date.now()): { key: FinanceKey; tone: StatusTone } {
  if (d.offer?.state === 'pending') return { key: 'cs_offer', tone: 'neutral' };
  if (d.state === 'agent') return { key: 'cs_agent', tone: 'info' };
  if (d.state !== 'open') return { key: 'cs_decided', tone: 'neutral' };
  const soon = d.dueBy && new Date(d.dueBy).getTime() - now < 86_400_000;
  return { key: 'cs_you', tone: soon ? 'danger' : 'warning' };
}

function Body({ c }: { c: CasesOverview }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const f = useFormatters();
  const tier = useMerchant()?.tier ?? 'registered';
  const merchantId = useMerchantId();
  // S-156: decisions an agent took against the business can be appealed once, from Standing
  const appealable = useQuery(standingQuery(merchantId)).data?.appealableDisputes.length ?? 0;
  const waiting = c.open.filter(x => caseState(x).key === 'cs_you').length;
  const over = c.disputeRateBps > c.disputeRateFloorBps;
  return (
    <>
      <BoHeader title={t('refundsKicker')} sub={t('refundsTitle', { disputes: c.openDisputes, refunds: c.refundsLast30Days })} />
      <KpiStrip label={t('refundsKpis')} items={[
        { key: 'open', label: t('kpiOpenDisputes'), value: f.number(c.openDisputes) },
        { key: 'waiting', label: t('kpiWaitingOnYou'), value: f.number(waiting), ...(waiting ? { delta: t('kpiWaitingDelta'), trend: 'down' as const } : { delta: t('kpiWaitingNone'), trend: 'up' as const }) },
        { key: 'refunds', label: t('kpiRefunds30'), value: f.number(c.refundsLast30Days) },
        { key: 'rate', label: t('kpiDisputeRate'), value: pctFromBps(c.disputeRateBps, locale), trend: over ? 'down' : 'up',
          delta: t('kpiDisputeFloor', { tier: t(`tier_${tier}` as FinanceKey), floor: pctFromBps(c.disputeRateFloorBps, locale) }) },
      ]} />
      <p className="fin-lede">{t('refundsLede')}</p>
      {appealable ? (
        <Alert tone="info" actions={<a className="btn btn-secondary" href={screenHref(merchantId, 'standing')}>{t('appealOpen')}</a>}>
          {t('appealable', { n: appealable })}
        </Alert>
      ) : null}
      <section className="fin-open" aria-labelledby="fin-open-h">
        <h2 className="fin-section-title" id="fin-open-h">{t('openCases', { count: c.open.length })}</h2>
        <div className="fin-cases">
          {c.open.length ? c.open.map(x => {
            const st = caseState(x);
            return <div key={x.id} className="bo-card fin-case-card" data-tone={st.tone}>{x.type === 'dispute' ? <DisputeCase d={x} /> : <RefundCase r={x} />}</div>;
          }) : <EmptyState>{t('noOpenCases')}</EmptyState>}
        </div>
      </section>
      <section className="bo-card fin-history" aria-labelledby="fin-cases-history">
        <h2 className="bo-card-title" id="fin-cases-history">{t('history')}</h2>
        <History rows={c.history} />
        <p className="fin-muted">
          {t('disputeRate', { rate: pctFromBps(c.disputeRateBps, locale), tier: t(`tier_${tier}` as FinanceKey), floor: pctFromBps(c.disputeRateFloorBps, locale) })}
        </p>
      </section>
    </>
  );
}

function EvidenceTags({ d }: { d: CaseDetail }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const photos = d.evidence.filter(e => e.kind === 'photo');
  const link = (id: string, label: string, downloadable: boolean) => downloadable
    ? <a key={id} className="fin-evidence fin-evidence-link" href={downloads.evidence(merchantId, d.id, id)} target="_blank" rel="noopener"><Paperclip size={14} aria-hidden />{label}</a>
    : <span key={id} className="fin-evidence"><Paperclip size={14} aria-hidden />{label}</span>;
  return (
    <div className="fin-tags">
      {photos.length ? <span className="fin-evidence"><Image size={14} aria-hidden />{t('photosAttached', { count: photos.length })}</span> : null}
      {d.evidence.filter(e => e.kind === 'report').map(e => link(e.id, t('reportPdf'), e.downloadable))}
      {d.evidence.filter(e => e.kind === 'document').map(e => link(e.id, e.name, e.downloadable))}
      {d.evidence.filter(e => e.kind === 'gps').map(e => <span key={e.id} className="fin-evidence"><MapPin size={14} aria-hidden />{t('gpsCheckIn', { time: timeOf(e.at, locale) })}</span>)}
    </div>
  );
}

function CaseHead({ id, d, title }: { id: string; d: CaseDetail; title: string }) {
  const t = useFinanceT();
  const f = useFormatters();
  const st = caseState(d);
  return (
    <div className="fin-case-head">
      <h3 id={id} className="fin-case-title">{title}</h3>
      <StatusTag tone={st.tone}>{t(st.key)}</StatusTag>
      <span className="fin-case-meta">{t('openedOn', { date: f.when(d.openedAt) })}</span>
    </div>
  );
}

function DisputeCase({ d }: { d: CaseDetail }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const role = useRole();
  const owner = role === 'owner';
  const operator = owner || role === 'technician' || role === 'cook';
  const save = useSaveResponse(merchantId);
  const upload = useUploadEvidence(merchantId);
  const offer = useGoodwillOffer(merchantId);
  const refund = useFullRefund(merchantId);
  const contest = useContestDispute(merchantId);
  const offerKey = useRef(actionKey());
  const refundKey = useRef(actionKey());
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(d.response ?? '');
  const [responseError, setResponseError] = useState<string>();
  const [fileError, setFileError] = useState<string>();
  const [confirming, setConfirming] = useState<null | 'offer' | 'refund'>(null);
  const [actionError, setActionError] = useState<string>();
  const half = Math.round(d.amountCents / 2);
  const isOpen = d.state === 'open';
  const offerPending = d.offer?.state === 'pending';
  const tooLong = text.trim().length > 2000;

  async function saveResponse() {
    if (tooLong) { setResponseError(t('responseTooLong')); return; }
    try { await save.mutateAsync({ disputeId: d.id, response: text }); setEditing(false); setResponseError(undefined); }
    catch (e) { setResponseError(e instanceof ValidationError ? e.byField().response ?? e.message : t('loadError')); }
  }
  async function onFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (!TYPES.includes(file.type)) { setFileError(t('evidenceType')); return; }
    if (file.size > MAX_BYTES) { setFileError(t('evidenceSize')); return; }
    setFileError(undefined);
    try { await upload.mutateAsync({ disputeId: d.id, file }); }
    catch (err) { setFileError(err instanceof ValidationError ? err.errors[0]?.message : t('loadError')); }
  }
  async function run(action: () => Promise<unknown>) {
    setActionError(undefined);
    try { await action(); setConfirming(null); }
    catch (e) {
      if (e instanceof ValidationError && e.byField().response) { setResponseError(e.byField().response); setEditing(true); setConfirming(null); return; }
      setActionError(e instanceof Error && e.message ? e.message : t('loadError'));
    }
  }
  function onContest() {
    if (!(d.response ?? '').trim()) { setResponseError(t('responseRequired')); setEditing(true); return; }
    void run(() => contest.mutateAsync({ disputeId: d.id }));
  }
  const hint = offerPending ? t('hintOffer') : d.state === 'agent' ? t('hintAgent') : t('hintOpen');
  return (
    <section className="fin-case" aria-labelledby={`case-${d.id}`}>
      <CaseHead id={`case-${d.id}`} d={d} title={t('disputeHeading', { number: d.caseNumber, subject: d.subject, amount: f.price(d.amountCents) })} />
      {d.customerStatement ? <div className="fin-quote"><strong>{t('customerSays', { name: d.customerName ?? '' })}</strong> “{d.customerStatement}”</div> : null}
      {editing ? (
        <div>
          <Field label={t('responseLabel')} error={responseError}>
            <TextArea rows={4} value={text} onChange={e => { setText(e.target.value); setResponseError(e.target.value.trim().length > 2000 ? t('responseTooLong') : undefined); }} />
          </Field>
          <div className="fin-actions">
            <Button onClick={() => void saveResponse()} disabled={save.isPending} aria-busy={save.isPending}>{save.isPending ? t('saving') : t('saveResponse')}</Button>
            <Button variant="ghost" onClick={() => { setEditing(false); setText(d.response ?? ''); setResponseError(undefined); }}>{t('cancel')}</Button>
          </div>
        </div>
      ) : (
        <div className="fin-draft">
          {d.response ? <><strong>{isOpen ? t('yourResponseDraft') : t('yourResponse')}</strong> {d.response}</> : t('noResponse')}
          {operator && isOpen ? <div className="fin-gap-top"><Button variant="ghost" onClick={() => setEditing(true)}>{d.response ? t('editResponse') : t('writeResponse')}</Button></div> : null}
        </div>
      )}
      {responseError && !editing ? <div role="alert" className="nl-error">{responseError}</div> : null}
      <EvidenceTags d={d} />
      {operator && d.state !== 'decided' ? (
        <div>
          <label className="btn btn-secondary fin-file">
            {upload.isPending ? t('working') : t('addEvidence')}
            <input type="file" accept="image/jpeg,image/png,image/heic,application/pdf" onChange={e => void onFile(e)} aria-describedby={`ev-hint-${d.id}`} />
          </label>
          <div id={`ev-hint-${d.id}`} className="fin-small fin-gap-top">{t('evidenceHint')}</div>
          {fileError ? <div role="alert" className="nl-error">{fileError}</div> : null}
        </div>
      ) : null}
      {owner && (isOpen || offerPending) ? (
        <div className="fin-actions">
          <Button onClick={() => setConfirming('offer')} disabled={!isOpen}>{offerPending ? t('offerSent') : t('sendOffer')}</Button>
          <Button variant="secondary" onClick={() => setConfirming('refund')} disabled={!isOpen}>{t('fullRefund')}</Button>
          <Button variant="secondary" onClick={onContest} disabled={!isOpen || contest.isPending}>{t('contest')}</Button>
        </div>
      ) : null}
      {actionError ? <div role="alert" className="nl-error">{actionError}</div> : null}
      <div className="fin-muted">{hint}{isOpen && d.dueBy ? ` ${t('replyBy', { date: dateTime(d.dueBy, locale) })}` : ''}</div>
      <Dialog open={confirming === 'offer'} onClose={() => setConfirming(null)} title={t('confirmOfferTitle', { amount: f.price(half) })}
        actions={<>
          <Button variant="ghost" onClick={() => setConfirming(null)}>{t('cancel')}</Button>
          <Button onClick={() => void run(() => offer.mutateAsync({ disputeId: d.id, amountCents: half, key: offerKey.current.get() }))} disabled={offer.isPending} aria-busy={offer.isPending}>{t('sendOffer')}</Button>
        </>}>
        <p>{t('confirmOffer')}</p>
      </Dialog>
      <Dialog open={confirming === 'refund'} onClose={() => setConfirming(null)} role="alertdialog" title={t('confirmFullRefundTitle', { amount: f.price(d.amountCents) })}
        actions={<>
          <Button variant="ghost" onClick={() => setConfirming(null)}>{t('cancel')}</Button>
          <Button onClick={() => void run(() => refund.mutateAsync({ disputeId: d.id, key: refundKey.current.get() }))} disabled={refund.isPending} aria-busy={refund.isPending}>{t('fullRefund')}</Button>
        </>}>
        <p>{t('confirmFullRefund', { amount: f.price(d.amountCents) })}</p>
      </Dialog>
    </section>
  );
}

function RefundCase({ r }: { r: CaseDetail }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const owner = useRole() === 'owner';
  const accept = useAcceptRefund(merchantId);
  const contest = useContestRefund(merchantId);
  const key = useRef(actionKey());
  const [contesting, setContesting] = useState(false);
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<string>();
  const reasonError = !reason.trim() ? t('contestReasonRequired') : undefined;
  async function send() {
    setTouched(true);
    if (reasonError) return;
    try { await contest.mutateAsync({ refundId: r.id, reason }); }
    catch (e) { setError(e instanceof ValidationError ? e.byField().reason ?? e.message : t('loadError')); }
  }
  return (
    <section className="fin-case" aria-labelledby={`case-${r.id}`}>
      <CaseHead id={`case-${r.id}`} d={r} title={t('refundHeading', { number: r.caseNumber, subject: r.subject, amount: f.price(r.amountCents) })} />
      <div className="fin-quote">{t('customerAsked', { name: r.customerName ?? '', amount: f.price(r.amountCents) })}</div>
      {contesting ? (
        <div>
          <Field label={t('contestReason')} error={touched ? (reasonError ?? error) : error}>
            <TextArea rows={3} value={reason} onChange={e => { setReason(e.target.value); setError(undefined); }} onBlur={() => setTouched(true)} />
          </Field>
          <div className="fin-actions">
            <Button onClick={() => void send()} disabled={contest.isPending} aria-busy={contest.isPending}>{t('sendToAgent')}</Button>
            <Button variant="ghost" onClick={() => setContesting(false)}>{t('cancel')}</Button>
          </div>
        </div>
      ) : owner ? (
        <div className="fin-actions">
          <Button onClick={() => accept.mutate({ refundId: r.id, key: key.current.get() }, { onError: e => setError(e.message) })} disabled={accept.isPending} aria-busy={accept.isPending}>{t('acceptRefund')}</Button>
          <Button variant="secondary" onClick={() => setContesting(true)}>{t('contest')}</Button>
        </div>
      ) : null}
      {error && !contesting ? <div role="alert" className="nl-error">{error}</div> : null}
      {r.dueBy ? <div className="fin-muted">{r.auto ? t('refundHintAuto', { date: dateTime(r.dueBy, locale) }) : t('refundHint', { date: dateTime(r.dueBy, locale) })}</div> : null}
    </section>
  );
}

interface HistoryRow { id: string; number: string; what: string; when: string; amount: string; outcome: string; tone: StatusTone; group: 'open' | 'won' | 'refunded' | 'other' }
const GOOD = new Set(['won']);
const ATTENTION = new Set(['awaiting_you', 'with_agent', 'offer_sent', 'queued']);
const REFUNDED = new Set(['auto_refunded', 'refunded', 'lost', 'partial', 'goodwill']);

/** S-176: won success, still moving warning (the business's) or info (an agent's), lost danger, the rest neutral. */
export function caseRows(rows: CaseRow[], t: ReturnType<typeof useFinanceT>, price: (c: number) => string, date: (iso: string) => string = iso => iso): HistoryRow[] {
  return rows.map(r => ({
    id: r.id, number: r.caseNumber, what: r.what, when: date(r.openedAt),
    amount: r.kind === 'credit' ? t('credit', { amount: price(r.amountCents) }) : price(r.amountCents),
    outcome: t(`o_${r.outcome}` as FinanceKey),
    tone: GOOD.has(r.outcome) ? 'success' : r.outcome === 'awaiting_you' ? 'warning' : ATTENTION.has(r.outcome) ? 'info' : r.outcome === 'lost' ? 'danger' : 'neutral',
    group: ATTENTION.has(r.outcome) ? 'open' : GOOD.has(r.outcome) ? 'won' : REFUNDED.has(r.outcome) ? 'refunded' : 'other',
  }));
}

function History({ rows }: { rows: CaseRow[] }) {
  const t = useFinanceT();
  const f = useFormatters();
  const role = useRole();
  const data = useMemo(() => caseRows(rows, t, c => f.price(c), iso => f.when(iso)), [rows, t, f]);
  const columns: DataTableColumn<HistoryRow>[] = [
    { key: 'number', label: t('colCase'), primary: true, nowrap: true },
    { key: 'what', label: t('colWhat'), card: 'line' },
    { key: 'when', label: t('colOpened'), priority: 1, nowrap: true, card: 'meta' },
    { key: 'amount', label: t('colAmount'), priority: 2, card: 'end' },
    { key: 'outcome', label: t('colOutcome'), type: 'tag', status: r => r.tone },
  ];
  const views: DataTableView<HistoryRow>[] = [
    { id: 'all', label: t('viewAll') },
    { id: 'open', label: t('viewInProgress'), when: r => r.group === 'open', hideEmpty: true },
    { id: 'won', label: t('viewWon'), when: r => r.group === 'won', hideEmpty: true },
    { id: 'refunded', label: t('viewRefunded'), when: r => r.group === 'refunded', hideEmpty: true },
  ];
  if (!rows) return <Skeleton height={200} />;
  return (
    <DataTable<HistoryRow> entity={t('caseEntity')} plural={t('casePlural')} columns={columns} rows={data} pageSize={5} views={views}
      can={{ create: false, update: false, delete: false, export: true }}
      roleName={t(`role_${role}` as FinanceKey)} emptyText={t('historyEmpty')} reportName="refunds-disputes" />
  );
}
