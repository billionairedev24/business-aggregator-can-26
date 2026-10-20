import { useQuery } from '@tanstack/react-query';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { colors, fonts, semantic, space } from '@northline/mobile-kit';

import type { MessageKey } from '../i18n';
import type { MyPoints, PointsActivity } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useShopFormat } from '../shop/common';
import { Body, Notice, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt } from '../ui/states';
import { KEYS, account, count } from './common';
import { Heading, LineRow, SettingsGroup } from './parts';

const KNOWN = new Set(['earn', 'reward', 'referral', 'redemption', 'redemption_return', 'refund_return', 'earn_reversal', 'reward_reversal', 'expiry']);

/**
 * S-152 Points activity (`/account/points`; the points push's deep link): the balance, when the next points expire,
 * how points work where the person shops (the region's rule, `GET /me/points`) and the ledger — earned on completed
 * orders and bookings, business bonuses, referrals, spent at checkout, refunds, expiry.
 */
export function Points() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const points = useQuery({
    queryKey: KEYS.points,
    queryFn: () => account().points(50),
    enabled: signedIn,
    staleTime: 30_000,
  });
  if (!signedIn) {
    return (
      <Screen title={t('title.points')} testID="points">
        <SignInPrompt message={t('loyalty.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen
      title={t('title.points')}
      testID="points"
      refreshControl={<RefreshControl refreshing={points.isRefetching} onRefresh={() => void points.refetch()} />}
    >
      <QueryView query={points} skeleton={<LoadingList rows={5} />}>
        {(p) => <PointsBody p={p} f={f} />}
      </QueryView>
    </Screen>
  );
}

function PointsBody({ p, f }: { p: MyPoints; f: ReturnType<typeof useShopFormat> }) {
  const { t, locale } = f;
  return (
    <>
      <View style={styles.balance}>
        <Text style={styles.big} testID="points-total">
          {count(p.balance, locale)}
        </Text>
        <Text style={type.small}>{t('account.wallet.value', { value: f.money(p.valueCents) })}</Text>
      </View>
      {p.nextExpiryAt && p.nextExpiryPoints > 0 ? (
        <Notice
          tone="info"
          message={t('loyalty.nextExpiry', {
            points: p.nextExpiryPoints,
            date: f.date(p.nextExpiryAt),
          })}
          testID="points-expiry"
        />
      ) : null}
      <SettingsGroup title={t('loyalty.howTitle')} testID="points-how">
        <View style={styles.how}>
          <Body tone="small">{t('loyalty.howEarn', { points: p.pointsPerDollarSpent })}</Body>
          <Body tone="small">
            {t('loyalty.howPlus', {
              multiplier: p.plusMultiplierPct > 100 ? p.plusMultiplierPct / 100 : 2,
            })}
          </Body>
          <Body tone="small">{t('loyalty.howRedeem', { points: p.redeemPointsPerDollar })}</Body>
          <Body tone="small">{p.expiryMonths ? t('loyalty.howExpiry', { months: p.expiryMonths }) : t('loyalty.howNever')}</Body>
        </View>
      </SettingsGroup>
      <Heading>{t('loyalty.activityTitle')}</Heading>
      {p.activity.length === 0 ? (
        <Body tone="muted">{t('loyalty.activityEmpty')}</Body>
      ) : (
        <View testID="points-activity">
          {p.activity.map((a) => (
            <ActivityRow key={a.id} a={a} f={f} />
          ))}
        </View>
      )}
    </>
  );
}

function ActivityRow({ a, f }: { a: PointsActivity; f: ReturnType<typeof useShopFormat> }) {
  const { t, locale } = f;
  const kind = (KNOWN.has(a.type) ? `loyalty.a.${a.type}` : a.type === 'order' || a.type === 'booking' ? 'loyalty.a.earn' : 'loyalty.a.other') as MessageKey;
  const sign = a.points > 0 ? '+' : '−';
  const meta = [a.note, f.date(a.at), a.expiresAt && a.points > 0 ? t('loyalty.expires', { date: f.date(a.expiresAt) }) : null].filter(Boolean).join(' · ');
  return (
    <LineRow
      right={
        <Text style={[styles.pts, a.points < 0 && styles.out]}>
          {sign}
          {count(Math.abs(a.points), locale)}
        </Text>
      }
    >
      <Text style={[type.body, type.strong]}>{t(kind)}</Text>
      <Text style={type.small}>{meta}</Text>
    </LineRow>
  );
}

const styles = StyleSheet.create({
  balance: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    alignItems: 'baseline',
    gap: space[2],
  },
  big: {
    fontFamily: fonts.bodyStrong,
    fontSize: 44,
    lineHeight: 50,
    letterSpacing: -0.8,
    color: colors.text,
  },
  how: { gap: space[2], padding: space[4] },
  pts: {
    fontFamily: fonts.bold,
    fontSize: 16,
    color: semantic.success,
    fontVariant: ['tabular-nums'],
  },
  out: { color: colors.textMuted },
});
