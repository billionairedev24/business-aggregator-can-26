import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { ApiError, MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import type { ProblemContext, ProblemItem, Reported } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import type { MessageKey } from '../i18n';
import { Body, Button, Field, Notice } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { shop, useShopFormat } from './common';
import { Chip, Kicker, Ladder, Panel } from './parts';

const NOTE_MAX = 1000;

/**
 * B10 Report a problem / refund request (design 01 `refund`; `/problem/{kind}/{id}`): the case rules in one line, the
 * items to pick (each with its amount and tax), the reason, an optional note, "Request {amount} refund" →
 * `POST /me/problems` (S-60) → the case: "Case … · in review", the seller's payout paused, the four steps. Photos
 * (`POST /me/case-uploads`) need a photo picker the app doesn't have yet (a native module and a permission) — the case
 * takes them later from the website; the AI triage (`POST /me/help/triage`) is optional on the web and not used here.
 */
export function Problem() {
  const { kind, id } = useLocalSearchParams<{ kind: string; id: string }>();
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const qc = useQueryClient();
  const context = useQuery({ queryKey: ['shop', 'problem', kind, id], queryFn: () => shop().problem(kind, id), enabled: status === 'signedIn' });
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [reason, setReason] = useState<string>();
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<{ items?: string; reason?: string; note?: string }>({});
  const report = useMutation({
    mutationFn: (r: Parameters<ReturnType<typeof shop>['report']>[0]) => shop().report(r),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['shop', 'problem', kind, id] });
      void qc.invalidateQueries({ queryKey: ['account'] });
    },
  });

  if (status !== 'signedIn') {
    return (
      <Screen title={t('title.refund')} testID="refund">
        <SignInPrompt message={t('shop.order.signIn')} />
      </Screen>
    );
  }
  if (report.data) return <Done reported={report.data} />;

  const ctx = context.data;
  const open = (i: ProblemItem) => i.status === 'open';
  const chosen = ctx?.items.filter((i) => open(i) && picked[i.ref]) ?? [];
  const amount = chosen.reduce((n, i) => n + i.amountCents + i.taxCents, 0);

  const submit = () => {
    if (!ctx) return;
    const e: typeof errors = {};
    if (chosen.length === 0) e.items = t('shop.problem.v.items');
    if (!reason) e.reason = t('shop.problem.v.reason');
    if (note.trim().length > NOTE_MAX) e.note = t('shop.problem.v.note');
    setErrors(e);
    if (Object.keys(e).length) return;
    report.mutate({ kind: ctx.kind, id: ctx.id, items: chosen.map((i) => i.ref), reason: reason!, ...(note.trim() ? { note: note.trim() } : {}), attachmentIds: [] });
  };
  const serverError = report.error
    ? report.error instanceof ApiError && report.error.errors.length
      ? report.error.errors.map((x) => x.message).join(' ')
      : errorMessage(report.error, t)
    : null;

  return (
    <Screen
      title={t('title.refund')}
      testID="refund"
      footer={
        ctx && ctx.status === 'open' ? (
          <>
            {serverError ? <Notice message={serverError} /> : null}
            <Button label={t('shop.problem.request', { amount: f.money(amount) })} large busy={report.isPending} onPress={submit} testID="refund-submit" />
          </>
        ) : undefined
      }
    >
      <QueryView query={context} skeleton={<LoadingList rows={5} height={48} />}>
        {(c: ProblemContext | null) =>
          c ? (
            <View style={styles.gap}>
              <Body tone="muted">{t('shop.problem.intro')}</Body>
              {c.status !== 'open' ? (
                <Notice tone="info" message={t(`shop.problem.st.${c.status}` as MessageKey)} testID="refund-status" />
              ) : (
                <>
                  <Kicker>{t('shop.problem.items')}</Kicker>
                  <View style={styles.items}>
                    {c.items.map((i) => {
                      const on = !!picked[i.ref] && open(i);
                      return (
                        <Pressable
                          key={i.ref}
                          accessibilityRole="checkbox"
                          accessibilityLabel={`${i.title}, ${f.money(i.amountCents + i.taxCents)}${open(i) ? '' : `, ${t(`shop.problem.item.${i.status}` as MessageKey)}`}`}
                          accessibilityState={{ checked: on, disabled: !open(i) }}
                          disabled={!open(i)}
                          onPress={() => setPicked((p) => ({ ...p, [i.ref]: !p[i.ref] }))}
                          style={[styles.item, on && styles.itemOn, !open(i) && styles.itemOff]}
                          testID={`refund-item-${i.ref}`}
                        >
                          <View style={[styles.box, on && styles.boxOn]} />
                          <Text style={[styles.body, styles.flex]}>
                            {i.qty > 1 ? `${i.qty} × ` : ''}
                            {i.title}
                            {open(i) ? '' : ` · ${t(`shop.problem.item.${i.status}` as MessageKey)}`}
                          </Text>
                          <Text style={styles.small}>{f.money(i.amountCents + i.taxCents)}</Text>
                        </Pressable>
                      );
                    })}
                  </View>
                  {errors.items ? <Text accessibilityRole="alert" style={styles.error}>{errors.items}</Text> : null}
                  <Kicker>{t('shop.problem.reason')}</Kicker>
                  <View style={styles.chips} accessibilityRole="radiogroup">
                    {c.reasons.map((r) => (
                      <Chip key={r} role="radio" label={t(`shop.problem.r.${r}` as MessageKey)} on={reason === r} onPress={() => setReason(r)} testID={`reason-${r}`} />
                    ))}
                  </View>
                  {errors.reason ? <Text accessibilityRole="alert" style={styles.error}>{errors.reason}</Text> : null}
                  <Field label={t('shop.problem.note')} value={note} onChangeText={setNote} multiline error={errors.note} testID="refund-note" />
                  <Body tone="small">{t('shop.problem.photos')}</Body>
                </>
              )}
            </View>
          ) : null
        }
      </QueryView>
    </Screen>
  );
}

function Done({ reported }: { reported: Reported }) {
  const f = useShopFormat();
  const { t } = f;
  const first = reported.refunds[0];
  const card = reported.card ? t('shop.pay.card', { brand: reported.card.brand, last4: reported.card.last4 }) : t('shop.problem.yourCard');
  return (
    <Screen title={t('title.refund')} testID="refund-done" footer={<Button label={t('shop.problem.backToOrders')} large onPress={() => router.replace('/orders')} />}>
      <Panel>
        <Kicker>{t('shop.problem.caseInReview', { number: reported.caseCode })}</Kicker>
        <Text style={styles.h3}>{t('shop.problem.received', { amount: f.money(reported.totalCents) })}</Text>
        <Body tone="muted">{t('shop.problem.paused')}</Body>
      </Panel>
      <Ladder
        steps={[
          { key: 'submitted', state: 'done', label: t('shop.problem.step.submitted'), detail: t('shop.problem.stepd.submitted', { time: f.time(reported.submittedAt) }) },
          {
            key: 'seller',
            state: 'current',
            label: t('shop.problem.step.seller'),
            detail: first?.respondBy ? t('shop.problem.stepd.seller', { merchant: first.merchantName, date: `${f.date(first.respondBy)} ${f.time(first.respondBy)}` }) : undefined,
          },
          { key: 'northline', state: 'todo', label: t('shop.problem.step.northline'), detail: t('shop.problem.stepd.northline') },
          { key: 'refund', state: 'todo', label: t('shop.problem.step.refund'), detail: t('shop.problem.stepd.refund', { card }) },
        ]}
      />
      <Body tone="small">{t('shop.problem.notify')}</Body>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: space[3] },
  flex: { flex: 1 },
  items: { gap: 6 },
  item: { flexDirection: 'row', alignItems: 'center', gap: 10, minHeight: MIN_TARGET, paddingHorizontal: 14, paddingVertical: space[2], borderRadius: radius.md, borderWidth: 1, borderColor: colors.divider },
  itemOn: { borderColor: colors.accent, backgroundColor: colors.accent100 },
  itemOff: { opacity: 0.55 },
  box: { width: 18, height: 18, borderRadius: radius.sm, borderWidth: 1.5, borderColor: colors.divider },
  boxOn: { borderColor: colors.accent, backgroundColor: colors.accent },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  body: { fontFamily: fonts.body, fontSize: 15, color: colors.text },
  small: { fontFamily: fonts.body, fontSize: 13, color: colors.text },
  error: { fontFamily: fonts.body, fontSize: 13, color: colors.accent2_700 },
  h3: { fontFamily: fonts.bodyStrong, fontSize: 18, color: colors.text },
});
