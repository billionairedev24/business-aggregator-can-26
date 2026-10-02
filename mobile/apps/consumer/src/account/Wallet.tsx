import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { colors, fonts, radius, space } from '@northline/mobile-kit';

import type { Wallet as WalletData } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useShopFormat } from '../shop/common';
import { Body, Button, Notice, Option, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { QueryView, SignInPrompt, Skeleton, errorMessage } from '../ui/states';
import { KEYS, account, count, useAccountMutation } from './common';
import { SettingsRow } from './parts';

/**
 * D5 Wallet & points (design 01 `wallet`; signed in): the balance and what it is worth (100 points = $1), the points
 * earned in each of the last 8 weeks (`GET /me/wallet`), Northline Plus ("Try free" starts the household's 30-day
 * trial, `POST /me/plus`; "Manage" shows the renewal and cancels, `DELETE /me/plus`) and the payment methods
 * (`GET /me/payment-methods` → Payment methods). The design's "Provider-funded rewards near you" and points "Activity"
 * have no consumer api yet (MOBILE_PLAN § API gaps) and are not drawn.
 */
export function Wallet() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const wallet = useQuery({ queryKey: KEYS.wallet, queryFn: () => account().wallet(), enabled: signedIn, staleTime: 60_000 });
  const cards = useQuery({ queryKey: KEYS.cards, queryFn: () => account().cards(), enabled: signedIn, staleTime: 60_000 });

  if (!signedIn) {
    return (
      <Screen title={t('title.wallet')} testID="wallet">
        <SignInPrompt message={t('account.wallet.signIn')} />
      </Screen>
    );
  }

  const card = cards.data?.items.find((c) => c.isDefault) ?? cards.data?.items[0];
  return (
    <Screen
      title={t('title.wallet')}
      testID="wallet"
      refreshControl={<RefreshControl refreshing={wallet.isRefetching} onRefresh={() => void Promise.all([wallet.refetch(), cards.refetch()])} />}
      footer={
        <SettingsRow
          name={t('account.you.payments')}
          value={cards.data ? (card ? t('account.card', { brand: card.brand, last4: card.last4 }) : t('account.wallet.noCard')) : undefined}
          onPress={() => router.push('/account/payments')}
          testID="wallet-payments"
        />
      }
    >
      <QueryView
        query={wallet}
        skeleton={
          <View style={styles.skeleton} testID="loading">
            <Skeleton height={14} width="40%" />
            <Skeleton height={48} width="60%" />
            <Skeleton height={60} />
            <Skeleton height={64} />
          </View>
        }
      >
        {(w) => <Points wallet={w} f={f} />}
      </QueryView>
    </Screen>
  );
}

function Points({ wallet, f }: { wallet: WalletData; f: ReturnType<typeof useShopFormat> }) {
  const { t, locale } = f;
  const { points } = wallet;
  const max = Math.max(1, ...points.weekly);
  const weeks = points.weekly.length;
  const chart = points.weekly.map((p, i) => t('account.wallet.week', { points: count(p, locale), n: weeks - 1 - i })).join(', ');
  return (
    <>
      <Text style={type.kicker}>{t('account.wallet.kicker')}</Text>
      <View style={styles.balance}>
        <Text style={styles.big} testID="points-balance">
          {count(points.balance, locale)}
        </Text>
        <Text style={[type.small, styles.value]}>{t('account.wallet.value', { value: f.money(points.valueCents) })}</Text>
      </View>
      <View style={styles.bars} accessible accessibilityRole="image" accessibilityLabel={`${t('account.wallet.chart')}: ${chart}`} testID="points-chart">
        {points.weekly.map((p, i) => (
          <View key={i} style={styles.bar}>
            <View style={[styles.fill, { height: `${Math.round((p / max) * 100)}%` }]} />
          </View>
        ))}
      </View>
      <Text style={type.small}>{t('account.wallet.chart')}</Text>
      {points.balance === 0 && points.weekly.every((p) => p === 0) ? <Body tone="muted">{t('account.wallet.noPoints')}</Body> : null}
      <Plus wallet={wallet} f={f} />
    </>
  );
}

/** Northline Plus: "Try free" (monthly or annual) or "Manage" (renewal, cancel). */
function Plus({ wallet, f }: { wallet: WalletData; f: ReturnType<typeof useShopFormat> }) {
  const { t } = f;
  const plus = wallet.plus;
  const [open, setOpen] = useState(false);
  const [plan, setPlan] = useState<'monthly' | 'annual'>('monthly');
  const refresh = [KEYS.wallet, KEYS.household];
  const start = useAccountMutation((p: 'monthly' | 'annual') => account().startPlus(p), { set: KEYS.household, refresh });
  const cancel = useAccountMutation(() => account().cancelPlus(), { set: KEYS.household, refresh });
  const failed = start.error ?? cancel.error;

  return (
    <View style={styles.plus} testID="plus">
      <View style={styles.plusHead}>
        <View style={styles.flex}>
          <Text style={[type.body, type.strong]}>{t(plus ? 'account.wallet.plusOn' : 'account.wallet.plusOff')}</Text>
          <Text style={type.small}>{t(plus ? 'account.wallet.plusSubOn' : 'account.wallet.plusSubOff')}</Text>
        </View>
        <Button label={t(plus ? 'account.wallet.manage' : 'account.wallet.tryFree')} tone="ghost" onPress={() => setOpen((o) => !o)} testID="plus-cta" />
      </View>
      {open && plus ? (
        <View style={styles.plusBody}>
          <Body tone="small">
            {plus.renewsAt
              ? t('account.wallet.renews', { plan: t(`account.wallet.plan.${plus.plan}`), date: f.date(plus.renewsAt), members: plus.members })
              : t('account.wallet.planOnly', { plan: t(`account.wallet.plan.${plus.plan}`), members: plus.members })}
          </Body>
          <Button label={t('account.wallet.cancelPlus')} tone="danger" busy={cancel.isPending} onPress={() => cancel.mutate(undefined, { onSuccess: () => setOpen(false) })} testID="plus-cancel" />
        </View>
      ) : null}
      {open && !plus ? (
        <View style={styles.plusBody} accessibilityRole="radiogroup">
          {(['monthly', 'annual'] as const).map((p) => (
            <Option key={p} selected={plan === p} onPress={() => setPlan(p)} title={t(`account.wallet.plan.${p}`)} description={t(`account.wallet.price.${p}`)} testID={`plan-${p}`} />
          ))}
          <Body tone="small">{t('account.wallet.trialNote')}</Body>
          <Button label={t('account.wallet.startTrial')} busy={start.isPending} onPress={() => start.mutate(plan, { onSuccess: () => setOpen(false) })} testID="plus-start" />
        </View>
      ) : null}
      {failed ? <Notice message={errorMessage(failed, t)} /> : null}
    </View>
  );
}


const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  skeleton: { gap: space[3] },
  balance: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'baseline', gap: space[2] },
  big: { fontFamily: fonts.bodyStrong, fontSize: 44, lineHeight: 50, letterSpacing: -0.8, color: colors.text },
  value: { fontSize: 14 },
  bars: { flexDirection: 'row', gap: 4, height: 60, marginTop: space[2] },
  bar: { flex: 1, height: 60, justifyContent: 'flex-end', backgroundColor: colors.neutral200, borderRadius: 2, overflow: 'hidden' },
  fill: { backgroundColor: colors.accent },
  plus: { marginTop: space[4], padding: space[4], borderRadius: radius.md, backgroundColor: colors.surface, gap: space[2] },
  plusHead: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  plusBody: { gap: space[2] },
});
