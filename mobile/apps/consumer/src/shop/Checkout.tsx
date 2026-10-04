import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { ApiError, MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import { ageCleared, type AddressInput, type CheckoutBody, type CheckoutSetup, type DeliveryOption, type Substitution } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import { useDeliveryLocation, type DeliveryLocation } from '../location/DeliveryLocation';
import { Body, Button, Notice } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, ErrorState, LoadingList, QueryView, SignInPrompt, Skeleton, errorMessage } from '../ui/states';
import { AgeCheck } from './AgeCheck';
import { serverMessage, shop, useMarket, useShopFormat } from './common';
import { Chip, Kicker, SumRow, shopStyles } from './parts';

export const SUBSTITUTIONS: Substitution[] = ['similar', 'refund', 'ask'];

export interface ChosenAddress {
  input: AddressInput;
  /** `location` = the address saved on this phone (S-98), `saved:<id>` = one of the account's. */
  source: string;
  line1: string;
  line2: string;
}

/**
 * Where the order goes: the address chosen on this phone's Location screen when it is complete (street, city,
 * province, postal code — its "unit / buzzer / drop-off note" becomes the courier's note), else the account's default
 * saved address (`GET /me/checkout` → `addresses`); none → ask for one.
 */
export function chooseAddress(location: DeliveryLocation, setup: Pick<CheckoutSetup, 'addresses'> | undefined): ChosenAddress | null {
  if (location.status === 'saved' && location.street && location.city && location.province && location.postalCode) {
    return {
      source: 'location',
      input: { street: location.street, city: location.city, province: location.province, postal: location.postalCode, ...(location.unit ? { note: location.unit.slice(0, 200) } : {}) },
      line1: location.street,
      line2: [`${location.city} ${location.province} ${location.postalCode}`, location.unit].filter(Boolean).join(' · '),
    };
  }
  const saved = setup?.addresses.find((a) => a.isDefault) ?? setup?.addresses[0];
  if (!saved) return null;
  return {
    source: `saved:${saved.id}`,
    input: { addressId: saved.id },
    line1: saved.unit ? `${saved.street}, ${saved.unit}` : saved.street,
    line2: [`${saved.city} ${saved.province} ${saved.postal}`, saved.note].filter(Boolean).join(' · '),
  };
}

/** The checkout form as a request body (pooled run or direct courier, the address, the substitution choice). */
export const bodyOf = (option: Pick<DeliveryOption, 'kind' | 'windowId'>, address: AddressInput, substitution: Substitution): CheckoutBody => ({
  kind: option.kind,
  windowId: option.windowId ?? null,
  address,
  substitution,
});

/** The quote for a checkout body (`POST /me/checkout/quote`): Checkout and Payment share it by key. */
export function useQuote(body: CheckoutBody | null) {
  const { locale } = useShopFormat();
  return useQuery({
    queryKey: ['shop', 'checkout', 'quote', body, locale],
    queryFn: () => shop().quote(body!, locale),
    enabled: !!body,
    staleTime: 30_000,
    // the last numbers stay on screen while a changed choice is re-quoted
    placeholderData: keepPreviousData,
    retry: (n, e) => n < 2 && !(e instanceof ApiError && !e.transient),
  });
}

/** Items, delivery, each tax and the total — the quote's numbers, or the cart's while the tax is being worked out. */
export function Sums({ subtotalCents, deliveryCents, quote }: { subtotalCents: number; deliveryCents?: number; quote: ReturnType<typeof useQuote> }) {
  const f = useShopFormat();
  const { t } = f;
  const q = quote.data;
  return (
    <View style={styles.sums} testID="sums">
      <SumRow label={t('shop.sum.items')} value={f.money(q?.subtotalCents ?? subtotalCents)} />
      <SumRow label={t('shop.sum.delivery')} value={q ? f.fee(q.deliveryFeeCents) : deliveryCents !== undefined ? f.fee(deliveryCents) : '—'} />
      {q ? (
        q.taxes.map((x) => <SumRow key={`${x.type}${x.percent}`} label={f.tax(x.type, x.percent)} value={f.money(x.cents)} />)
      ) : quote.isFetching ? (
        <Skeleton height={18} width="60%" />
      ) : (
        <SumRow label={t('shop.sum.tax')} value={t('shop.sum.taxLater')} />
      )}
      <SumRow label={t('shop.sum.total')} value={q ? f.money(q.totalCents) : '—'} strong />
    </View>
  );
}

/** The api's answer to a quote, worded for the person: field rules (the address) or the problem's own words. */
export function quoteProblem(error: unknown, t: ReturnType<typeof useShopFormat>['t']): string | null {
  if (!error) return null;
  if (error instanceof ApiError && error.errors.length > 0) return error.errors.map((e) => serverMessage(e.message, t)).join(' ');
  return errorMessage(error, t);
}

/**
 * B5 Checkout (design 01 `checkout`; signed in): deliver to (Change → the Location screen and back), the delivery
 * window (the market's pooled runs and the direct courier with their fees), substitutions, then the quote: items,
 * delivery, each tax by name and rate (from the province — never a fixed "GST 5%"), total. "Redeem points" has no
 * checkout api (S-51) and isn't offered. A cart with age-restricted items shows the age step (2026-10-04) and Continue
 * waits for it. Continue → Payment with the choices in the route.
 */
export function Checkout() {
  const { status } = useAuth();
  const f = useShopFormat();
  const { t, locale } = f;
  const market = useMarket();
  const { location } = useDeliveryLocation();
  const setup = useQuery({
    queryKey: ['shop', 'checkout', 'setup', market.city?.toLowerCase() ?? '', locale],
    queryFn: () => shop().checkout(market.city, locale),
    enabled: status === 'signedIn' && market.ready,
    staleTime: 15_000,
  });
  const [optionId, setOptionId] = useState<string>();
  const [substitution, setSubstitution] = useState<Substitution>('similar');
  const data = setup.data ?? undefined;
  const option = data?.options.find((o) => o.id === optionId) ?? data?.options[0];
  const address = chooseAddress(location, data);
  const body = option && address && data && data.cart.itemCount > 0 ? bodyOf(option, address.input, substitution) : null;
  const quote = useQuote(body);
  const problem = quoteProblem(quote.error, t);
  // 2026-10-04: the age step — the quote's answer for the chosen address (its province), else the setup's
  const age = quote.data?.age ?? data?.age;

  if (status !== 'signedIn') {
    return (
      <Screen title={t('title.checkout')} testID="checkout">
        <SignInPrompt message={t('shop.checkout.signIn')} />
      </Screen>
    );
  }

  const next = () => {
    if (!option || !address) return;
    router.push({
      pathname: '/pay',
      params: { kind: option.kind, window: option.windowId ?? '', sub: substitution, address: address.source },
    });
  };

  return (
    <Screen
      title={t('title.checkout')}
      testID="checkout"
      footer={
        data && data.cart.itemCount > 0 ? (
          <>
            {data.stepUp !== 'none' ? <Body tone="small">{t('shop.checkout.stepUp')}</Body> : null}
            {!ageCleared(age) ? <Body tone="small">{t('shop.age.blocked')}</Body> : null}
            <Button label={t('shop.checkout.continue')} large disabled={!body || !quote.data || quote.isPlaceholderData || !ageCleared(age)} onPress={next} testID="checkout-continue" />
          </>
        ) : undefined
      }
    >
      <QueryView
        query={setup}
        skeleton={<LoadingList rows={5} height={52} />}
        isEmpty={(s) => !s || s.cart.itemCount === 0}
        empty={<EmptyState message={t('shop.cart.empty')} action={t('shop.cart.browse')} onAction={() => router.replace('/search')} />}
      >
        {(s) =>
          s ? (
            <View style={styles.body}>
              <Kicker>{t('shop.checkout.deliverTo')}</Kicker>
              {address ? (
                <View style={styles.address}>
                  <View style={shopStyles.rowText}>
                    <Text style={[styles.body15, shopStyles.strong]}>{address.line1}</Text>
                    <Text style={styles.small}>{address.line2}</Text>
                  </View>
                  <Button label={t('shop.checkout.change')} tone="ghost" hint={t('shop.checkout.changeHint')} onPress={() => router.push('/location?next=/checkout')} />
                </View>
              ) : (
                <View style={styles.gap}>
                  <Body tone="muted">{t('shop.checkout.noAddress')}</Body>
                  <Button label={t('shop.checkout.addAddress')} tone="secondary" onPress={() => router.push('/location?next=/checkout')} testID="checkout-add-address" />
                </View>
              )}

              <Kicker>{t('shop.checkout.window')}</Kicker>
              {!s.served ? (
                <Body tone="muted">{t('shop.checkout.notServed', { place: s.market })}</Body>
              ) : s.options.length === 0 ? (
                <Body tone="muted">{t('shop.checkout.noWindow')}</Body>
              ) : (
                <View style={styles.gap} accessibilityRole="radiogroup">
                  {s.options.map((o) => (
                    <WindowOption key={o.id} option={o} selected={o.id === option?.id} onPress={() => setOptionId(o.id)} />
                  ))}
                </View>
              )}

              <Kicker>{t('shop.checkout.substitution')}</Kicker>
              <View style={shopStyles.chips} accessibilityRole="radiogroup">
                {SUBSTITUTIONS.map((x) => (
                  <Chip key={x} role="radio" label={t(`shop.checkout.${x}`)} on={substitution === x} onPress={() => setSubstitution(x)} testID={`sub-${x}`} />
                ))}
              </View>

              {age?.required ? <AgeCheck age={age} /> : null}
              {problem ? <Notice message={problem} /> : null}
              {quote.isError && !problem ? <ErrorState error={quote.error} onRetry={() => void quote.refetch()} /> : null}
              <Sums subtotalCents={s.cart.subtotalCents} deliveryCents={option?.feeCents} quote={quote} />
            </View>
          ) : null
        }
      </QueryView>
    </Screen>
  );
}

function WindowOption({ option, selected, onPress }: { option: DeliveryOption; selected: boolean; onPress: () => void }) {
  const f = useShopFormat();
  const { t } = f;
  const name = option.kind === 'direct' ? t('shop.win.now', { eta: option.etaMinutes ?? 45 }) : f.window(option, 'shop.win');
  const desc =
    option.kind === 'direct'
      ? t('shop.win.direct')
      : option.households > 0
        ? t('shop.win.pooledWith', { n: option.households })
        : t('shop.win.pooled', { time: f.time(option.orderBy) });
  const price = f.fee(option.feeCents);
  // the design's `opt` with the price on the right
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityLabel={`${name}, ${desc}, ${price}`}
      accessibilityState={{ selected, checked: selected }}
      onPress={onPress}
      style={({ pressed }) => [styles.option, selected && styles.optionOn, pressed && !selected && styles.optionOn]}
      testID={`window-${option.id}`}
    >
      <View style={shopStyles.rowText}>
        <Text style={[styles.body15, shopStyles.strong]}>{name}</Text>
        <Text style={styles.small}>{desc}</Text>
      </View>
      <Text style={shopStyles.price}>{price}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  body: { gap: space[3] },
  gap: { gap: space[2] },
  address: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  body15: { fontFamily: fonts.body, fontSize: 15, color: colors.text },
  small: { fontFamily: fonts.body, fontSize: 13, color: colors.neutral700 },
  sums: { gap: 6, paddingTop: space[3] },
  option: { flexDirection: 'row', alignItems: 'center', gap: 10, minHeight: MIN_TARGET, paddingHorizontal: 14, paddingVertical: space[3], borderRadius: radius.md, borderWidth: 1, borderColor: colors.divider },
  optionOn: { borderColor: colors.accent, backgroundColor: colors.accent100 },
});
