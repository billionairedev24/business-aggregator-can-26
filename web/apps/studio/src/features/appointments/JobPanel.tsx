import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { z } from 'zod';
import { Alert, Dialog, ErrorState, Field, Skeleton, StatusTag, TextArea, TextInput, useFormatters, useLocale, type Locale, timeZone } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { screenHref } from '../shell/nav';
import { ApiError, ValidationError } from '../../lib/http';
import { useSession } from '../../lib/session';
import { clock } from '../../lib/time';
import { jobQuery, openCustomerThread, reportCustomerNoShow, useAdvanceJob, useRequestApproval, useUploadMedia, type JobDetail, type Media, type Step } from './api';
import { CustomerPhotos } from './QuoteRequests';
import { useAppointmentsT } from './messages';
import { LiveShare } from './LiveShare';
import { APPROVAL_MESSAGES, QUOTE_MESSAGES_FR } from './quote';
import { useLocalizeMessage } from '../../lib/validation';

type T = ReturnType<typeof useAppointmentsT>;

/** Best-effort GPS fix for the check-in proof (never blocks the transition). */
function position(): Promise<{ lat: number; lng: number } | undefined> {
  if (typeof navigator === 'undefined' || !navigator.geolocation) return Promise.resolve(undefined);
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(undefined), 4000);
    navigator.geolocation.getCurrentPosition(p => { clearTimeout(timer); resolve({ lat: p.coords.latitude, lng: p.coords.longitude }); }, () => { clearTimeout(timer); resolve(undefined); }, { timeout: 4000, maximumAge: 60000 });
  });
}

export function jobWhen(j: Pick<JobDetail, 'startsAt'>, locale: Locale) {
  const day = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { weekday: 'short', timeZone: timeZone() }).format(new Date(j.startsAt)).replace('.', '');
  return `${day} ${clock(j.startsAt, locale)}`;
}

/** The design's jobStages: the primary action and hint per state. */
export function stage(j: JobDetail, t: T, price: (c: number) => string): { cta: string; step?: Step; hint: string } {
  switch (j.state) {
    case 'confirmed': return { cta: t('startTravel'), step: 'en-route', hint: t('hintStart') };
    case 'en_route': return { cta: t('checkIn'), step: 'on-site', hint: t('hintCheckIn') };
    case 'on_site': return { cta: t('complete'), step: 'complete', hint: t('hintComplete') };
    case 'completed': return { cta: t('waiting'), hint: t('hintWaiting', { money: price(j.escrowMoney?.heldCents ?? j.priceCents ?? 0) }) };
    case 'signed_off': return { cta: t('signedOff'), hint: t('hintSigned') };
    default: return { cta: t(`state_${j.state}`), hint: t('hintOther', { state: t(`state_${j.state}`).toLowerCase() }) };
  }
}

export function JobPanel({ jobId }: { jobId: string | null }) {
  const t = useAppointmentsT();
  const merchantId = useMerchantId();
  const q = useQuery({ ...jobQuery(merchantId, jobId ?? ''), enabled: !!jobId });
  if (!jobId) return <section><p className="nl-muted">{t('noJob')}</p></section>;
  if (q.isPending) return <section aria-busy="true"><div className="nl-appt-skel"><Skeleton height={28} width="70%" />{Array.from({ length: 5 }, (_, i) => <Skeleton key={i} height={20} />)}</div></section>;
  if (q.isError) return <section><ErrorState message={t('loadJobError')} onRetry={() => void q.refetch()} /></section>;
  return <JobCard job={q.data} />;
}

function JobCard({ job: j }: { job: JobDetail }) {
  const t = useAppointmentsT();
  const f = useFormatters();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const role = useRole();
  const nav = useNavigate();
  const advance = useAdvanceJob(merchantId);
  const [completing, setCompleting] = useState(false);
  const [approving, setApproving] = useState(false);
  const st = stage(j, t, c => f.price(c));
  const canOperate = role !== 'bookkeeper';
  const me = useSession().data?.user.id;
  const when = jobWhen(j, locale);
  const title = j.ref ? t('jobTitleRef', { title: j.title, when, ref: j.ref }) : t('jobTitle', { title: j.title, when });
  const go = async () => {
    if (!st.step) return;
    if (st.step === 'complete') { setCompleting(true); return; }
    const pos = await position();
    advance.mutate({ id: j.id, step: st.step, body: pos ?? {} });
  };
  const toMessages = () => void nav({ to: screenHref(merchantId, 'messages') });
  // S-153: the job's own customer thread (the one the customer reads), opened on demand
  const [threadError, setThreadError] = useState(false);
  const message = async () => {
    setThreadError(false);
    try {
      const { threadId } = await openCustomerThread(merchantId, 'jobs', j.id);
      void nav({ to: screenHref(merchantId, 'messages'), search: { thread: threadId } as never });
    } catch { setThreadError(true); }
  };
  // S-156: a customer who isn't there 15 minutes after the start can be reported; trust & safety confirm it
  const [noShow, setNoShow] = useState<'sent' | 'error' | string>();
  const lateEnough = Date.now() >= new Date(j.startsAt).getTime() + 15 * 60_000;
  const reportNoShow = async () => {
    try { await reportCustomerNoShow(merchantId, j.id); setNoShow('sent'); }
    catch (e) { setNoShow(e instanceof ApiError && e.body && typeof e.body === 'object' && 'detail' in e.body ? String((e.body as { detail: unknown }).detail) : 'error'); }
  };
  return (
    <section aria-labelledby="appt-job">
      <h2 id="appt-job" className="nl-h2 nl-appt-h2">{title}{j.memberName && j.memberUserId !== me ? <span className="nl-muted"> ({j.memberName})</span> : null}</h2>
      <dl className="nl-appt-facts">
        {j.customer ? <><dt>{t('customer')}</dt><dd>{j.customer.reliability != null ? t('customerLine', { name: j.customer.name, score: j.customer.reliability.toFixed(1), jobs: j.customer.pastJobs }) : t('customerLineNoScore', { name: j.customer.name, jobs: j.customer.pastJobs })}
          {j.customer.noShows ? <> <StatusTag tone="warning">{t('customerNoShows', { n: j.customer.noShows })}</StatusTag></> : null}</dd></> : null}
        {j.addressLine || j.access ? <><dt>{t('where')}</dt><dd>{[j.addressLine, j.access].filter(Boolean).join(' · ')}</dd></> : null}
        {j.vehicle ? <><dt>{t('vehicle')}</dt><dd>{j.vehicle}</dd></> : null}
        <dt>{t('escrow')}</dt><dd><EscrowFact j={j} /></dd>
        {j.balance && j.balance.state !== 'none' ? <><dt>{t('balance')}</dt><dd>{t(`balance_${j.balance.state}`, { money: f.price(j.balance.cents + j.balance.taxCents), date: j.balance.dueAt ? f.date(j.balance.dueAt, 'dateTime') : '—', attempts: j.balance.attempts })}</dd></> : null}
        {j.cancellation ? <><dt>{t('cancelledOn')}</dt><dd>{j.cancellation.feeCents > 0 ? t('cancelledLate', { date: f.date(j.cancellation.at, 'dateTime'), money: f.price(j.cancellation.feeCents + j.cancellation.feeTaxCents) }) : t('cancelledFree', { date: f.date(j.cancellation.at, 'dateTime') })}</dd></> : null}
      </dl>
      {j.customerPhotoIds?.length ? <CustomerPhotos merchantId={merchantId} ids={j.customerPhotoIds} /> : null}
      {threadError ? <div role="alert" className="nl-error">{t('threadError')}</div> : null}
      {j.customerNote ? <div className="nl-appt-quote">“{j.customerNote}”</div> : null}
      {j.approvals.length > 0 ? (
        <div className="nl-appt-approvals">
          <h3 className="nl-appt-cv-h">{t('approvalsTitle')}</h3>
          <ul>{j.approvals.map(a => <li key={a.id}><span>{a.description} · {f.price(a.amountCents)}</span><StatusTag tone={a.state === 'approved' ? 'success' : a.state === 'declined' ? 'danger' : 'warning'}>{t(`approval_${a.state}`)}</StatusTag></li>)}</ul>
        </div>
      ) : null}
      {canOperate ? (
        <div className="nl-appt-actions nl-appt-jobactions">
          <button type="button" className="btn btn-primary" disabled={!st.step || advance.isPending} onClick={() => void go()}>{st.cta}</button>
          <button type="button" className="btn btn-secondary" onClick={() => void message()}>{t('message')}</button>
          {['confirmed', 'en_route', 'on_site'].includes(j.state) ? <button type="button" className="btn btn-secondary" onClick={() => setApproving(true)}>{t('extraParts')}</button> : null}
          <button type="button" className="btn btn-tertiary" onClick={toMessages}>{t('reschedule')}</button>
          {['confirmed', 'en_route', 'on_site'].includes(j.state) && lateEnough && noShow !== 'sent'
            ? <button type="button" className="btn btn-tertiary" onClick={() => void reportNoShow()}>{t('noShow')}</button> : null}
        </div>
      ) : null}
      {noShow === 'sent' ? <Alert tone="info" role="status">{t('noShowSent')}</Alert>
        : noShow ? <Alert tone="error">{noShow === 'error' ? t('advanceError') : noShow}</Alert> : null}
      <div className="nl-appt-hint" aria-live="polite">{st.hint}</div>
      {canOperate && j.state === 'en_route' && (j.memberUserId === me || (!j.memberUserId && role === 'owner')) ? <LiveShare merchantId={merchantId} jobId={j.id} /> : null}
      {advance.isError ? <Alert tone="error">{t('advanceError')}</Alert> : null}
      <CompleteDialog open={completing} onClose={() => setCompleting(false)} job={j} />
      <ApprovalDialog open={approving} onClose={() => setApproving(false)} jobId={j.id} />
    </section>
  );
}

function CompleteDialog({ open, onClose, job }: { open: boolean; onClose: () => void; job: JobDetail }) {
  const t = useAppointmentsT();
  const merchantId = useMerchantId();
  const advance = useAdvanceJob(merchantId);
  const upload = useUploadMedia(merchantId);
  const [photos, setPhotos] = useState<Media[]>([]);
  const [report, setReport] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const onFiles = async (files: FileList | null) => {
    for (const file of Array.from(files ?? [])) { try { const m = await upload.mutateAsync(file); setPhotos(p => [...p, m]); } catch { /* shown below */ } }
    if (fileRef.current) fileRef.current.value = '';
  };
  const submit = async () => {
    const pos = await position();
    advance.mutate({ id: job.id, step: 'complete', body: { photoMediaIds: photos.map(p => p.id), report: report || undefined, ...(pos ?? {}) } }, { onSuccess: () => { setPhotos([]); setReport(''); onClose(); } });
  };
  return (
    <Dialog open={open} onClose={onClose} title={t('completeTitle')} actions={<>
      <button type="button" className="btn btn-tertiary" onClick={onClose}>{t('cancel')}</button>
      <button type="button" className="btn btn-primary" disabled={advance.isPending || upload.isPending} onClick={() => void submit()}>{t('completeCta')}</button>
    </>}>
      <div className="nl-field">
        <span className="nl-label">{t('photos')}</span>
        <input ref={fileRef} type="file" hidden multiple accept="image/jpeg,image/png,image/heic,image/webp" onChange={e => void onFiles(e.target.files)} />
        <button type="button" className="btn btn-secondary" disabled={upload.isPending} onClick={() => fileRef.current?.click()}>{upload.isPending ? t('uploading') : t('addPhotos')}</button>
        {photos.length ? <ul className="nl-appt-files">{photos.map(p => <li key={p.id}>{p.fileName}</li>)}</ul> : <div className="nl-hint">{t('noPhotosWarning')}</div>}
        {upload.isError ? <div role="alert" className="nl-error">{t('uploadError', { name: '' })}</div> : null}
      </div>
      <Field label={t('report')}><TextArea value={report} placeholder={t('reportPlaceholder')} maxLength={4000} onChange={e => setReport(e.target.value)} /></Field>
      {advance.isError ? <Alert tone="error">{t('advanceError')}</Alert> : null}
    </Dialog>
  );
}

const approvalSchema = z.object({
  description: z.string().trim().min(1, APPROVAL_MESSAGES.description).max(160, APPROVAL_MESSAGES.tooLong),
  amount: z.string().refine(v => Math.round(parseFloat(v.replace(/[^0-9.]/g, '')) * 100) > 0, APPROVAL_MESSAGES.amount),
});

function ApprovalDialog({ open, onClose, jobId }: { open: boolean; onClose: () => void; jobId: string }) {
  const t = useAppointmentsT();
  const merchantId = useMerchantId();
  const request = useRequestApproval(merchantId);
  const [v, setV] = useState({ description: '', amount: '' });
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [tried, setTried] = useState(false);
  const r = approvalSchema.safeParse(v);
  const errs: Record<string, string> = {};
  if (!r.success) for (const i of r.error.issues) errs[String(i.path[0])] ??= i.message;
  const server = request.error instanceof ValidationError ? request.error.byField() : {};
  const lm = useLocalizeMessage(QUOTE_MESSAGES_FR);
  const show = (k: 'description' | 'amount', sk: string) => lm((tried || touched[k] ? errs[k] : undefined) ?? server[sk]);
  const count = Object.keys(errs).length;
  const submit = () => {
    setTried(true);
    if (!r.success) return;
    request.mutate({ id: jobId, description: v.description.trim(), amountCents: Math.round(parseFloat(v.amount.replace(/[^0-9.]/g, '')) * 100) }, { onSuccess: () => { setV({ description: '', amount: '' }); setTried(false); setTouched({}); onClose(); } });
  };
  return (
    <Dialog open={open} onClose={onClose} title={t('approvalTitle')} actions={<>
      <button type="button" className="btn btn-tertiary" onClick={onClose}>{t('cancel')}</button>
      <button type="button" className="btn btn-primary" disabled={request.isPending} onClick={submit}>{t('approvalSend')}</button>
    </>}>
      {tried && count > 0 ? <Alert tone="error">{t('attention', { n: count })}</Alert> : null}
      <Field label={t('approvalDesc')} error={show('description', 'description')}><TextInput value={v.description} maxLength={200} onBlur={() => setTouched(x => ({ ...x, description: true }))} onChange={e => setV(x => ({ ...x, description: e.target.value }))} /></Field>
      <Field label={t('approvalAmount')} error={show('amount', 'amountCents')}><TextInput inputMode="decimal" value={v.amount} onBlur={() => setTouched(x => ({ ...x, amount: true }))} onChange={e => setV(x => ({ ...x, amount: e.target.value }))} /></Field>
    </Dialog>
  );
}

/** What the customer paid and is held (incl. GST/HST), then what the business gets after the tax and Northline's fee — the Earnings ledger's figures. */
function EscrowFact({ j }: { j: JobDetail }) {
  const t = useAppointmentsT();
  const f = useFormatters();
  const m = j.escrowMoney;
  const held = m?.heldCents ?? j.priceCents ?? 0;
  const state = m?.state ?? j.escrow;
  const line = state === 'held' ? t('escrowHeld', { money: f.price(held) })
    : state === 'released' ? t('escrowReleased', { money: f.price(held) })
    : state === 'disputed' ? t('escrowOnHold', { money: f.price(held) })
    : state === 'refunded' ? t('escrowRefunded', { money: f.price(held) })
    : t('escrowNone');
  return (
    <>
      <strong>{line}</strong>
      {m && state !== 'refunded' ? <div className="nl-muted">{t('escrowBreakdown', { tax: f.price(m.taxCents), fee: f.price(m.feeCents), net: f.price(m.netCents) })}</div> : null}
    </>
  );
}
