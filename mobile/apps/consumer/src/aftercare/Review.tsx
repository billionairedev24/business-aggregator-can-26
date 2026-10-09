import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, colors, space } from '@northline/mobile-kit';

import { REVIEW_TAGS, aftercareApi, type MyReview, type ReviewKind, type ReviewTarget } from '../api/aftercare';
import { useAuth } from '../auth/AuthProvider';
import { useI18n, type MessageKey } from '../i18n';
import { services } from '../services';
import { Chip } from '../services/parts';
import { Body, Button, Field, Notice, Title, type } from '../ui/primitives';
import { errorMessage } from '../ui/states';

export const aftercare = () => aftercareApi(services().api);

export const reviewKey = (kind: ReviewKind, id: string) => ['aftercare', 'reviews', kind, id] as const;

/** What can be reviewed for a job, an order or a food order (`GET /me/reviews/{kind}/{id}`); 404 = nothing. */
export function useReviewContext(kind: ReviewKind, id: string) {
  const { status } = useAuth();
  return useQuery({
    queryKey: reviewKey(kind, id),
    queryFn: () => aftercare().reviewContext(kind, id),
    enabled: status === 'signedIn' && !!id,
    retry: (n, e) => n < 2 && !(e instanceof ApiError && !e.transient),
  });
}

/**
 * The reviews of a job or a delivery (mobile gaps part 2): one per business, once it's done, within 30 days. Each open
 * one is a form — stars, what stood out (the kind's tags), an optional note — posted with `POST /me/reviews`; a posted
 * one shows with its note (contact details and swearing masked by the api), the business's reply, and "Change my
 * review" while the 24-hour edit window is open and nobody replied. Nothing is shown when there is nothing to review.
 */
export function ReviewPanel({ kind, id, title, onPosted, extra }: { kind: ReviewKind; id: string; title?: (name: string) => string; onPosted?: (target: ReviewTarget) => void; extra?: (target: ReviewTarget) => ReactNode }) {
  const ctx = useReviewContext(kind, id);
  if (!ctx.data) return null;
  return (
    <View style={styles.stack} testID="review-panel">
      {ctx.data.targets.map((target) => (
        <TargetReview key={target.merchantId} kind={kind} id={id} target={target} title={title} onPosted={onPosted} extra={extra} readAt={ctx.dataUpdatedAt} />
      ))}
    </View>
  );
}

/** `readAt`: when the api answered — the edit window is judged against it (renders stay pure). */
function TargetReview({ kind, id, target, title, onPosted, extra, readAt }: { kind: ReviewKind; id: string; target: ReviewTarget; title?: (name: string) => string; onPosted?: (target: ReviewTarget) => void; extra?: (target: ReviewTarget) => ReactNode; readAt: number }) {
  const { t, time, day } = useI18n();
  const [editing, setEditing] = useState(false);
  const name = target.merchantName;
  const review = target.review;
  if (target.status === 'not_yet') return <Body tone="small">{t('review.notYet')}</Body>;
  if (review && !editing) {
    const canEdit = !review.reply && !review.hidden && !!review.editUntil && Date.parse(review.editUntil) > readAt;
    return (
      <View style={styles.stack} testID={`review-${target.merchantId}`}>
        <Text style={[type.body, type.strong]}>{t('review.yours', { name, stars: '★'.repeat(review.rating) })}</Text>
        {review.tags.length ? <Body tone="small">{review.tags.map((x) => t(`review.tag.${x}` as MessageKey)).join(' · ')}</Body> : null}
        {review.text ? <Body>{review.text}</Body> : null}
        {review.screened ? <Body tone="small">{t('review.screened')}</Body> : null}
        {review.hidden ? <Notice tone="info" message={t('review.hidden')} /> : null}
        {review.reply ? <Body tone="small">{t('review.reply', { name, reply: review.reply })}</Body> : null}
        {canEdit ? (
          <>
            <Body tone="small">{t('review.editUntil', { time: `${day(review.editUntil!)} ${time(review.editUntil!)}` })}</Body>
            <Button label={t('review.edit')} tone="secondary" onPress={() => setEditing(true)} testID="review-edit" />
          </>
        ) : null}
      </View>
    );
  }
  if (target.status === 'closed') return <Body tone="small">{t('review.closed')}</Body>;
  return (
    <ReviewForm
      kind={kind}
      id={id}
      target={target}
      heading={title ? title(name) : t('review.title', { name })}
      existing={editing ? review ?? undefined : undefined}
      onDone={() => {
        setEditing(false);
        onPosted?.(target);
      }}
      extra={extra?.(target)}
    />
  );
}

function ReviewForm({ kind, id, target, heading, existing, onDone, extra }: { kind: ReviewKind; id: string; target: ReviewTarget; heading: string; existing?: MyReview; onDone: () => void; extra?: ReactNode }) {
  const { t } = useI18n();
  const qc = useQueryClient();
  const [stars, setStars] = useState(existing?.rating ?? 0);
  const [tags, setTags] = useState<ReadonlySet<string>>(new Set(existing?.tags ?? []));
  const [note, setNote] = useState(existing?.text ?? '');
  const [starsError, setStarsError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => {
      const body = { rating: stars, tags: [...tags], ...(note.trim() ? { text: note.trim() } : {}) };
      return existing ? aftercare().editReview(existing.id, body) : aftercare().postReview({ kind, id, merchantId: target.merchantId, ...body });
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: reviewKey(kind, id) });
      onDone();
    },
  });
  const fieldError = (field: string) => (save.error instanceof ApiError ? save.error.fieldMessage(field) ?? null : null);
  const other = save.error && !fieldError('text') && !fieldError('rating') && !fieldError('tags') ? errorMessage(save.error, t) : null;
  const submit = () => {
    if (stars < 1) return setStarsError(t('review.needStars'));
    setStarsError(null);
    save.mutate();
  };
  return (
    <View style={styles.stack} testID={`review-form-${target.merchantId}`}>
      <Title>{heading}</Title>
      <View style={styles.stars} accessibilityRole="radiogroup" accessibilityLabel={t('review.stars')}>
        {[1, 2, 3, 4, 5].map((n) => (
          <Text
            key={n}
            accessibilityRole="radio"
            accessibilityLabel={t('review.star', { n })}
            accessibilityState={{ checked: stars === n }}
            onPress={() => setStars(n)}
            style={[styles.star, stars >= n && styles.starOn]}
            testID={`star-${n}`}
          >
            ★
          </Text>
        ))}
      </View>
      {starsError || fieldError('rating') ? <Text accessibilityRole="alert" style={type.error}>{starsError ?? fieldError('rating')}</Text> : null}
      <Text style={type.small}>{t('review.stoodOut')}</Text>
      <View style={styles.chips}>
        {REVIEW_TAGS[kind].map((k) => (
          <Chip
            key={k}
            label={t(`review.tag.${k}` as MessageKey)}
            selected={tags.has(k)}
            onPress={() =>
              setTags((s) => {
                const next = new Set(s);
                if (!next.delete(k)) next.add(k);
                return next;
              })
            }
          />
        ))}
      </View>
      {fieldError('tags') ? <Text accessibilityRole="alert" style={type.error}>{fieldError('tags')}</Text> : null}
      <Field label={t('review.noteLabel')} placeholder={t('review.note')} value={note} onChangeText={setNote} multiline maxLength={1000} error={fieldError('text')} testID="field-review" />
      {extra}
      {other ? <Notice message={other} testID="review-error" /> : null}
      <Button label={existing ? t('review.save') : t('review.submit')} large busy={save.isPending} onPress={submit} testID="submit-review" />
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: space[3] },
  stars: { flexDirection: 'row', gap: 6 },
  star: { width: 48, height: 48, fontSize: 30, lineHeight: 48, textAlign: 'center', color: colors.neutral300 },
  starOn: { color: colors.accent2 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
