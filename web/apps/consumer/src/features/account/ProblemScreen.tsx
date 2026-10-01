import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Chip, EmptyState, ErrorState, Field, SiteLink, Skeleton, TextArea, useFormatters, useLocale } from '@northline/ui';
import { isNotFound, ValidationError } from '@northline/client';
import { useZone } from '../location/regions';
import { signInHref, useViewer } from '../session/api';
import { clock } from '../shop/format';
import { brandName } from './PaymentsTab';
import { CATEGORY_REASON, problemQuery, triage, uploadPhoto, useReport, type ProblemContext, type ProblemKind, type Reported, type Triage, type Upload } from './problemApi';
import { useProblemT, type ProblemT } from './problemMessages';
import { shortDate } from './format';
import { tabHref } from './tabs';

const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'application/pdf'];

/**
 * "Something's wrong" (consumer app `refund`, reached from Orders & bookings, a delivered order or food order, and a
 * completed job): pick the items, the reason, add photos and a note, then "Request $X refund" — which opens a case,
 * never an instant refund — and the case's timeline. Only what is still inside its escrow window can be reported.
 */
export function ProblemScreen({ kind, id }: { kind: string; id: string }) {
  const t = useProblemT();
  const { user, loading } = useViewer();
  const context = useQuery({ ...problemQuery(kind, id), enabled: !!user });
  const [done, setDone] = useState<{ reported: Reported; photo: boolean }>();
  const here = `/account/problem/${kind}/${id}`;
  if (loading || (user && context.isPending)) return <ProblemSkeleton />;
  if (!user) {
    return <Page><EmptyState action={<SiteLink href={signInHref(here)} className="btn btn-primary">{t('signInAction')}</SiteLink>}>{t('signIn')}</EmptyState></Page>;
  }
  if (context.isError) {
    return <Page>{isNotFound(context.error) ? <EmptyState action={<SiteLink href="/account/orders" className="btn btn-primary">{t('backToOrders')}</SiteLink>}>{t('notFound')}</EmptyState>
      : <ErrorState message={t('loadError')} onRetry={() => void context.refetch()} />}</Page>;
  }
  const c = context.data!;
  if (done) return <Page><Submitted reported={done.reported} photo={done.photo} /></Page>;
  if (c.status !== 'open') {
    return (
      <Page about={about(c, t)}>
        <EmptyState action={<SiteLink href={c.status === 'not_yet' || c.status === 'not_paid' ? '/account/orders' : tabHref('help')} className="btn btn-primary">
          {c.status === 'not_yet' || c.status === 'not_paid' ? t('backToOrders') : t('toHelp')}</SiteLink>}>
          {t(`st_${c.status}`)}
        </EmptyState>
      </Page>
    );
  }
  return <Page about={about(c, t)}><ReportForm context={c} onDone={(reported, photo) => setDone({ reported, photo })} /></Page>;
}

const about = (c: ProblemContext, t: ProblemT) =>
  c.kind === 'booking' ? t('about_booking', { title: c.title, ref: c.ref ?? '' }) : t(c.kind === 'food' ? 'about_food' : 'about_order', { ref: c.ref ?? '' });

function Page({ about, children }: { about?: string; children: React.ReactNode }) {
  const t = useProblemT();
  return (
    <div className="nl-page nl-problem">
      <h1 className="nl-acct-h1">{t('title')}</h1>
      {about ? <p className="nl-small nl-muted">{about}</p> : null}
      {children}
    </div>
  );
}

function ReportForm({ context: c, onDone }: { context: ProblemContext; onDone: (r: Reported, photo: boolean) => void }) {
  const t = useProblemT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const report = useReport();
  const open = c.items.filter(i => i.status === 'open');
  const [items, setItems] = useState<string[]>(() => (c.kind === 'booking' || open.length === 1 ? open.map(i => i.ref) : []));
  const [reason, setReason] = useState<string>();
  const [note, setNote] = useState('');
  const [photos, setPhotos] = useState<Upload[]>([]);
  const [uploading, setUploading] = useState(false);
  const [suggestion, setSuggestion] = useState<Triage | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const file = useRef<HTMLInputElement>(null);
  const chosen = c.items.filter(i => items.includes(i.ref));
  const total = chosen.reduce((sum, i) => sum + i.amountCents + i.taxCents, 0);
  const suggestedReason = suggestion ? CATEGORY_REASON[suggestion.category] : undefined;

  const toggle = (ref: string) => setItems(xs => (xs.includes(ref) ? xs.filter(x => x !== ref) : [...xs, ref]));
  const askTriage = async () => {
    if (note.trim().length < 10) return;
    const s = await triage(note.trim(), c.kind as ProblemKind);
    setSuggestion(s && CATEGORY_REASON[s.category] && c.reasons.includes(CATEGORY_REASON[s.category]!) ? s : null);
  };
  const addPhoto = async (f: File | undefined) => {
    if (!f) return;
    if (photos.length >= 5) { setErrors(e => ({ ...e, photos: t('v_photos') })); return; }
    if (!PHOTO_TYPES.includes(f.type) || f.size > 10 * 1024 * 1024) { setErrors(e => ({ ...e, photos: t('v_photo') })); return; }
    setUploading(true);
    try { const u = await uploadPhoto(f); setPhotos(p => [...p, u]); setErrors(({ photos: _, ...rest }) => rest); } catch { setErrors(e => ({ ...e, photos: t('v_photo') })); } finally { setUploading(false); }
  };
  const submit = () => {
    const next: Record<string, string> = {};
    if (!items.length) next.items = t('v_items');
    if (!reason) next.reason = t('v_reason');
    if (note.length > 1000) next.note = t('v_note');
    setErrors(next);
    if (Object.keys(next).length) return;
    report.mutate({
      kind: c.kind, id: c.id, items, reason: reason!, note: note.trim() || undefined, attachmentIds: photos.map(p => p.id),
      ...(suggestion && suggestedReason === reason ? { triageCategory: suggestion.category, triageSummary: suggestion.summary ?? undefined } : {}),
    }, {
      onSuccess: r => onDone(r, photos.length > 0),
      onError: e => setErrors(e instanceof ValidationError ? Object.fromEntries(e.errors.map(x => [x.field === 'items' || x.field === 'reason' || x.field === 'note' ? x.field : '_form', x.message])) : { _form: t('submitError') }),
    });
  };

  return (
    <div className="nl-problem-form">
      <p className="nl-acct-lede">{c.kind === 'booking' ? t('introService') : t('intro')}</p>
      {c.reportBy ? <p className="nl-small nl-muted">{t('reportBy', { date: shortDate(c.reportBy, locale) })}</p> : null}
      <fieldset className="nl-problem-set">
        <legend className="nl-problem-kicker">{t('items')}</legend>
        <div className="nl-problem-items">
          {c.items.map(i => {
            const can = i.status === 'open';
            const on = items.includes(i.ref);
            return (
              <label key={i.ref} className={on ? 'nl-problem-item is-on' : 'nl-problem-item'} aria-disabled={!can}>
                <input type="checkbox" checked={on} disabled={!can} onChange={() => toggle(i.ref)} />
                <span className="nl-problem-item-name">{i.qty > 1 ? `${i.title} ×${i.qty}` : i.title}{c.kind === 'order' && i.merchantName ? <span className="nl-small nl-muted nl-block">{i.merchantName}</span> : null}</span>
                <span className="nl-small">{can ? money(i.amountCents + i.taxCents) : t(`item_${i.status}` as Parameters<ProblemT>[0])}</span>
              </label>
            );
          })}
        </div>
        {errors.items ? <p className="nl-error" role="alert">{errors.items}</p> : null}
      </fieldset>
      <fieldset className="nl-problem-set">
        <legend className="nl-problem-kicker">{t('reason')}</legend>
        <div className="nl-chips">
          {c.reasons.map(r => <Chip key={r} selected={reason === r} onClick={() => setReason(r)}>{t(`r_${r}` as Parameters<ProblemT>[0])}</Chip>)}
        </div>
        {suggestion && suggestedReason && suggestedReason !== reason ? (
          <p className="nl-small nl-problem-suggest" role="status">
            {t('suggested', { reason: t(`r_${suggestedReason}` as Parameters<ProblemT>[0]) })}{' '}
            <Button type="button" variant="ghost" onClick={() => setReason(suggestedReason)}>{t('suggestedApply', { reason: t(`r_${suggestedReason}` as Parameters<ProblemT>[0]) })}</Button>
          </p>
        ) : null}
        {errors.reason ? <p className="nl-error" role="alert">{errors.reason}</p> : null}
      </fieldset>
      <input ref={file} type="file" accept={PHOTO_TYPES.join(',')} hidden onChange={e => { void addPhoto(e.target.files?.[0]); e.target.value = ''; }} />
      <Button type="button" variant="secondary" block className="nl-problem-photo" disabled={uploading} aria-busy={uploading} onClick={() => file.current?.click()}>{t('addPhoto')}</Button>
      {photos.length ? (
        <ul className="nl-problem-photos" aria-label={t('photos', { count: photos.length })}>
          {photos.map(p => <li key={p.id}><span>{p.fileName}</span> <Button type="button" variant="ghost" aria-label={t('removePhoto', { name: p.fileName })} onClick={() => setPhotos(ps => ps.filter(x => x.id !== p.id))}>×</Button></li>)}
        </ul>
      ) : null}
      {errors.photos ? <p className="nl-error" role="alert">{errors.photos}</p> : null}
      <Field label={t('noteLabel')} error={errors.note}>
        <TextArea value={note} rows={3} placeholder={t('note')} onChange={e => setNote(e.target.value)} onBlur={() => void askTriage()} />
      </Field>
      {errors._form ? <p className="nl-error" role="alert">{errors._form}</p> : null}
      <Button type="button" block className="nl-problem-submit" disabled={report.isPending} aria-busy={report.isPending} onClick={submit}>{t('request', { amount: money(total) })}</Button>
    </div>
  );
}

/** "Request received" and the four steps (consumer app `refundDone`). */
function Submitted({ reported, photo }: { reported: Reported; photo: boolean }) {
  const t = useProblemT();
  const { locale } = useLocale();
  const zone = useZone();
  const { money, date } = useFormatters();
  const first = reported.refunds[0];
  const numbers = reported.refunds.map(r => r.number);
  const card = reported.card ? `${brandName(reported.card.brand)} ··${reported.card.last4}` : t('yourCard');
  const merchants = [...new Set(reported.refunds.map(r => r.merchantName).filter(Boolean))].join(', ');
  return (
    <div className="nl-problem-done">
      <div className="nl-acct-panel nl-problem-card">
        <div>
          <div className="nl-problem-kicker">{numbers.length > 1 ? t('casesInReview', { numbers: numbers.join(', ') }) : t('caseInReview', { number: numbers[0] ?? reported.caseCode })}</div>
          <div className="nl-problem-received">{t('received', { amount: money(reported.totalCents) })}</div>
          <div className="nl-small nl-muted">{t('paused')}</div>
        </div>
      </div>
      <ol className="nl-problem-steps">
        <li className="is-done"><span className="nl-problem-dot" aria-hidden /><span><strong>{t('step_submitted')}</strong> · {t('stepd_submitted', { time: clock(reported.submittedAt, locale, zone), attached: t(photo ? 'attachedPhoto' : 'attachedReason') })}</span></li>
        <li className="is-current"><span className="nl-problem-dot" aria-hidden /><span><strong>{t('step_seller')}</strong> · {t('stepd_seller', { merchant: merchants, date: first?.respondBy ? date(first.respondBy, 'dateTime') : '' })}</span></li>
        <li><span className="nl-problem-dot" aria-hidden /><span><strong>{t('step_northline')}</strong> · {t('stepd_northline')}</span></li>
        <li><span className="nl-problem-dot" aria-hidden /><span><strong>{t('step_refund')}</strong> · {t('stepd_refund', { card })}</span></li>
      </ol>
      <p className="nl-small nl-muted">{t('notifyNote')}</p>
      <div className="nl-acct-actions">
        <SiteLink href="/account/orders" className="btn btn-primary">{t('backToOrders')}</SiteLink>
        <SiteLink href={tabHref('help', first ? { case: first.id } : {})} className="btn btn-ghost">{t('toHelp')}</SiteLink>
      </div>
    </div>
  );
}

export function ProblemSkeleton() {
  const t = useProblemT();
  return (
    <div className="nl-page nl-problem" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width="50%" height={40} />
      {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} height={44} style={{ marginTop: 12, maxWidth: 560 }} />)}
    </div>
  );
}
