import { useState, type FormEvent } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Alert, Button, Dialog, EmptyState, ErrorState, Field, OptionCard, PageHeader, PageSkeleton, Stars, TextArea, useFormatters } from '@northline/ui';
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

/**
 * /b/$merchantId/reviews — design: reviews. Rating summary (distribution + praise tags) and verified reviews, newest
 * first. Reviews come only from completed bookings / delivered orders and can't be edited; the business replies once,
 * publicly, and may report a review.
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

  if (summary.isPending || list.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (summary.isError || list.isError) {
    return <><PageHeader kicker={t('kicker')} title={t('kicker')} /><ErrorState message={t('loadError')} onRetry={() => { void summary.refetch(); void list.refetch(); }} /></>;
  }
  const s = summary.data;
  const reviews = list.data.pages.flatMap(p => p.items);
  const title = s.count === 0 ? t('titleNone') : t('title', { average: number(s.average, { minimumFractionDigits: 1, maximumFractionDigits: 1 }), count: s.count });

  return (
    <>
      <PageHeader kicker={t('kicker')} title={title} />
      {s.count === 0 ? <EmptyState>{t('empty')}</EmptyState> : (
        <div className="nl-rev-grid">
          <div>
            <Distribution summary={s} />
            <p className="nl-rev-note">{t('twoWay')}</p>
            {!canRespond ? <p className="nl-rev-note">{t('viewOnly', { role: shellT(`role_${role}` as 'role_owner') })}</p> : null}
            {canRespond && s.count >= 3 ? <ReviewSummaryDraftPanel merchantId={merchantId} /> : null}
          </div>
          <div>
            <ul className="nl-rev-list">
              {reviews.map(r => <ReviewItem key={r.id} merchantId={merchantId} review={r} canRespond={canRespond} />)}
            </ul>
            {list.hasNextPage ? (
              <Button variant="secondary" onClick={() => void list.fetchNextPage()} disabled={list.isFetchingNextPage}>{list.isFetchingNextPage ? t('loadingMore') : t('more')}</Button>
            ) : null}
          </div>
        </div>
      )}
    </>
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
            <span className="nl-rev-dist-track" aria-hidden><span className="nl-rev-dist-fill" style={{ width: `${d.percent}%` }} /></span>
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
    <li className="nl-rev-item">
      <div className="nl-rev-head">
        <span><Stars rating={review.rating} /> · {who} · {review.jobLabel ? t('verified', { job: review.jobLabel }) : t('verifiedNoJob')}</span>
        <span className="nl-rev-when"><time dateTime={review.createdAt}>{ago(review.createdAt)}</time></span>
      </div>
      {review.text ? <p className="nl-rev-text">{review.text}</p> : null}
      {review.reply ? (
        <div className="nl-rev-replied">{t('replied', { text: review.reply })}</div>
      ) : canRespond ? <ReplyBox merchantId={merchantId} review={review} who={who} /> : null}
      <div className="nl-rev-actions">
        {review.reportedAt ? <span className="tag tag-neutral">{t('reported')}</span>
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
