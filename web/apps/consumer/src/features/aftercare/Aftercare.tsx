import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, ErrorState, Field, Skeleton, TextArea, useFormatters } from '@northline/ui';
import { isNotFound, newIdempotencyKey, ValidationError } from '@northline/client';
import { StripeCard } from '../booking/StripeCard';
import {
  confirmTip, editReview, etaQuery, postReview, reviewQuery, startTip, TAGS, tipsQuery,
  type Review, type ReviewKind, type Target, type TipStarted,
} from './api';
import { useAftercareT } from './messages';

type T = ReturnType<typeof useAftercareT>;

/**
 * Reviewing what was booked or ordered (mobile gaps part 2, design 06 / 01 C11 · B9): one form per business of the
 * booking or order, stars, praise tags and words; the posted review with its 24-hour edit window, the business's reply
 * and whether trust & safety hid it. 422 messages arrive in the page's language.
 */
export function ReviewPanel({ kind, id }: { kind: ReviewKind; id: string }) {
  const t = useAftercareT();
  const q = useQuery(reviewQuery(kind, id));
  if (q.isPending) return <Skeleton height={140} />;
  if (q.isError) return isNotFound(q.error) ? null : <ErrorState message={t('reviewError')} onRetry={() => void q.refetch()} />;
  return <>{q.data.targets.map(target => <ReviewTarget key={target.merchantId} kind={kind} id={id} target={target} />)}</>;
}

function ReviewTarget({ kind, id, target }: { kind: ReviewKind; id: string; target: Target }) {
  const t = useAftercareT();
  const { date } = useFormatters();
  const [editing, setEditing] = useState(false);
  const review = target.review;
  const editable = !!review?.editUntil && new Date(review.editUntil).getTime() > Date.now();
  return (
    <section className="nl-after" aria-labelledby={`rv-${target.merchantId}`}>
      <h2 id={`rv-${target.merchantId}`} className="nl-after-h2">{t('reviewTitle', { name: target.merchantName })}</h2>
      {target.status === 'not_yet' ? <p className="nl-after-muted">{t('notYet', { name: target.merchantName })}</p>
        : target.status === 'closed' ? <p className="nl-after-muted">{t('closed')}</p>
          : review && !editing ? (
            <PostedReview t={t} review={review} name={target.merchantName}
              footer={editable ? (
                <>
                  <p className="nl-after-muted">{t('editableUntil', { time: date(review.editUntil!, 'dateTime') })}</p>
                  <button type="button" className="btn btn-ghost" onClick={() => setEditing(true)}>{t('edit')}</button>
                </>
              ) : review.reply ? null : <p className="nl-after-muted">{t('locked')}</p>} />
          ) : <ReviewForm kind={kind} id={id} target={target} existing={editing ? review ?? undefined : undefined} onDone={() => setEditing(false)} />}
    </section>
  );
}

function PostedReview({ t, review, name, footer }: { t: T; review: Review; name: string; footer: React.ReactNode }) {
  return (
    <div className="nl-after-posted">
      <p className="nl-after-stars" aria-label={t('star', { count: review.rating })}>{'★'.repeat(review.rating)}{'☆'.repeat(5 - review.rating)}</p>
      {review.tags.length ? <p className="nl-after-muted">{review.tags.map(tag => t(`tag_${tag}` as 'tag_on_time')).join(' · ')}</p> : null}
      {review.text ? <blockquote className="nl-after-quote">{review.text}</blockquote> : null}
      {review.screened ? <p className="nl-after-muted">{t('screened')}</p> : null}
      {review.hidden ? <p className="nl-after-muted">{t('hidden')}</p> : null}
      {review.reply ? <p className="nl-after-reply">{t('replied', { name, reply: review.reply })}</p> : null}
      {footer}
    </div>
  );
}

function ReviewForm({ kind, id, target, existing, onDone }: { kind: ReviewKind; id: string; target: Target; existing?: Review; onDone: () => void }) {
  const t = useAftercareT();
  const qc = useQueryClient();
  const [rating, setRating] = useState(existing?.rating ?? 0);
  const [tags, setTags] = useState<ReadonlySet<string>>(new Set(existing?.tags ?? []));
  const [text, setText] = useState(existing?.text ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const toggle = (tag: string) => setTags(s => { const n = new Set(s); if (n.has(tag)) n.delete(tag); else n.add(tag); return n; });
  const submit = async () => {
    setBusy(true); setErrors({}); setError(undefined);
    const body = { rating, tags: [...tags], ...(text.trim() ? { text: text.trim() } : {}) };
    try {
      if (existing) await editReview(existing.id, body);
      else await postReview({ kind, id, merchantId: target.merchantId, ...body });
      await qc.invalidateQueries({ queryKey: ['me', 'reviews', kind, id] });
      onDone();
    } catch (e) {
      if (e instanceof ValidationError) setErrors(e.byField());
      else setError(e instanceof Error && e.message ? e.message : t('reviewError'));
    } finally { setBusy(false); }
  };
  return (
    <form className="nl-after-form" onSubmit={e => { e.preventDefault(); void submit(); }}>
      <p className="nl-after-muted">{t('reviewLede')}</p>
      <div role="radiogroup" aria-label={t('stars')} className="nl-after-starpick">
        {[1, 2, 3, 4, 5].map(n => (
          <button key={n} type="button" role="radio" aria-checked={rating === n} aria-label={t('star', { count: n })}
            className="nl-after-star" onClick={() => setRating(n)}>{n <= rating ? '★' : '☆'}</button>
        ))}
      </div>
      {errors.rating ? <p className="nl-error" role="alert">{errors.rating}</p> : null}
      <fieldset className="nl-chips nl-after-tags"><legend>{t('stoodOut')}</legend>
        {TAGS[kind].map(tag => <button key={tag} type="button" className="nl-chip" aria-pressed={tags.has(tag)} onClick={() => toggle(tag)}>{t(`tag_${tag}` as 'tag_on_time')}</button>)}
      </fieldset>
      {errors.tags ? <p className="nl-error" role="alert">{errors.tags}</p> : null}
      <Field label={t('text')} error={errors.text}>
        <TextArea value={text} onChange={e => setText(e.target.value)} placeholder={t('textHint')} rows={3} maxLength={1000} aria-invalid={!!errors.text} />
      </Field>
      {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
      <button type="submit" className="btn btn-primary" disabled={busy || rating === 0} aria-busy={busy}>{busy ? t('posting') : existing ? t('save') : t('submit')}</button>
    </form>
  );
}

const AMOUNTS = [200, 300, 500, 800] as const;

/**
 * Tipping the courier after the delivery (design 01 B9): what was tipped at checkout, then $2 · $3 · $5 · $8 — 100 % to
 * the courier, its own card payment (Stripe's Payment Element, or nothing to collect with the local stand-in).
 */
export function CourierTip({ orderId }: { orderId: string }) {
  const t = useAftercareT();
  const { money } = useFormatters();
  const qc = useQueryClient();
  const q = useQuery(tipsQuery(orderId));
  const [amount, setAmount] = useState<number>(AMOUNTS[1]);
  const [started, setStarted] = useState<TipStarted>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const key = useRef(newIdempotencyKey());
  if (q.isPending || q.isError) return null;
  const { items, canTip, courierFirstName } = q.data;
  const sent = items.filter(i => i.source === 'after_delivery' && i.state !== 'pending');
  const atCheckout = items.find(i => i.source === 'checkout');
  if (!canTip && sent.length === 0 && !atCheckout) return null;
  const done = async (tipId: string) => {
    await confirmTip(orderId, tipId);
    await qc.invalidateQueries({ queryKey: ['me', 'tips', orderId] });
    setStarted(undefined);
  };
  const send = async () => {
    setBusy(true); setError(undefined);
    try {
      const s = await startTip(orderId, { kind: 'amount', value: amount }, key.current);
      key.current = newIdempotencyKey();
      if (s.provider === 'stripe' && s.tip.clientSecret && s.publishableKey) setStarted(s);
      else await done(s.tip.id);
    } catch (e) {
      setError(e instanceof ValidationError ? e.errors[0]?.message : e instanceof Error && e.message ? e.message : t('tipError'));
    } finally { setBusy(false); }
  };
  return (
    <section className="nl-after" aria-labelledby="tip-title">
      <h2 id="tip-title" className="nl-after-h2">{courierFirstName ? t('tipTitle', { name: courierFirstName }) : t('tipTitleNoName')}</h2>
      {atCheckout ? <p className="nl-after-muted">{t('tipCheckout', { amount: money(atCheckout.amountCents) })}</p> : null}
      {sent.map(s => <p key={s.id} role="status">{t('tipSent', { amount: money(s.amountCents) })}</p>)}
      {canTip && !started ? (
        <>
          <p className="nl-after-muted">{t('tipLede')}</p>
          <div role="radiogroup" aria-label={t('tipAmount')} className="nl-chips nl-after-tags">
            {AMOUNTS.map(a => <button key={a} type="button" role="radio" className="nl-chip" aria-checked={amount === a} onClick={() => setAmount(a)}>{money(a, { whole: true })}</button>)}
          </div>
          <button type="button" className="btn btn-primary" disabled={busy} aria-busy={busy} onClick={() => void send()}>{t('tipSend', { amount: money(amount) })}</button>
        </>
      ) : null}
      {started ? (
        <StripeCard clientSecret={started.tip.clientSecret!} publishableKey={started.publishableKey!} label={t('tipCard')}
          onAuthorized={() => void done(started.tip.id).catch(() => setError(t('tipError')))} onError={setError} />
      ) : null}
      {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
    </section>
  );
}

/**
 * The visit's day-of ETA (design 01 C9 on the web): while the provider is on the way and shares their location,
 * minutes away from a straight-line estimate — never their position — asked again every 30 s.
 */
export function VisitEta({ bookingId, name }: { bookingId: string; name: string }) {
  const t = useAftercareT();
  const { date } = useFormatters();
  const q = useQuery(etaQuery(bookingId));
  if (q.isPending) return <Skeleton height={60} />;
  if (q.isError) return <ErrorState message={t('etaError')} onRetry={() => void q.refetch()} />;
  const e = q.data;
  const text = e.state === 'en_route'
    ? e.minutesAway != null ? t('etaMinutes', { name, minutes: e.minutesAway, km: (e.kmAway ?? 0).toLocaleString(document.documentElement.lang.startsWith('fr') ? 'fr-CA' : 'en-CA') })
      : e.sharing ? t('etaSharing', { name }) : t('etaWaiting', { name })
    : e.state === 'on_site' ? t('etaOnSite', { name })
      : e.state === 'confirmed' ? t('etaConfirmed', { name }) : t('etaDone');
  return (
    <section className="nl-after" aria-labelledby="eta-title">
      <h2 id="eta-title" className="nl-after-h2">{t('etaTitle')}</h2>
      <p className="nl-after-eta" role="status" aria-live="polite">{text}</p>
      {e.sharing && e.updatedAt ? <p className="nl-after-muted">{t('etaUpdated', { time: date(e.updatedAt, 'time') })}</p> : null}
      {e.minutesAway != null ? <p className="nl-after-muted">{t('etaMethod')}</p> : null}
    </section>
  );
}
