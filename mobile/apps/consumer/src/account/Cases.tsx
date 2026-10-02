import { useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, radius, space } from '@northline/mobile-kit';

import type { CaseDetail, CaseRow } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import type { MessageKey } from '../i18n';
import { backToTab, useShopFormat } from '../shop/common';
import { Ladder } from '../shop/parts';
import { Body, Button, Field, Notice, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, useAccountMutation } from './common';
import { Heading } from './parts';

type Format = ReturnType<typeof useShopFormat>;

/** "Seller reviewing · 14 h left", "Closed · $15.00 refunded"… (the consumer web's case statuses, S-60). */
export function caseStatus(c: CaseRow, f: Format, now = Date.now()): string {
  const { t } = f;
  if (c.kind === 'refund') {
    switch (c.state) {
      case 'seller_review': {
        if (!c.respondBy) return t('account.case.status.seller_review_due');
        const min = Math.max(0, Math.round((Date.parse(c.respondBy) - now) / 60_000));
        const left = min >= 48 * 60 ? t('account.case.left.d', { n: Math.round(min / 1440) }) : min >= 60 ? t('account.case.left.h', { n: Math.round(min / 60) }) : t('account.case.left.m', { n: min });
        return t('account.case.status.seller_review', { left });
      }
      case 'paid':
        return c.outcome === 'credit'
          ? t('account.case.status.paid_credit', { amount: f.money(c.settledCents ?? c.amountCents) })
          : t('account.case.status.paid', { amount: f.money((c.settledCents ?? c.amountCents) + c.taxCents) });
      default:
        break;
    }
  }
  const key = `account.case.status.${c.state}` as MessageKey;
  const s = t(key);
  return s === key ? c.state : s;
}

/**
 * Refunds & help (design 01 You › "Refunds & help"; S-60; signed in): the person's refund cases and disputes
 * (`GET /me/cases`, newest first), each opening its case. Cases are opened from an order or a job ("Something's
 * wrong", Journey B's report form).
 */
export function Help() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const list = useQuery({ queryKey: KEYS.cases, queryFn: () => account().cases(), enabled: signedIn, staleTime: 30_000 });
  if (!signedIn) {
    return (
      <Screen title={t('account.help.title')} testID="help">
        <SignInPrompt message={t('account.help.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen title={t('account.help.title')} testID="help">
      <QueryView
        query={list}
        skeleton={<LoadingList rows={3} height={56} />}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState message={t('account.help.empty')} action={t('account.orders.title')} onAction={() => backToTab('/orders')} />}
      >
        {(items) => (
          <View accessibilityRole="list">
            {items.map((c) => {
              const status = caseStatus(c, f);
              return (
                <Pressable
                  key={c.id}
                  accessibilityRole="button"
                  accessibilityLabel={`${t('account.case.title', { number: c.number })}, ${c.what}, ${status}`}
                  onPress={() => router.push(`/cases/${encodeURIComponent(c.number)}` as never)}
                  style={({ pressed }) => [styles.row, pressed && styles.pressed]}
                  testID={`case-${c.number}`}
                >
                  <View style={styles.text}>
                    <Text style={[type.body, type.strong]}>{t('account.case.title', { number: c.number })}</Text>
                    <Text style={type.small}>{[c.what, c.merchantName, f.date(c.openedAt)].filter(Boolean).join(' · ')}</Text>
                  </View>
                  <Tag label={status} tone={c.open ? 'accent2' : 'neutral'} />
                </Pressable>
              );
            })}
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

/**
 * One case (`/cases/<number>`, where S-102's case notifications land): its status, what it is about, the timeline
 * (Submitted → Seller reviews → Northline decides → Refund issued), and the messages with Northline — a new one is
 * added with `POST /me/cases/{id}/notes` while the case is open. The link names the case number; the api's id comes
 * from the list.
 */
export function Case() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const { number = '' } = useLocalSearchParams<{ number: string }>();
  const list = useQuery({ queryKey: KEYS.cases, queryFn: () => account().cases(), enabled: signedIn, staleTime: 30_000 });
  const row = list.data?.find((c) => c.number === number || c.id === number);
  const detail = useQuery({ queryKey: KEYS.case(row?.id ?? ''), queryFn: () => account().caseDetail(row!.id), enabled: signedIn && !!row, staleTime: 15_000 });
  const title = t('account.case.title', { number });

  if (!signedIn) {
    return (
      <Screen title={title} testID="case">
        <SignInPrompt message={t('account.help.signIn')} />
      </Screen>
    );
  }
  if (list.data && !row) {
    return (
      <Screen title={title} testID="case">
        <EmptyState message={t('account.case.notFound')} action={t('account.help.title')} onAction={() => router.replace('/account/help')} />
      </Screen>
    );
  }
  return (
    <Screen title={title} testID="case">
      {row ? (
        <QueryView query={detail} skeleton={<LoadingList rows={4} height={48} />}>
          {(d) => <CaseView detail={d} f={f} />}
        </QueryView>
      ) : (
        <QueryView query={list} skeleton={<LoadingList rows={4} height={48} />}>
          {() => null}
        </QueryView>
      )}
    </Screen>
  );
}

function CaseView({ detail, f }: { detail: CaseDetail; f: Format }) {
  const { t } = f;
  const { row, steps, thread } = detail;
  const [note, setNote] = useState('');
  const add = useAccountMutation((body: string) => account().addCaseNote(row.id, body), { set: KEYS.case(row.id), refresh: [KEYS.cases] });
  const card = detail.card ? t('account.card', { brand: detail.card.brand, last4: detail.card.last4 }) : t('account.case.yourCard');
  const ladder = steps
    .filter((s) => s.state !== 'skipped')
    .map((s) => ({
      key: s.key,
      state: (s.state === 'done' || s.state === 'denied' ? 'done' : s.state === 'current' ? 'current' : 'todo') as 'done' | 'current' | 'todo',
      label: t(`account.case.step.${s.key === 'seller' || s.key === 'northline' || s.key === 'refund' ? s.key : 'submitted'}`),
      detail:
        s.key === 'submitted' && s.at
          ? f.date(s.at)
          : s.key === 'seller' && s.at
            ? t('account.case.stepd.seller', { merchant: row.merchantName, date: f.date(s.at) })
            : s.key === 'refund'
              ? t('account.case.stepd.refund', { card })
              : undefined,
    }));
  const open = !!thread && thread.state !== 'resolved' && row.open;
  return (
    <>
      <Tag label={caseStatus(row, f)} tone={row.open ? 'accent2' : 'neutral'} />
      <Title>{row.what}</Title>
      <Body tone="muted">{[row.merchantName, f.money(row.amountCents + row.taxCents), t('account.case.opened', { date: f.date(row.openedAt) })].filter(Boolean).join(' · ')}</Body>
      <View style={styles.panel}>
        <Ladder steps={ladder} />
      </View>
      {thread ? (
        <>
          <Heading>{t('account.case.messages')}</Heading>
          {thread.notes.length === 0 ? <Body tone="small">{t('account.case.noMessages')}</Body> : null}
          {thread.notes.map((n, i) => (
            <View key={i} style={[styles.note, n.by === 'you' && styles.mine]} accessible accessibilityLabel={`${t(n.by === 'you' ? 'account.case.you' : 'account.case.northline')}: ${n.body}`}>
              <Text style={type.small}>
                {t(n.by === 'you' ? 'account.case.you' : 'account.case.northline')} · {f.date(n.at)} {f.time(n.at)}
              </Text>
              <Text style={type.body}>{n.body}</Text>
            </View>
          ))}
          {open ? (
            <>
              <Field label={t('account.case.addNote')} value={note} onChangeText={setNote} multiline hint={t('account.case.noteHint')} testID="case-note" />
              {add.error ? <Notice message={errorMessage(add.error, t)} /> : null}
              <Button
                label={t('account.case.send')}
                busy={add.isPending}
                disabled={!note.trim()}
                onPress={() => add.mutate(note.trim(), { onSuccess: () => setNote('') })}
                testID="case-send"
              />
            </>
          ) : null}
        </>
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: space[3], paddingVertical: space[3], minHeight: 56 },
  pressed: { backgroundColor: colors.accent100 },
  text: { flex: 1, minWidth: 0, gap: 2 },
  panel: { padding: space[4], borderRadius: radius.md, backgroundColor: colors.surface },
  note: { padding: space[3], borderRadius: radius.md, backgroundColor: colors.surface, gap: 2 },
  mine: { backgroundColor: colors.accent100 },
});
