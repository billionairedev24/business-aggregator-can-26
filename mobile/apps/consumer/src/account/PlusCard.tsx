import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, radius, randomId, semantic, space } from '@northline/mobile-kit';

import type { Plus, PlusPlan } from '../api/account';
import { useShopFormat } from '../shop/common';
import { Body, Button, Notice, Option, type } from '../ui/primitives';
import { Skeleton, errorMessage } from '../ui/states';
import { KEYS, account, useAccountMutation } from './common';
import { plusPayment } from './plusPayment';

const ACTIVE = new Set(['trialing', 'active', 'past_due', 'incomplete']);

/**
 * S-152 Northline Plus in the wallet (`GET|POST|PATCH|DELETE /me/plus`, `POST /me/plus/resume`): one membership per
 * household, paid by the person who started it. Join (monthly or annual, the free trial the first time) — it renews on
 * the default saved card (409 `card_required` without one) — switch plan, cancel at the end of the paid period, keep
 * it. Others in the household see it, the payer manages it. S-162: Northline bills Plus itself — the next payment, a
 * plan change from the next renewal, a refused renewal; a payment the bank wants confirmed (the first, or a renewal:
 * the push opens the wallet) is confirmed with Stripe's SDK, then `POST /me/plus/confirm`.
 */
export function PlusCard() {
  const f = useShopFormat();
  const { t } = f;
  const plus = useQuery({
    queryKey: KEYS.plus,
    queryFn: () => account().plus(),
    staleTime: 60_000,
  });
  const [plan, setPlan] = useState<PlusPlan>('monthly');
  const [confirming, setConfirming] = useState(false);
  const [bank, setBank] = useState<string | null>(null);
  // one key per attempt: a retry of the same press replays, a new press is a new request
  const key = useRef(randomId());
  const refresh = [KEYS.wallet, KEYS.household, KEYS.points];
  const confirmPay = useAccountMutation(() => account().confirmPlus(), { set: KEYS.plus, refresh });
  /** The bank's check with Stripe's SDK, then the api reads the PaymentIntent again. */
  const withBank = async (p: Plus) => {
    if (p.clientSecret && p.publishableKey) {
      setBank(null);
      const r = await plusPayment()
        .confirm(p.clientSecret, p.publishableKey)
        .catch(() => ({ ok: false as const, message: undefined }));
      if (!r.ok) {
        setBank(r.message ?? t('loyalty.bankFailed'));
        void plus.refetch();
        return;
      }
      confirmPay.mutate(undefined);
    }
  };
  const afterJoin = async (p: Plus) => {
    key.current = randomId();
    await withBank(p);
  };
  const join = useAccountMutation((p: PlusPlan) => account().joinPlus(p, key.current), { set: KEYS.plus, refresh });
  const change = useAccountMutation((p: PlusPlan) => account().changePlus(p, key.current), { set: KEYS.plus, refresh });
  const cancel = useAccountMutation(() => account().cancelPlus(), {
    set: KEYS.plus,
    refresh,
  });
  const resume = useAccountMutation(() => account().resumePlus(), {
    set: KEYS.plus,
    refresh,
  });
  const failed = join.error ?? change.error ?? cancel.error ?? resume.error ?? confirmPay.error;
  const needsCard = failed instanceof ApiError && failed.status === 409 && failed.code === 'card_required';

  if (plus.isPending) return <Skeleton height={120} />;
  if (!plus.data) return <Notice message={errorMessage(plus.error, t)} />;
  const p = plus.data;
  const member = ACTIVE.has(p.status);
  const price = (which: PlusPlan) =>
    which === 'monthly' ? t('loyalty.perMonth', { price: f.money(p.monthlyPriceCents) }) : t('loyalty.perYear', { price: f.money(p.annualPriceCents) });
  const end = p.currentPeriodEnd ? f.date(p.currentPeriodEnd) : '';
  const state = !member
    ? null
    : p.cancelAtPeriodEnd
      ? t('loyalty.s.canceling', { date: end })
      : p.status === 'trialing'
        ? t('loyalty.s.trialing', { date: end })
        : p.status === 'active'
          ? t('loyalty.s.active', { date: end })
          : t(`loyalty.s.${p.status as 'past_due' | 'incomplete'}`);
  const other: PlusPlan = p.plan === 'annual' ? 'monthly' : 'annual';

  return (
    <View style={styles.card} testID="plus">
      <Text accessibilityRole="header" style={[type.body, type.strong]}>
        {t('loyalty.plusTitle')}
        {member && p.plan ? ` · ${t(`loyalty.plan.${p.plan}`)}` : ''}
      </Text>
      {member ? (
        <>
          <Body tone="small">{state}</Body>
          {p.payer && p.nextPlan && p.nextChargeAt ? (
            <Body tone="small">{t('loyalty.switchesTo', { plan: t(`loyalty.plan.${p.nextPlan}`), date: f.date(p.nextChargeAt) })}</Body>
          ) : p.payer && p.nextChargeAt && !p.payment && p.status !== 'incomplete' ? (
            <Body tone="small">{t('loyalty.nextCharge', { amount: f.money(p.nextChargeCents ?? 0), date: f.date(p.nextChargeAt) })}</Body>
          ) : null}
          {p.payer && p.payment ? (
            <View style={styles.confirm} testID="plus-payment">
              <Notice
                tone="info"
                message={
                  p.payment.reason === 'authentication_required'
                    ? t('loyalty.pay.authentication_required', { amount: f.money(p.payment.totalCents) })
                    : p.payment.retryAt
                      ? t('loyalty.pay.declined', { amount: f.money(p.payment.totalCents), date: f.date(p.payment.retryAt) })
                      : t('loyalty.pay.lastTry', { amount: f.money(p.payment.totalCents) })
                }
              />
              {p.clientSecret && p.publishableKey ? (
                <Button label={t('loyalty.confirmPayment')} busy={confirmPay.isPending} onPress={() => void withBank(p)} testID="plus-confirm" />
              ) : null}
            </View>
          ) : null}
          {!p.payer ? <Body tone="muted">{t('loyalty.notPayer')}</Body> : null}
          {p.payer && p.cancelAtPeriodEnd ? (
            <Button label={t('loyalty.resume')} busy={resume.isPending} onPress={() => resume.mutate(undefined)} testID="plus-resume" />
          ) : null}
          {p.payer && !p.cancelAtPeriodEnd ? (
            <>
              {p.nextPlan && p.plan ? (
                <Button
                  label={t('loyalty.keepPlan', { plan: t(`loyalty.plan.${p.plan}`) })}
                  tone="secondary"
                  busy={change.isPending}
                  onPress={() => change.mutate(p.plan!, { onSuccess: () => (key.current = randomId()) })}
                  testID="plus-keep-plan"
                />
              ) : (
                <Button
                  label={t('loyalty.switchTo', {
                    plan: t(`loyalty.plan.${other}`),
                  })}
                  tone="secondary"
                  busy={change.isPending}
                  onPress={() =>
                    change.mutate(other, {
                      onSuccess: () => (key.current = randomId()),
                    })
                  }
                  testID="plus-switch"
                />
              )}
              {confirming ? (
                <View style={styles.confirm} testID="plus-cancel-confirm">
                  <Body tone="small">{t('loyalty.cancelBody', { date: end })}</Body>
                  <Button
                    label={t('loyalty.cancelConfirm')}
                    tone="danger"
                    busy={cancel.isPending}
                    onPress={() =>
                      cancel.mutate(undefined, {
                        onSuccess: () => setConfirming(false),
                      })
                    }
                    testID="plus-cancel-yes"
                  />
                  <Button label={t('loyalty.resume')} tone="ghost" onPress={() => setConfirming(false)} />
                </View>
              ) : (
                <Button label={t('loyalty.cancel')} tone="ghost" onPress={() => setConfirming(true)} testID="plus-cancel" />
              )}
            </>
          ) : null}
        </>
      ) : (
        <>
          <Body tone="small">{t('loyalty.plusLede')}</Body>
          <View style={styles.perks}>
            <Body tone="small">{t('loyalty.perkFee')}</Body>
            <Body tone="small">
              •{' '}
              {t('loyalty.perkPoints', {
                multiplier: p.benefits.pointsMultiplierPct > 100 ? p.benefits.pointsMultiplierPct / 100 : 2,
              })}
            </Body>
            <Body tone="small">{t('loyalty.perkPrices')}</Body>
            <Body tone="small">{t('loyalty.perkHousehold')}</Body>
          </View>
          <View accessibilityRole="radiogroup" style={styles.perks}>
            {(['monthly', 'annual'] as const).map((which) => (
              <Option
                key={which}
                selected={plan === which}
                onPress={() => setPlan(which)}
                title={t(`loyalty.plan.${which}`)}
                description={price(which)}
                testID={`plan-${which}`}
              />
            ))}
          </View>
          {p.trialAvailable ? (
            <Body tone="small">
              {t('loyalty.then', { price: price(plan) })} · {t('loyalty.trialNote')}
            </Body>
          ) : null}
          <Button
            label={p.trialAvailable ? t('loyalty.startTrial', { days: p.trialDays }) : t('loyalty.join')}
            busy={join.isPending}
            onPress={() => {
              setBank(null);
              join.mutate(plan, { onSuccess: (r) => void afterJoin(r) });
            }}
            testID="plus-start"
          />
        </>
      )}
      {needsCard ? (
        <View style={styles.confirm} testID="plus-needs-card">
          <Notice tone="info" message={t('loyalty.addCardBody')} />
          <Button label={t('account.you.payments')} tone="secondary" onPress={() => router.push('/account/payments')} />
        </View>
      ) : failed ? (
        <Notice message={errorMessage(failed, t)} />
      ) : null}
      {bank ? <Notice message={bank} testID="plus-bank" /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { marginTop: space[4], padding: space[4], borderRadius: radius.panel, backgroundColor: semantic.surface, gap: space[2] },
  perks: { gap: space[1] },
  confirm: { gap: space[2] },
});
