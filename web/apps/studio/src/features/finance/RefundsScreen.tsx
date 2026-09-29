import { useMemo, useRef, useState, type ChangeEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, DataTable, Dialog, EmptyState, Field, PageSkeleton, Skeleton, TextArea, useFormatters, useLocale, type DataTableColumn } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import {
  actionKey, casesQuery, downloads, useAcceptRefund, useContestDispute, useContestRefund, useFullRefund, useGoodwillOffer, useSaveResponse, useUploadEvidence,
  type CaseDetail, type CaseRow, type CasesOverview,
} from './api';
import { dateTime, moneyShort, pctFromBps, timeOf } from './format';
import { useFinanceT, type FinanceKey } from './messages';
import { QueryState } from './QueryState';
import './finance.css';

const MAX_BYTES = 10 * 1024 * 1024;
const TYPES = ['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'application/pdf'];

/** Design 02 · Refunds & disputes: open cases with evidence and answers, history, dispute rate. */
export function RefundsScreen() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const cases = useQuery(casesQuery(merchantId));
  return (
    <>
      <span className="nl-kicker">{t('refundsKicker')}</span>
      <QueryState query={cases} skeleton={<PageSkeleton kpis={0} rows={5} />}>{c => <Body c={c} />}</QueryState>
    </>
  );
}

function Body({ c }: { c: CasesOverview }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const tier = useMerchant()?.tier ?? 'registered';
  return (
    <>
      <h1 className="nl-page-title" style={{ margin: '0 0 8px' }}>{t('refundsTitle', { disputes: c.openDisputes, refunds: c.refundsLast30Days })}</h1>
      <p className="nl-lede" style={{ maxWidth: '60ch', color: 'var(--color-neutral-700)', margin: '0 0 24px' }}>{t('refundsLede')}</p>
      <div className="fin-cols">
        <div>
          {c.open.length ? c.open.map(x => (x.type === 'dispute' ? <DisputeCase key={x.id} d={x} /> : <RefundCase key={x.id} r={x} />))
            : <EmptyState>{t('noOpenCases')}</EmptyState>}
        </div>
        <div>
          <h2 className="fin-h2">{t('history')}</h2>
          <History rows={c.history} />
          <p className="fin-muted" style={{ marginTop: 14 }}>
            {t('disputeRate', { rate: pctFromBps(c.disputeRateBps, locale), tier: t(`tier_${tier}` as FinanceKey), floor: pctFromBps(c.disputeRateFloorBps, locale) })}
          </p>
        </div>
      </div>
    </>
  );
}

function EvidenceTags({ d }: { d: CaseDetail }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const photos = d.evidence.filter(e => e.kind === 'photo');
  const link = (id: string, label: string, downloadable: boolean, tone = 'tag-accent') => downloadable
    ? <a key={id} className={`tag ${tone}`} href={downloads.evidence(merchantId, d.id, id)} target="_blank" rel="noopener">{label}</a>
    : <span key={id} className={`tag ${tone}`}>{label}</span>;
  return (
    <div className="fin-tags">
      {photos.length ? <span className="tag tag-accent">{t('photosAttached', { count: photos.length })}</span> : null}
      {d.evidence.filter(e => e.kind === 'report').map(e => link(e.id, t('reportPdf'), e.downloadable))}
      {d.evidence.filter(e => e.kind === 'document').map(e => link(e.id, e.name, e.downloadable))}
      {d.evidence.filter(e => e.kind === 'gps').map(e => <span key={e.id} className="tag tag-neutral">{t('gpsCheckIn', { time: timeOf(e.at, locale) })}</span>)}
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
      <h2 id={`case-${d.id}`} className="fin-h2" style={{ margin: 0 }}>{t('disputeHeading', { number: d.caseNumber, subject: d.subject, amount: moneyShort(d.amountCents, f.money) })}</h2>
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
          {operator && isOpen ? <div><Button variant="ghost" onClick={() => setEditing(true)} style={{ marginTop: 6 }}>{d.response ? t('editResponse') : t('writeResponse')}</Button></div> : null}
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
          <div id={`ev-hint-${d.id}`} className="fin-small" style={{ marginTop: 4 }}>{t('evidenceHint')}</div>
          {fileError ? <div role="alert" className="nl-error">{fileError}</div> : null}
        </div>
      ) : null}
      {owner && (isOpen || offerPending) ? (
        <div className="fin-actions" style={{ marginTop: 4 }}>
          <Button onClick={() => setConfirming('offer')} disabled={!isOpen}>{offerPending ? t('offerSent') : t('sendOffer')}</Button>
          <Button variant="secondary" onClick={() => setConfirming('refund')} disabled={!isOpen}>{t('fullRefund')}</Button>
          <Button variant="secondary" onClick={onContest} disabled={!isOpen || contest.isPending}>{t('contest')}</Button>
        </div>
      ) : null}
      {actionError ? <div role="alert" className="nl-error">{actionError}</div> : null}
      <div className="fin-muted">{hint}{isOpen && d.dueBy ? ` ${t('replyBy', { date: dateTime(d.dueBy, locale) })}` : ''}</div>
      <Dialog open={confirming === 'offer'} onClose={() => setConfirming(null)} title={t('confirmOfferTitle', { amount: f.money(half) })}
        actions={<>
          <Button variant="ghost" onClick={() => setConfirming(null)}>{t('cancel')}</Button>
          <Button onClick={() => void run(() => offer.mutateAsync({ disputeId: d.id, amountCents: half, key: offerKey.current.get() }))} disabled={offer.isPending} aria-busy={offer.isPending}>{t('sendOffer')}</Button>
        </>}>
        <p>{t('confirmOffer')}</p>
      </Dialog>
      <Dialog open={confirming === 'refund'} onClose={() => setConfirming(null)} role="alertdialog" title={t('confirmFullRefundTitle', { amount: f.money(d.amountCents) })}
        actions={<>
          <Button variant="ghost" onClick={() => setConfirming(null)}>{t('cancel')}</Button>
          <Button onClick={() => void run(() => refund.mutateAsync({ disputeId: d.id, key: refundKey.current.get() }))} disabled={refund.isPending} aria-busy={refund.isPending}>{t('fullRefund')}</Button>
        </>}>
        <p>{t('confirmFullRefund', { amount: f.money(d.amountCents) })}</p>
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
      <h2 id={`case-${r.id}`} className="fin-h2" style={{ margin: 0 }}>{t('refundHeading', { number: r.caseNumber, subject: r.subject, amount: moneyShort(r.amountCents, f.money) })}</h2>
      <div className="fin-quote">{t('customerAsked', { name: r.customerName ?? '', amount: f.money(r.amountCents) })}</div>
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
        <div className="fin-actions" style={{ marginTop: 4 }}>
          <Button onClick={() => accept.mutate({ refundId: r.id, key: key.current.get() }, { onError: e => setError(e.message) })} disabled={accept.isPending} aria-busy={accept.isPending}>{t('acceptRefund')}</Button>
          <Button variant="secondary" onClick={() => setContesting(true)}>{t('contest')}</Button>
        </div>
      ) : null}
      {error && !contesting ? <div role="alert" className="nl-error">{error}</div> : null}
      {r.dueBy ? <div className="fin-muted">{r.auto ? t('refundHintAuto', { date: dateTime(r.dueBy, locale) }) : t('refundHint', { date: dateTime(r.dueBy, locale) })}</div> : null}
    </section>
  );
}

interface HistoryRow { id: string; number: string; what: string; amount: string; outcome: string; tone: 'tag-accent' | 'tag-accent-2' | 'tag-neutral' }
const GOOD = new Set(['won']);
const ATTENTION = new Set(['awaiting_you', 'with_agent', 'offer_sent', 'queued']);

export function caseRows(rows: CaseRow[], t: ReturnType<typeof useFinanceT>, money: (c: number) => string): HistoryRow[] {
  return rows.map(r => ({
    id: r.id, number: r.caseNumber, what: r.what,
    amount: r.kind === 'credit' ? t('credit', { amount: money(r.amountCents) }) : money(r.amountCents),
    outcome: t(`o_${r.outcome}` as FinanceKey), tone: GOOD.has(r.outcome) ? 'tag-accent' : ATTENTION.has(r.outcome) ? 'tag-accent-2' : 'tag-neutral',
  }));
}

function History({ rows }: { rows: CaseRow[] }) {
  const t = useFinanceT();
  const f = useFormatters();
  const role = useRole();
  const data = useMemo(() => caseRows(rows, t, c => f.money(c)), [rows, t, f]);
  const columns: DataTableColumn<HistoryRow>[] = [
    { key: 'number', label: t('colCase'), primary: true },
    { key: 'what', label: t('colWhat') },
    { key: 'amount', label: t('colAmount'), priority: 2 },
    { key: 'outcome', label: t('colOutcome'), type: 'tag' },
  ];
  if (!rows) return <Skeleton height={200} />;
  return (
    <DataTable<HistoryRow> entity={t('caseEntity')} plural={t('casePlural')} columns={columns} rows={data} pageSize={5}
      rowTones={r => ({ outcome: r.tone })} can={{ create: false, update: false, delete: false, export: true }}
      roleName={t(`role_${role}` as FinanceKey)} emptyText={t('historyEmpty')} reportName="refunds-disputes" />
  );
}
