import { useState, type FormEvent } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Alert, BoHeader, Button, Dialog, EmptyState, ErrorState, Field, KpiStrip, OptionCard, PageSkeleton, Stars, StatusTag, TextArea, UnderlineTabs, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { useAgo } from '../messages/time';
import { ReportReason, reviewSummaryQuery, reviewsQuery, useReplyToReview, useReportReview, type Review, type ReviewSummary } from './api';
import { useReviewsT } from './messages';
import { firstErrors, ReplyForm, ReportForm, useMessageT } from './validation';
import './reviews.css';
import { ReviewSummaryDraftPanel } from '../writing/WritingHelp';

const CAN_RESPOND = new Set(['owner', 'technician', 'cook', 'manager', 'staff']);

type Filter = 'all' | 'reply' | 'replied' | 'low';

/**
 * /b/$merchantId/reviews (S-176, on the Studio dashboard's language): the page head, the KPI strip (rating, reviews,
 * replied, waiting for a reply), the rating summary (distribution + praise tags) beside the reviews with All / Needs a
 * reply / Replied / 1–3 stars tabs, newest first. Reviews come only from completed bookings / delivered orders and
 * can't be edited; the business replies once, publicly, and may report a review.
 */
export function ReviewsScreen() {
  const t = useReviewsT();
  const shellT = useShellT();
  const merchantId = useMerchantId();
  const role = useRole();
  const { number } = useFormatters();
  const summary = useQuery(reviewSummaryQuery(merchantId));
  const list = useInfiniteQuery(reviewsQuery(merchantId));
  const canRespond = CAN_RESPOND.has(role);
  const [filter, setFilter] = useState<Filter>('all');

  if (summary.isPending || list.isPending) return <><BoHeader title={t('kicker')} /><PageSkeleton kpis={4} rows={4} /></>;
  if (summary.isError || list.isError) {
    return <><BoHeader title={t('kicker')} /><ErrorState message={t('loadError')} onRetry={() => { void summary.refetch(); void list.refetch(); }} /></>;
  }
  const s = summary.data;
  const reviews = list.data.pages.flatMap(p => p.items);
  const avg = number(s.average, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const title = s.count === 0 ? t('titleNone') : t('title', { average: avg, count: s.count });
  const waiting = reviews.filter(r => !r.reply).length;
  const replied = reviews.length - waiting;
  const match: Record<Filter, (r: Review) => boolean> = { all: () => true, reply: r => !r.reply, replied: r => !!r.reply, low: r => r.rating <= 3 };
  const shown = reviews.filter(match[filter]);

  return (
    <div className="nl-rev-page">
      <BoHeader title={t('kicker')} sub={s.count === 0 ? undefined : t('sub')} />
      {s.count === 0 ? <section className="bo-card" aria-labelledby="rev-none"><h2 className="bo-card-title" id="rev-none">{t('titleNone')}</h2><EmptyState>{t('empty')}</EmptyState></section> : (
        <>
          <KpiStrip label={t('kpis')} items={[
            { key: 'avg', label: t('kpiRating'), value: <span className="nl-rev-kpi-rating">{avg}<Stars rating={s.average} /></span> },
            { key: 'count', label: t('kpiCount'), value: number(s.count) },
            { key: 'replied', label: t('kpiReplied'), value: reviews.length ? number(replied / reviews.length, { style: 'percent', maximumFractionDigits: 0 }) : '—', delta: t('kpiOfShown', { count: reviews.length }) },
            { key: 'waiting', label: t('kpiWaiting'), value: number(waiting), ...(waiting ? { delta: t('kpiWaitingDelta'), trend: 'down' as const } : { delta: t('kpiWaitingNone'), trend: 'up' as const }) },
          ]} />
          <div className="nl-rev-grid">
            <section className="bo-card nl-rev-summary" aria-labelledby="rev-summary">
              <h2 className="bo-card-title" id="rev-summary">{title}</h2>
              <Distribution summary={s} />
              <p className="nl-rev-note">{t('twoWay')}</p>
              {!canRespond ? <p className="nl-rev-note">{t('viewOnly', { role: shellT(`role_${role}` as 'role_owner') })}</p> : null}
              {canRespond && s.count >= 3 ? <ReviewSummaryDraftPanel merchantId={merchantId} /> : null}
            </section>
            <section className="bo-card nl-rev-main" aria-labelledby="rev-list">
              <h2 className="nl-sr-only" id="rev-list">{t('listTitle')}</h2>
              <UnderlineTabs aria-label={t('show')} value={filter} onChange={setFilter} options={[
                { value: 'all', label: t('tabAll'), count: number(reviews.length) },
                { value: 'reply', label: t('tabReply'), count: number(waiting) },
                { value: 'replied', label: t('tabReplied'), count: number(replied) },
                { value: 'low', label: t('tabLow'), count: number(reviews.filter(match.low).length) },
              ]} />
              {shown.length ? (
                <ul className="nl-rev-list">
                  {shown.map(r => <ReviewItem key={r.id} merchantId={merchantId} review={r} canRespond={canRespond} />)}
                </ul>
              ) : <p className="nl-rev-note">{t('noneHere')}</p>}
              {list.hasNextPage ? (
                <div><Button variant="secondary" onClick={() => void list.fetchNextPage()} disabled={list.isFetchingNextPage}>{list.isFetchingNextPage ? t('loadingMore') : t('more')}</Button></div>
              ) : null}
            </section>
          </div>
        </>
      )}
    </div>
  );
}

function Distribution({ summary }: { summary: ReviewSummary }) {
  const t = useReviewsT();
  const tagLabel = (tag: string) => (t(`tag_${tag}` as 'tag_on_time') === `tag_${tag}` ? tag.replace(/_/g, ' ') : t(`tag_${tag}` as 'tag_on_time'));
  return (
    <>
      <ul className="nl-rev-dist" aria-label={t('distribution')}>
        {summary.distribution.map(d => (
          <li key={d.stars} className="nl-rev-dist-row">
            <span className="nl-rev-dist-n" aria-hidden>{d.stars}</span>
            <span className="nl-rev-dist-track" aria-hidden><span className="nl-rev-dist-fill" style={{ ['--pct' as string]: d.percent }} /></span>
            <span className="nl-rev-dist-c" aria-hidden>{d.count}</span>
            <span className="nl-sr-only">{t('starsRow', { stars: d.stars, count: d.count })}</span>
          </li>
        ))}
      </ul>
      {summary.praise.length ? (
        <div className="nl-rev-praise">{t('praise', { tags: summary.praise.map(p => t('praiseItem', { tag: tagLabel(p.tag), percent: p.percent })).join(' · ') })}</div>
      ) : null}
    </>
  );
}

function ReviewItem({ merchantId, review, canRespond }: { merchantId: string; review: Review; canRespond: boolean }) {
  const t = useReviewsT();
  const ago = useAgo();
  const [reporting, setReporting] = useState(false);
  const who = review.authorName ?? t('anonymous');
  return (
    <li className="nl-rev-item" data-waiting={!review.reply ? '' : undefined}>
      <div className="nl-rev-head">
        <span className="nl-rev-avatar" aria-hidden>{who.trim().charAt(0).toUpperCase()}</span>
        <span className="nl-rev-who"><strong>{who}</strong><span className="nl-rev-meta"><Stars rating={review.rating} /> · {review.jobLabel ? t('verified', { job: review.jobLabel }) : t('verifiedNoJob')}</span></span>
        <span className="nl-rev-when"><time dateTime={review.createdAt}>{ago(review.createdAt)}</time></span>
      </div>
      {review.text ? <p className="nl-rev-text">{review.text}</p> : null}
      {review.reply ? (
        <div className="nl-rev-replied">{t('replied', { text: review.reply })}</div>
      ) : canRespond ? <ReplyBox merchantId={merchantId} review={review} who={who} /> : null}
      <div className="nl-rev-actions">
        {review.hiddenAt ? <StatusTag tone="warning">{t('hidden')}</StatusTag> : null}
        {review.editedAt ? <StatusTag tone="neutral">{t('edited')}</StatusTag> : null}
        {review.reportedAt ? <StatusTag tone="neutral">{t('reported')}</StatusTag>
          : canRespond ? <Button variant="ghost" className="nl-rev-report" onClick={() => setReporting(true)}>{t('report')}</Button> : null}
      </div>
      {reporting ? <ReportDialog merchantId={merchantId} review={review} onClose={() => setReporting(false)} /> : null}
    </li>
  );
}

function ReplyBox({ merchantId, review, who }: { merchantId: string; review: Review; who: string }) {
  const t = useReviewsT();
  const mt = useMessageT();
  const reply = useReplyToReview(merchantId);
  const [text, setText] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState<string>();
  const error = serverError ?? (submitted ? firstErrors(ReplyForm.safeParse({ text })).text : undefined);
  const id = `reply-${review.id}`;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setSubmitted(true);
    setServerError(undefined);
    if (!ReplyForm.safeParse({ text }).success) return;
    try {
      await reply.mutateAsync({ id: review.id, text });
    } catch (err) {
      setServerError(err instanceof ValidationError ? err.byField().text ?? err.errors[0]?.message : t('actionError'));
    }
  }

  return (
    <form className="nl-rev-reply" onSubmit={e => void submit(e)} noValidate>
      <div className="nl-rev-reply-row">
        <input className="input" id={id} value={text} placeholder={t('replyPlaceholder')} aria-label={t('replyLabel', { name: who })}
          aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-err` : undefined}
          onChange={e => { setText(e.target.value); setServerError(undefined); }} />
        <Button type="submit" variant="secondary" disabled={reply.isPending}>{reply.isPending ? t('replying') : t('reply')}</Button>
      </div>
      {error ? <div id={`${id}-err`} role="alert" className="nl-error">{mt(error)}</div> : null}
    </form>
  );
}

function ReportDialog({ merchantId, review, onClose }: { merchantId: string; review: Review; onClose: () => void }) {
  const t = useReviewsT();
  const mt = useMessageT();
  const report = useReportReview(merchantId);
  const [reason, setReason] = useState<ReportReason | ''>('');
  const [note, setNote] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [failed, setFailed] = useState(false);
  const local = submitted ? firstErrors(ReportForm.safeParse({ reason, note })) : {};
  const errors = { ...local, ...server };
  const count = Object.values(errors).filter(Boolean).length;

  async function submit() {
    setSubmitted(true);
    setServer({});
    setFailed(false);
    const parsed = ReportForm.safeParse({ reason, note });
    if (!parsed.success) return;
    try {
      await report.mutateAsync({ id: review.id, reason: parsed.data.reason, note });
      onClose();
    } catch (err) {
      if (err instanceof ValidationError) setServer(err.byField()); else setFailed(true);
    }
  }

  return (
    <Dialog open onClose={onClose} title={t('reportTitle')} actions={<>
      <Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
      <Button onClick={() => void submit()} disabled={report.isPending}>{report.isPending ? t('sending') : t('sendReport')}</Button>
    </>}>
      <p className="nl-rev-note">{t('reportLede')}</p>
      {submitted && count > 0 ? <Alert tone="error">{t('attention', { count })}</Alert> : null}
      {failed ? <Alert tone="error">{t('actionError')}</Alert> : null}
      <fieldset className="nl-rev-reasons" aria-describedby={errors.reason ? 'report-reason-err' : undefined}>
        <legend className="nl-label">{t('reason')}</legend>
        {ReportReason.options.map(r => (
          <OptionCard key={r} selected={reason === r} title={t(`reason_${r}`)} onClick={() => { setReason(r); setServer({}); }} />
        ))}
        {errors.reason ? <div id="report-reason-err" role="alert" className="nl-error">{mt(errors.reason)}</div> : null}
      </fieldset>
      <Field label={t('note')} note={reason === 'other' ? undefined : t('noteOptional')} error={mt(errors.note)}>
        <TextArea value={note} rows={3} onChange={e => { setNote(e.target.value); setServer({}); }} />
      </Field>
    </Dialog>
  );
}
