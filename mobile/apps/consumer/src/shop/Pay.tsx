import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router, useLocalSearchParams } from 'expo-router';
import { useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, NetworkError, colors, fonts, randomId, space } from '@northline/mobile-kit';

import type { CheckoutBody, Started, Substitution } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import type { MessageKey } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { Body, Button, Field, Notice, Option, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, SignInPrompt, errorMessage } from '../ui/states';
import { SUBSTITUTIONS, Sums, bodyOf, chooseAddress, extrasOf, useQuote } from './Checkout';
import { CART_KEY, problemCode, shop, useMarket, useShopFormat } from './common';
import { cardPaymentsFor, pendingIntents, type PayMethod } from './payments';
import { Kicker, Panel } from './parts';
import { StepUpFailed, stepUpWithCode, type StepUpFailure } from './stepUp';

type Phase = 'form' | 'stepUp' | 'enrol' | 'bank';
/** Why the authenticator code wasn't taken, in words (the reasons aren't the copy keys: `wrong_code` → `wrong`). */
const STEP_UP_MESSAGE: Record<StepUpFailure, MessageKey> = { wrong_code: 'shop.stepUp.wrong', locked: 'shop.stepUp.locked', elsewhere: 'shop.stepUp.elsewhere' };
const KNOWN = ['out_of_stock', 'window_closed', 'cart_empty', 'checkout_expired', 'payment_not_authorized'];

/**
 * B6 Payment + 3-D Secure (design 01 `pay`; signed in). The saved cards (`GET /me/payment-methods`) or "+ New card",
 * the escrow note, "Pay {total}":
 *   1. `POST /me/checkouts` (Idempotency-Key kept for every retry of this Pay; `X-Step-Up` when the api asks — the
 *      authenticator code is confirmed with northline-auth first, or the person is told how to add a second factor);
 *   2. the PaymentIntents: Stripe's SDK (PaymentSheet for a new card, the saved card's PaymentMethod otherwise; 3-D
 *      Secure is Stripe's own native screen), or with the api's stand-in the design's bank step (`pay3ds`);
 *   3. `POST /me/checkouts/{id}/place` → Order confirmed.
 * Apple Pay / Google Pay need merchant ids that don't exist yet (MOBILE_PLAN § API gaps) and aren't offered.
 */
export function Pay() {
  const { status } = useAuth();
  const f = useShopFormat();
  const { t, locale } = f;
  const qc = useQueryClient();
  const params = useLocalSearchParams<{ kind?: string; window?: string; sub?: string; address?: string; promo?: string; points?: string; tip?: string }>();
  const { location } = useDeliveryLocation();
  const market = useMarket();
  const signedIn = status === 'signedIn';
  const setup = useQuery({
    queryKey: ['shop', 'checkout', 'setup', market.city?.toLowerCase() ?? '', locale],
    queryFn: () => shop().checkout(market.city, locale),
    enabled: signedIn && market.ready,
    staleTime: 15_000,
  });
  const cards = useQuery({ queryKey: ['shop', 'cards'], queryFn: () => shop().cards(), enabled: signedIn, staleTime: 60_000 });

  const address =
    params.address === 'location'
      ? chooseAddress(location, undefined)?.input
      : params.address?.startsWith('saved:')
        ? { addressId: params.address.slice('saved:'.length) }
        : undefined;
  const kind = params.kind === 'pooled' || params.kind === 'direct' ? params.kind : undefined;
  const sub = SUBSTITUTIONS.includes(params.sub as Substitution) ? (params.sub as Substitution) : 'similar';
  const body: CheckoutBody | null = kind && address ? bodyOf({ kind, windowId: params.window || null }, address, sub, extrasOf(params)) : null;
  const quote = useQuote(signedIn ? body : null);

  const [method, setMethod] = useState<string>();
  const [phase, setPhase] = useState<Phase>('form');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [bankCode, setBankCode] = useState('482 913');
  const [started, setStarted] = useState<Started | null>(null);
  // one Idempotency-Key per Pay, the same for every retry until the payment goes through (MOBILE_PLAN § Data)
  const payKey = useRef(randomId());
  const placeKey = useRef(randomId());

  const provider = started?.payment.provider ?? setup.data?.payment.provider ?? 'fake';
  const savedCards = cards.data?.items ?? [];
  const chosen = method ?? savedCards.find((c) => c.isDefault)?.id ?? savedCards[0]?.id ?? 'new';
  const total = quote.data ? f.money(quote.data.totalCents) : '—';

  if (!signedIn) {
    return (
      <Screen title={t('title.pay')} testID="pay">
        <SignInPrompt message={t('shop.checkout.signIn')} />
      </Screen>
    );
  }
  if (!body) {
    return (
      <Screen title={t('title.pay')} testID="pay">
        <EmptyState message={t('shop.pay.missing')} action={t('shop.pay.backToCheckout')} onAction={() => router.replace('/checkout')} />
      </Screen>
    );
  }

  const fail = (e: unknown) => {
    const c = problemCode(e);
    setError(c && KNOWN.includes(c) ? t(`shop.pay.err.${c}` as MessageKey) : e instanceof ApiError || e instanceof NetworkError ? errorMessage(e, t) : t('shop.pay.err.generic'));
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

  const place = async (s: Started) => {
    setBusy(true);
    try {
      const placed = await shop().place(s.checkoutId, placeKey.current);
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
    const how: PayMethod = chosen === 'new' ? { kind: 'new' } : { kind: 'saved', paymentMethodId: chosen };
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
      setCodeError(e instanceof StepUpFailed ? t(STEP_UP_MESSAGE[e.reason]) : errorMessage(e, t));
    }
  };

  if (phase === 'stepUp') {
    return (
      <Screen
        title={t('title.pay')}
        testID="pay-step-up"
        footer={
          <>
            <Button label={t('shop.stepUp.confirm')} large busy={busy} onPress={() => void confirmCode()} testID="step-up-confirm" />
            <Button label={t('shop.stepUp.cancel')} tone="ghost" onPress={() => setPhase('form')} />
          </>
        }
      >
        <Title>{t('shop.stepUp.title')}</Title>
        <Body tone="muted">{t('shop.stepUp.body')}</Body>
        <Field
          label={t('shop.stepUp.code')}
          value={code}
          onChangeText={setCode}
          keyboardType="number-pad"
          textContentType="oneTimeCode"
          autoComplete="one-time-code"
          maxLength={6}
          error={codeError}
          testID="step-up-code"
        />
      </Screen>
    );
  }

  if (phase === 'enrol') {
    return (
      <Screen
        title={t('title.pay')}
        testID="pay-enrol"
        footer={
          <>
            <Button label={t('shop.enrol.open')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(`${config.siteOrigin}/account?tab=security`)} />
            <Button label={t('shop.stepUp.cancel')} tone="ghost" onPress={() => setPhase('form')} />
          </>
        }
      >
        <Title>{t('shop.enrol.title')}</Title>
        <Body tone="muted">{t('shop.enrol.body')}</Body>
      </Screen>
    );
  }

  if (phase === 'bank' && started) {
    return (
      <Screen title={t('title.pay')} testID="pay-bank">
        <Panel style={styles.bank}>
          <Kicker>{t('shop.pay.bankKicker')}</Kicker>
          <Text accessibilityRole="header" style={styles.h3}>
            {t('shop.pay.bankTitle')}
          </Text>
          <Body>{t('shop.pay.bankBody', { total: f.money(started.totalCents) })}</Body>
          <Field label={t('shop.pay.bankCode')} value={bankCode} onChangeText={setBankCode} keyboardType="number-pad" testID="bank-code" />
          {error ? <Notice message={error} /> : null}
          <Button label={t('shop.pay.approve')} busy={busy} onPress={() => void place(started)} testID="bank-approve" />
        </Panel>
        <Body tone="small">{t('shop.pay.sca')}</Body>
      </Screen>
    );
  }

  return (
    <Screen
      title={t('title.pay')}
      testID="pay"
      footer={
        <>
          {error ? <Notice message={error} testID="pay-error" /> : null}
          <Button label={t('shop.pay.pay', { total })} large busy={busy} disabled={!quote.data || quote.isPlaceholderData} onPress={() => void pay()} testID="pay-button" />
        </>
      }
    >
      <Kicker>{t('shop.pay.withCard')}</Kicker>
      {cards.isPending ? (
        <LoadingList rows={2} height={48} />
      ) : (
        <View style={styles.cards} accessibilityRole="radiogroup">
          {savedCards.map((c) => (
            <Option
              key={c.id}
              selected={chosen === c.id}
              onPress={() => setMethod(c.id)}
              title={t('shop.pay.card', { brand: c.brand, last4: c.last4 })}
              description={`${String(c.expMonth).padStart(2, '0')}/${String(c.expYear % 100).padStart(2, '0')}`}
              testID={`card-${c.id}`}
            />
          ))}
          <Option selected={chosen === 'new'} onPress={() => setMethod('new')} title={t('shop.pay.newCard')} testID="card-new" />
        </View>
      )}
      {chosen === 'new' && provider === 'stripe' ? <Body tone="small">{t('shop.pay.newCardNote')}</Body> : null}
      {provider === 'fake' ? <Notice tone="info" message={t('shop.pay.fake')} /> : null}
      <Panel>
        <Body tone="small">{t('shop.pay.note', { total })}</Body>
      </Panel>
      <Sums subtotalCents={quote.data?.subtotalCents ?? 0} quote={quote} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  cards: { gap: space[2] },
  bank: { marginTop: space[6], gap: space[3], padding: space[4] },
  h3: { fontFamily: fonts.heading, fontSize: 20, lineHeight: 24, color: colors.text },
});
