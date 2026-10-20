import { useMutation, useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Linking, StyleSheet, Text, View } from 'react-native';

import { colors, fonts, radius, semantic, space } from '@northline/mobile-kit';

import type { Earnings, Totals } from '../../src/api/courier';
import { Banner, Body, Button, Card, Heading, Loading, Screen } from '../../src/components/ui';
import { useI18n, type MessageKey } from '../../src/i18n';
import { services } from '../../src/services';

export const EARNINGS_KEY = ['courier', 'earnings'] as const;

/** `Intl` money in the courier's language: 7,50 $ / $7.50. */
const money = (cents: number, locale: string) =>
  new Intl.NumberFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { style: 'currency', currency: 'CAD' }).format(cents / 100);

/**
 * S-151 earnings: today and this week (days and weeks in the market's zone, from the api), each paid run (the market's
 * pay model, plus the tips its deliveries brought), what is not paid out yet, the weekly payouts, and payout setup —
 * Stripe's own onboarding in the browser (the bank details go to Stripe, never to Northline or this phone).
 */
export default function EarningsScreen() {
  const { t, locale, day } = useI18n();
  const router = useRouter();
  const back = () => (router.canGoBack() ? router.back() : router.replace('/account'));
  const q = useQuery<Earnings>({ queryKey: EARNINGS_KEY, queryFn: () => services().courier.earnings() });
  const onboarding = useMutation({
    mutationFn: () => services().courier.payoutOnboarding(),
    onSuccess: (link) => void Linking.openURL(link.url),
  });
  const m = (c: number) => money(c, locale);

  if (q.isPending) {
    return (
      <Screen title={t('earnings.title')} back onBack={back} scroll={false}>
        <Loading label={t('common.loading')} />
      </Screen>
    );
  }
  if (!q.data) {
    return (
      <Screen title={t('earnings.title')} back onBack={back}>
        <Banner tone="error" action={<Button tone="ghost" label={t('common.retry')} onPress={() => void q.refetch()} />}>
          {t('earnings.error')}
        </Banner>
      </Screen>
    );
  }
  const e = q.data;
  const account = e.payoutAccount;
  const tile = (label: string, x: Totals, testID: string) => (
    <View style={styles.tile} testID={testID}>
      <Text style={styles.kicker}>{label}</Text>
      <Text style={styles.big}>{m(x.payCents + x.tipsCents)}</Text>
      <Body muted>{t('earnings.pay', { amount: m(x.payCents) })} · {t('earnings.tips', { amount: m(x.tipsCents) })}</Body>
      <Body muted>{t('earnings.counts', { runs: x.runs, stops: x.stops })}</Body>
    </View>
  );
  return (
    <Screen testID="earnings-screen" title={t('earnings.title')} back onBack={back}>
      <View style={styles.tiles}>
        {tile(t('earnings.today'), e.today, 'earnings-today')}
        {tile(t('earnings.week'), e.week, 'earnings-week')}
      </View>
      <Card>
        <Body strong>{t('earnings.balance', { amount: m(e.balanceCents) })}</Body>
        {account?.payoutsEnabled && account.bankLast4 ? <Body>{t('earnings.account', { last4: account.bankLast4 })}</Body> : null}
        {account && account.requirementsDue > 0 ? <Body>{t('earnings.accountDue', { n: account.requirementsDue })}</Body> : null}
        {!account?.payoutsEnabled ? (
          <>
            <Body muted>{t('earnings.setupHint')}</Body>
            <Button
              label={account ? t('earnings.continue') : t('earnings.setup')}
              busy={onboarding.isPending}
              onPress={() => onboarding.mutate()}
              testID="payout-setup"
            />
          </>
        ) : null}
        {onboarding.error ? <Banner tone="error">{onboarding.error instanceof Error ? onboarding.error.message : t('earnings.error')}</Banner> : null}
      </Card>
      <Heading level={2}>{t('earnings.runs')}</Heading>
      {e.runs.length ? (
        e.runs.map((r) => (
          <Card key={r.runId} testID={`earning-${r.runId}`}>
            <Body strong>{t('earnings.run', { label: r.label ?? r.runId.slice(-6), date: day(r.earnedAt, e.zone) })}</Body>
            <Body>{m(r.payCents + r.tipsCents)} · {t('earnings.pay', { amount: m(r.payCents) })} · {t('earnings.tips', { amount: m(r.tipsCents) })}</Body>
            <Body muted>{t('earnings.runDetail', { pickups: r.pickups, dropoffs: r.dropoffs, failed: r.failed, collects: r.collects, returns: r.returns })}</Body>
          </Card>
        ))
      ) : (
        <Body muted>{t('earnings.none')}</Body>
      )}
      {e.payouts.length ? (
        <>
          <Heading level={2}>{t('earnings.payouts')}</Heading>
          {e.payouts.map((p) => (
            <Body key={p.id}>
              {day(p.createdAt, e.zone)} · {t('earnings.payout', { amount: m(p.amountCents), state: t(`earnings.payout.${p.state}` as MessageKey) })}
            </Body>
          ))}
        </>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  tiles: { flexDirection: 'row', gap: space[3], flexWrap: 'wrap' },
  tile: { flex: 1, minWidth: 150, gap: space[1], padding: space[4], borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'] },
  kicker: { fontFamily: fonts.bold, fontSize: 12, letterSpacing: 0.5, textTransform: 'uppercase', color: colors.textMuted },
  big: { fontFamily: fonts.display, fontSize: 28, color: colors.text },
});
