import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { colors, fonts, space } from '@northline/mobile-kit';

import type { Wallet as WalletData } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useShopFormat } from '../shop/common';
import { Body, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { QueryView, SignInPrompt, Skeleton } from '../ui/states';
import { KEYS, account, count } from './common';
import { SettingsRow } from './parts';
import { PlusCard } from './PlusCard';

/**
 * D5 Wallet & points (design 01 `wallet`; signed in): the balance and what it is worth (100 points = $1), the points
 * earned in each of the last 8 weeks (`GET /me/wallet`), the points activity and Invite a neighbour (S-152's own
 * screens), Northline Plus (S-152, PlusCard) and the payment methods (`GET /me/payment-methods` → Payment methods).
 * Provider-funded rewards near you are on Home.
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
      <View style={styles.rows}>
        <SettingsRow icon="clock-counter-clockwise" name={t('loyalty.activityLink')} onPress={() => router.push('/account/points')} testID="wallet-activity" />
        <SettingsRow icon="hand-coins" name={t('loyalty.inviteRow')} onPress={() => router.push('/account/invite')} testID="wallet-invite" />
      </View>
      <PlusCard />
    </>
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
  rows: { marginTop: space[4] },
});
