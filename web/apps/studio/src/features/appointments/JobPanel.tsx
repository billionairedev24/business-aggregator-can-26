import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { z } from 'zod';
import { Alert, Dialog, ErrorState, Field, Skeleton, TextArea, TextInput, useFormatters, useLocale, type Locale, timeZone } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { screenHref } from '../shell/nav';
import { ValidationError } from '../../lib/http';
import { useSession } from '../../lib/session';
import { clock } from '../../lib/time';
import { jobQuery, useAdvanceJob, useRequestApproval, useUploadMedia, type JobDetail, type Media, type Step } from './api';
import { useAppointmentsT } from './messages';

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
export function stage(j: JobDetail, t: T, money: (c: number) => string): { cta: string; step?: Step; hint: string } {
  switch (j.state) {
    case 'confirmed': return { cta: t('startTravel'), step: 'en-route', hint: t('hintStart') };
    case 'en_route': return { cta: t('checkIn'), step: 'on-site', hint: t('hintCheckIn') };
    case 'on_site': return { cta: t('complete'), step: 'complete', hint: t('hintComplete') };
    case 'completed': return { cta: t('waiting'), hint: t('hintWaiting', { money: money(j.priceCents ?? 0) }) };
    case 'signed_off': return { cta: t('signedOff'), hint: t('hintSigned') };
    default: return { cta: t(`state_${j.state}`), hint: t('hintOther', { state: t(`state_${j.state}`).toLowerCase() }) };
  }
}

export function JobPanel({ jobId }: { jobId: string | null }) {
  const t = useAppointmentsT();
  const merchantId = useMerchantId();
  const q = useQuery({ ...jobQuery(merchantId, jobId ?? ''), enabled: !!jobId });
  if (!jobId) return <section><p className="nl-muted">{t('noJob')}</p></section>;
  if (q.isPending) return <section aria-busy="true"><Skeleton height={28} width="70%" style={{ marginBottom: 14 }} />{Array.from({ length: 5 }, (_, i) => <Skeleton key={i} height={20} style={{ marginBottom: 10 }} />)}</section>;
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
  const st = stage(j, t, c => f.money(c));
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
  return (
    <section aria-labelledby="appt-job">
      <h2 id="appt-job" className="nl-h2 nl-appt-h2">{title}{j.memberName && j.memberUserId !== me ? <span className="nl-muted"> ({j.memberName})</span> : null}</h2>
      <dl className="nl-appt-facts">
        {j.customer ? <><dt>{t('customer')}</dt><dd>{j.customer.reliability != null ? t('customerLine', { name: j.customer.name, score: j.customer.reliability.toFixed(1), jobs: j.customer.pastJobs }) : t('customerLineNoScore', { name: j.customer.name, jobs: j.customer.pastJobs })}</dd></> : null}
        {j.addressLine || j.access ? <><dt>{t('where')}</dt><dd>{[j.addressLine, j.access].filter(Boolean).join(' · ')}</dd></> : null}
        {j.vehicle ? <><dt>{t('vehicle')}</dt><dd>{j.vehicle}</dd></> : null}
        <dt>{t('escrow')}</dt><dd><strong>{j.escrow === 'held' ? t('escrowHeld', { money: f.money(j.priceCents ?? 0) }) : j.escrow === 'released' ? t('escrowReleased', { money: f.money(j.priceCents ?? 0) }) : t('escrowNone')}</strong></dd>
      </dl>
      {j.customerNote ? <div className="nl-appt-quote">“{j.customerNote}”</div> : null}
      {j.approvals.length > 0 ? (
        <div className="nl-appt-approvals">
          <h3 className="nl-appt-cv-h">{t('approvalsTitle')}</h3>
          <ul>{j.approvals.map(a => <li key={a.id}><span>{a.description} · {f.money(a.amountCents)}</span><span className={`tag ${a.state === 'approved' ? 'tag-accent' : a.state === 'declined' ? 'tag-accent-2' : 'tag-neutral'}`}>{t(`approval_${a.state}`)}</span></li>)}</ul>
        </div>
      ) : null}
      {canOperate ? (
        <div className="nl-appt-actions nl-appt-jobactions">
          <button type="button" className="btn btn-primary" disabled={!st.step || advance.isPending} onClick={() => void go()}>{st.cta}</button>
          <button type="button" className="btn btn-secondary" onClick={toMessages}>{t('message')}</button>
          {['confirmed', 'en_route', 'on_site'].includes(j.state) ? <button type="button" className="btn btn-secondary" onClick={() => setApproving(true)}>{t('extraParts')}</button> : null}
          <button type="button" className="btn btn-ghost" onClick={toMessages}>{t('reschedule')}</button>
        </div>
      ) : null}
      <div className="nl-appt-hint" aria-live="polite">{st.hint}</div>
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
      <button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button>
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

const APPROVAL_MESSAGES = { description: 'Describe the extra parts or work.', amount: 'Enter an amount.' } as const;
const approvalSchema = z.object({
  description: z.string().trim().min(1, APPROVAL_MESSAGES.description).max(160, 'At most 160 characters.'),
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
  const show = (k: 'description' | 'amount', sk: string) => (tried || touched[k] ? errs[k] : undefined) ?? server[sk];
  const count = Object.keys(errs).length;
  const submit = () => {
    setTried(true);
    if (!r.success) return;
    request.mutate({ id: jobId, description: v.description.trim(), amountCents: Math.round(parseFloat(v.amount.replace(/[^0-9.]/g, '')) * 100) }, { onSuccess: () => { setV({ description: '', amount: '' }); setTried(false); setTouched({}); onClose(); } });
  };
  return (
    <Dialog open={open} onClose={onClose} title={t('approvalTitle')} actions={<>
      <button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button>
      <button type="button" className="btn btn-primary" disabled={request.isPending} onClick={submit}>{t('approvalSend')}</button>
    </>}>
      {tried && count > 0 ? <Alert tone="error">{t('attention', { n: count })}</Alert> : null}
      <Field label={t('approvalDesc')} error={show('description', 'description')}><TextInput value={v.description} maxLength={200} onBlur={() => setTouched(x => ({ ...x, description: true }))} onChange={e => setV(x => ({ ...x, description: e.target.value }))} /></Field>
      <Field label={t('approvalAmount')} error={show('amount', 'amountCents')}><TextInput inputMode="decimal" value={v.amount} onBlur={() => setTouched(x => ({ ...x, amount: true }))} onChange={e => setV(x => ({ ...x, amount: e.target.value }))} /></Field>
    </Dialog>
  );
}
