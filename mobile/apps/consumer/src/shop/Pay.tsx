import { useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { Redirect, router } from 'expo-router';
import { useRef, useState } from 'react';
import { StyleSheet, Text } from 'react-native';

import { ApiError, NetworkError, randomId, space, textStyles } from '@northline/mobile-kit';

import type { CheckoutBody, Started } from '../api/shop';
import type { useCheckoutConsent } from '../account/CheckoutConsent';
import { config } from '../config';
import type { MessageKey } from '../i18n';
import { Body, Button, Field, Notice, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { errorMessage } from '../ui/states';
import { CART_KEY, problemCode, shop, useShopFormat } from './common';
import { cardPaymentsFor, pendingIntents, type PayMethod } from './payments';
import { Kicker, Panel } from './parts';
import { StepUpFailed, stepUpWithCode, type StepUpFailure } from './stepUp';

export type Phase = 'form' | 'stepUp' | 'enrol' | 'bank';
/** Why the authenticator code wasn't taken, in words (the reasons aren't the copy keys: `wrong_code` → `wrong`). */
const STEP_UP_MESSAGE: Record<StepUpFailure, MessageKey> = { wrong_code: 'shop.stepUp.wrong', locked: 'shop.stepUp.locked', elsewhere: 'shop.stepUp.elsewhere' };
const KNOWN = ['out_of_stock', 'window_closed', 'cart_empty', 'checkout_expired', 'payment_not_authorized'];

/**
 * Placing the order (B6 Payment + 3-D Secure), the one-page checkout's "Place your order" (S-178; before, a second
 * screen at /pay):
 *   1. `POST /me/checkouts` (Idempotency-Key kept for every retry of this checkout; `X-Step-Up` when the api asks — the
 *      authenticator code is confirmed with northline-auth first, or the person is told how to add a second factor);
 *   2. the PaymentIntents: Stripe's SDK (PaymentSheet for a new card, the saved card's PaymentMethod otherwise; 3-D
 *      Secure is Stripe's own native screen), or with the api's stand-in the design's bank step (`pay3ds`);
 *   3. `POST /me/checkouts/{id}/place` → Order confirmed.
 */
export function usePlaceOrder(body: CheckoutBody | null, card: string, consent: ReturnType<typeof useCheckoutConsent>) {
  const { t, locale } = useShopFormat();
  const qc = useQueryClient();
  const [phase, setPhase] = useState<Phase>('form');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [bankCode, setBankCode] = useState('482 913');
  const [started, setStarted] = useState<Started | null>(null);
  // one Idempotency-Key per checkout, the same for every retry until the payment goes through (MOBILE_PLAN § Data)
  const payKey = useRef(randomId());
  const placeKey = useRef(randomId());

  const fail = (e: unknown) => {
    const c = problemCode(e);
    setError(c && KNOWN.includes(c) ? t(`shop.pay.err.${c}` as MessageKey) : e instanceof ApiError || e instanceof NetworkError ? errorText(e) : t('shop.pay.err.generic'));
    if (c === 'out_of_stock' || c === 'cart_empty' || c === 'window_closed' || c === 'checkout_expired') {
      void qc.invalidateQueries({ queryKey: CART_KEY });
      void qc.invalidateQueries({ queryKey: ['shop', 'checkout'] });
    }
    // a refusal ends this attempt; no answer (offline, 5xx) keeps the key so trying again can't pay twice
    if (e instanceof ApiError && !e.transient) {
      payKey.current = randomId();
      setStarted(null);
    }
  };
  const errorText = (e: unknown) => errorMessage(e, t);

  const place = async (s: Started) => {
    setBusy(true);
    try {
      const placed = await shop().place(s.checkoutId, placeKey.current);
      await consent.record(); // S-157: the ticked offers box, once the order went through
      await qc.invalidateQueries({ queryKey: CART_KEY });
      void qc.invalidateQueries({ queryKey: ['shop', 'checkout'] });
      router.replace({ pathname: '/orders/[id]/confirmed', params: { id: placed?.orderId ?? s.orderId } });
    } catch (e) {
      fail(e);
      setPhase('form');
    } finally {
      setBusy(false);
    }
  };

  const pay = async (proof?: string) => {
    if (!body) return;
    setError(null);
    setBusy(true);
    let s: Started | null = started;
    try {
      if (!s) {
        s = await shop().start(body, payKey.current, locale, proof);
        if (!s) throw new ApiError(502, undefined, undefined);
        setStarted(s);
      }
    } catch (e) {
      setBusy(false);
      const c = problemCode(e);
      if (c === 'step_up_required') return setPhase('stepUp');
      if (c === 'second_factor_required') return setPhase('enrol');
      return fail(e);
    }
    const payments = cardPaymentsFor(s.payment.provider);
    if (!payments || pendingIntents(s).length === 0) {
      setBusy(false);
      // the stand-in authorized everything: the design's bank step, then the order is placed
      if (s.payment.provider !== 'stripe') return setPhase('bank');
      return place(s);
    }
    const how: PayMethod = card === 'new' ? { kind: 'new' } : { kind: 'saved', paymentMethodId: card };
    const result = await payments.pay(s, how).catch(() => ({ status: 'failed' as const, message: undefined }));
    setBusy(false);
    if (result.status === 'paid') return place(s);
    setError(result.status === 'cancelled' ? t('shop.pay.err.cancelled') : result.message ? t('shop.pay.err.card', { message: result.message }) : t('shop.pay.err.generic'));
  };

  const confirmCode = async () => {
    setCodeError(null);
    if (!/^\d{6}$/.test(code.trim())) return setCodeError(t('shop.stepUp.wrong'));
    setBusy(true);
    try {
      const proof = await stepUpWithCode(code);
      setPhase('form');
      setCode('');
      await pay(proof);
    } catch (e) {
      setBusy(false);
      setCodeError(e instanceof StepUpFailed ? t(STEP_UP_MESSAGE[e.reason]) : errorText(e));
    }
  };

  return { phase, setPhase, busy, error, code, setCode, codeError, bankCode, setBankCode, started, pay, place, confirmCode };
}

export type PlaceOrder = ReturnType<typeof usePlaceOrder>;

/** The steps between "Place your order" and the order: the authenticator code, adding a second factor, the bank. */
export function PayPhase({ order }: { order: PlaceOrder }) {
  const f = useShopFormat();
  const { t } = f;
  if (order.phase === 'stepUp') {
    return (
      <Screen
        title={t('title.pay')}
        testID="pay-step-up"
        footer={
          <>
            <Button label={t('shop.stepUp.confirm')} large busy={order.busy} onPress={() => void order.confirmCode()} testID="step-up-confirm" />
            <Button label={t('shop.stepUp.cancel')} tone="ghost" onPress={() => order.setPhase('form')} />
          </>
        }
      >
        <Title>{t('shop.stepUp.title')}</Title>
        <Body tone="muted">{t('shop.stepUp.body')}</Body>
        <Field
          label={t('shop.stepUp.code')}
          value={order.code}
          onChangeText={order.setCode}
          keyboardType="number-pad"
          textContentType="oneTimeCode"
          autoComplete="one-time-code"
          maxLength={6}
          error={order.codeError}
          testID="step-up-code"
        />
      </Screen>
    );
  }
  if (order.phase === 'enrol') {
    return (
      <Screen
        title={t('title.pay')}
        testID="pay-enrol"
        footer={
          <>
            <Button label={t('shop.enrol.open')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(`${config.siteOrigin}/account?tab=security`)} />
            <Button label={t('shop.stepUp.cancel')} tone="ghost" onPress={() => order.setPhase('form')} />
          </>
        }
      >
        <Title>{t('shop.enrol.title')}</Title>
        <Body tone="muted">{t('shop.enrol.body')}</Body>
      </Screen>
    );
  }
  if (order.phase === 'bank' && order.started) {
    const started = order.started;
    return (
      <Screen title={t('title.pay')} testID="pay-bank">
        <Panel style={styles.bank}>
          <Kicker>{t('shop.pay.bankKicker')}</Kicker>
          <Text accessibilityRole="header" style={textStyles.section}>
            {t('shop.pay.bankTitle')}
          </Text>
          <Body>{t('shop.pay.bankBody', { total: f.money(started.totalCents) })}</Body>
          <Field label={t('shop.pay.bankCode')} value={order.bankCode} onChangeText={order.setBankCode} keyboardType="number-pad" testID="bank-code" />
          {order.error ? <Notice message={order.error} /> : null}
          <Button label={t('shop.pay.approve')} busy={order.busy} onPress={() => void order.place(started)} testID="bank-approve" />
        </Panel>
        <Body tone="small">{t('shop.pay.sca')}</Body>
      </Screen>
    );
  }
  return null;
}

/** `/pay` (design 01 `pay`): payment is part of the one-page checkout now (S-178), so the route opens it. */
export function Pay() {
  return <Redirect href="/checkout" />;
}

const styles = StyleSheet.create({
  bank: { marginTop: space[6], gap: space[3], padding: space[4] },
});

