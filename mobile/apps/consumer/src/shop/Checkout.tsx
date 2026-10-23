import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import {
  ApiError,
  Button as KitButton,
  Icon,
  LINE,
  MetaLine,
  OptionCard,
  PhotoWell,
  Price,
  SIZE,
  Segmented,
  TYPE,
  radius,
  semantic,
  space,
  textStyles,
} from '@northline/mobile-kit';

import { CheckoutConsent, useCheckoutConsent } from '../account/CheckoutConsent';
import { PromoPoints, TipPicker } from '../aftercare/PromoPoints';
import type { TipChoice } from '../api/aftercare';
import { ageCleared, type AddressInput, type CheckoutBody, type CheckoutSetup, type DeliveryOption, type SavedAddress, type Substitution } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import { useDeliveryLocation, type DeliveryLocation } from '../location/DeliveryLocation';
import { iconFor, photoSource } from '../ui/commerce';
import { Body, Button, Notice } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, ErrorState, LoadingList, QueryView, SignInPrompt, Skeleton, errorMessage } from '../ui/states';
import { AgeCheck } from './AgeCheck';
import { serverMessage, shop, useMarket, useShopFormat } from './common';
import { SumRow } from './parts';
import { PayPhase, usePlaceOrder } from './Pay';
import { CheckoutSection } from './steps';

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
  return saved ? savedAddress(saved) : null;
}

/** One of the account's saved addresses as the checkout shows it. */
export function savedAddress(saved: SavedAddress): ChosenAddress {
  return {
    source: `saved:${saved.id}`,
    input: { addressId: saved.id },
    line1: saved.unit ? `${saved.street}, ${saved.unit}` : saved.street,
    line2: [`${saved.city} ${saved.province} ${saved.postal}`, saved.note].filter(Boolean).join(' · '),
  };
}

/** Mobile gaps part 2: the promo code, points and the courier's tip — each sent only when set. */
export interface Extras { promoCode?: string; usePoints?: boolean; tip?: TipChoice }
export const NO_TIP: TipChoice = { kind: 'none', value: 0 };

/** The checkout form as a request body (pooled run or direct courier, the address, the substitution choice, extras). */
export const bodyOf = (option: Pick<DeliveryOption, 'kind' | 'windowId'>, address: AddressInput, substitution: Substitution, extras: Extras = {}): CheckoutBody => ({
  kind: option.kind,
  windowId: option.windowId ?? null,
  address,
  substitution,
  ...(extras.promoCode ? { promoCode: extras.promoCode } : {}),
  ...(extras.usePoints ? { usePoints: true } : {}),
  ...(extras.tip && extras.tip.kind !== 'none' ? { tip: extras.tip } : {}),
});

/** The extras as route params (Checkout → Payment) and back. */
export const extrasParams = (x: Extras) => ({ promo: x.promoCode ?? '', points: x.usePoints ? '1' : '', tip: x.tip && x.tip.kind !== 'none' ? `${x.tip.kind}:${x.tip.value}` : '' });
export function extrasOf(p: { promo?: string; points?: string; tip?: string }): Extras {
  const [kind, value] = (p.tip ?? '').split(':');
  const tip: TipChoice | undefined = (kind === 'amount' || kind === 'percent') && Number(value) > 0 ? { kind, value: Number(value) } : undefined;
  return { ...(p.promo ? { promoCode: p.promo } : {}), ...(p.points === '1' ? { usePoints: true } : {}), ...(tip ? { tip } : {}) };
}

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

/** Items, delivery, the code and points, each tax, the tip and the total — the quote's numbers, or the cart's while the tax is being worked out. */
export function Sums({ subtotalCents, deliveryCents, quote }: { subtotalCents: number; deliveryCents?: number; quote: ReturnType<typeof useQuote> }) {
  const f = useShopFormat();
  const { t } = f;
  const q = quote.data;
  return (
    <View style={styles.sums} testID="sums">
      <SumRow label={t('shop.sum.items')} value={f.money(q?.subtotalCents ?? subtotalCents)} />
      <SumRow label={t('shop.sum.delivery')} value={q ? f.fee(q.deliveryFeeCents) : deliveryCents !== undefined ? f.fee(deliveryCents) : '—'} />
      {q?.discountCents ? <SumRow label={t('promo.sum.discount', { code: q.promoCode ?? '' })} value={`−${f.money(q.discountCents)}`} tone="accent2" /> : null}
      {q ? (
        q.taxes.map((x) => <SumRow key={`${x.type}${x.percent}`} label={f.tax(x.type, x.percent)} value={f.money(x.cents)} />)
      ) : quote.isFetching ? (
        <Skeleton height={18} width="60%" />
      ) : (
        <SumRow label={t('shop.sum.tax')} value={t('shop.sum.taxLater')} />
      )}
      {q?.tipCents ? <SumRow label={t('promo.sum.tip')} value={f.money(q.tipCents)} /> : null}
      {q?.pointsCents ? <SumRow label={t('promo.sum.points')} value={`−${f.money(q.pointsCents)}`} tone="accent2" /> : null}
      <SumRow label={t('shop.sum.total')} value={q ? f.money(q.totalCents) : '—'} strong />
    </View>
  );
}

/** The api's answer to a quote, worded for the person: field rules (the address) or the problem's own words. The promo code's own rule shows at its field. */
export function quoteProblem(error: unknown, t: ReturnType<typeof useShopFormat>['t']): string | null {
  if (!error) return null;
  if (error instanceof ApiError && error.errors.length > 0) {
    const rest = error.errors.filter((e) => e.field !== 'promoCode');
    return rest.length ? rest.map((e) => serverMessage(e.message, t)).join(' ') : null;
  }
  return errorMessage(error, t);
}

/** Why the promo code wasn't taken (`promoCode` in a 422), in the app's language. */
export const promoProblem = (error: unknown) => (error instanceof ApiError ? (error.fieldMessage('promoCode') ?? null) : null);

/**
 * The one-page checkout (E-15 A · Plainsight, design/revamp/a.html "Cart and one-page checkout" on a phone; before, a
 * second screen at /pay): six numbered sections in one panel — 1 the delivery address (Change → the Location screen
 * and back), 2 the delivery window (the market's pooled runs and the direct courier as option cards, the fee on the
 * right), 3 what to do if an item is out of stock (stacked joined boxes), 4 the courier's tip (joined boxes), 5
 * payment (the saved cards or a new one, as option cards; the escrow note), 6 the items with who sells them — then the
 * summary: Place your order, the terms, the promo code and points, the quote (items, delivery, the code's discount,
 * each tax by name and rate from the province, the tip, the points, the total). The sticky bar keeps the total and
 * Place your order in reach. A cart with age-restricted items shows the age step (2026-10-04) and placing waits for
 * it. The same APIs as the web's /checkout (`GET /me/checkout`, `POST /me/checkout/quote`, then {@link usePlaceOrder}).
 */
export function Checkout() {
  const { status } = useAuth();
  const f = useShopFormat();
  const { t, locale } = f;
  const signedIn = status === 'signedIn';
  const market = useMarket();
  const { location } = useDeliveryLocation();
  const setup = useQuery({
    queryKey: ['shop', 'checkout', 'setup', market.city?.toLowerCase() ?? '', locale],
    queryFn: () => shop().checkout(market.city, locale),
    enabled: signedIn && market.ready,
    staleTime: 15_000,
  });
  const cards = useQuery({ queryKey: ['shop', 'cards'], queryFn: () => shop().cards(), enabled: signedIn, staleTime: 60_000 });
  const consent = useCheckoutConsent(signedIn); // S-157: the optional offers box (CASL)
  const [optionId, setOptionId] = useState<string>();
  const [substitution, setSubstitution] = useState<Substitution>('similar');
  const [promoCode, setPromoCode] = useState<string>();
  const [usePoints, setUsePoints] = useState(false);
  const [tip, setTip] = useState<TipChoice>(NO_TIP);
  const [method, setMethod] = useState<string>();
  const extras: Extras = { promoCode, usePoints, tip };
  const data = setup.data ?? undefined;
  const option = data?.options.find((o) => o.id === optionId) ?? data?.options[0];
  const address = chooseAddress(location, data);
  const body = option && address && data && data.cart.itemCount > 0 ? bodyOf(option, address.input, substitution, extras) : null;
  const quote = useQuote(body);
  const problem = quoteProblem(quote.error, t);
  // 2026-10-04: the age step — the quote's answer for the chosen address (its province), else the setup's
  const age = quote.data?.age ?? data?.age;
  const savedCards = cards.data?.items ?? [];
  const card = method ?? savedCards.find((c) => c.isDefault)?.id ?? savedCards[0]?.id ?? 'new';
  const order = usePlaceOrder(body, card, consent);
  const provider = order.started?.payment.provider ?? data?.payment.provider ?? 'fake';

  if (!signedIn) {
    return (
      <Screen title={t('title.checkout')} testID="checkout">
        <SignInPrompt message={t('shop.checkout.signIn')} />
      </Screen>
    );
  }
  if (order.phase !== 'form') return <PayPhase order={order} />;

  const ready = !!body && !!quote.data && !quote.isPlaceholderData && ageCleared(age);
  const place = (testID: string, block?: boolean) => (
    <KitButton label={t('rv.checkout.place')} size="lg" block={block} busy={order.busy} disabled={!ready} onPress={() => void order.pay()} style={block ? undefined : styles.barButton} testID={testID} />
  );

  return (
    <Screen
      title={t('title.checkout')}
      testID="checkout"
      footer={
        data && data.cart.itemCount > 0 ? (
          <>
            {order.error ? <Notice message={order.error} testID="pay-error" /> : null}
            {!ageCleared(age) ? <Body tone="small">{t('shop.age.blocked')}</Body> : null}
            <View style={styles.bar}>
              <View style={styles.flex}>
                <Text style={textStyles.meta}>{t('shop.sum.total')}</Text>
                {quote.data ? <Price cents={quote.data.totalCents} size="md" /> : <Text style={textStyles.price}>—</Text>}
              </View>
              {place('pay-button')}
            </View>
          </>
        ) : undefined
      }
    >
      <QueryView
        query={setup}
        skeleton={<LoadingList rows={5} height={space[12]} />}
        isEmpty={(s) => !s || s.cart.itemCount === 0}
        empty={<EmptyState art="grocery" message={t('shop.cart.empty')} action={t('shop.cart.browse')} onAction={() => router.replace('/search')} />}
      >
        {(s) =>
          s ? (
            <View style={styles.body}>
              <View style={styles.heading}>
                <Icon name="lock-simple" size={TYPE.price} color={semantic['text-muted']} />
                <Body tone="muted" style={styles.flex}>
                  {t('rv.checkout.summary', { items: s.cart.itemCount, shops: s.cart.shopCount })}
                </Body>
              </View>
              {s.stepUp !== 'none' ? <Body tone="small">{t('shop.checkout.stepUp')}</Body> : null}

              <View style={styles.panel}>
                <CheckoutSection n={1} title={t('rv.checkout.address')} onChange={address ? () => router.push('/location?next=/checkout') : undefined} changeLabel={address ? `${t('shop.checkout.change')}: ${t('rv.checkout.address')}` : undefined}>
                  {address ? (
                    <OptionCard
                      title={address.line1}
                      description={address.line2}
                      selected
                      role="button"
                      onPress={() => router.push('/location?next=/checkout')}
                      testID="checkout-address"
                    />
                  ) : (
                    <>
                      <Body tone="muted">{t('shop.checkout.noAddress')}</Body>
                      <Button label={t('shop.checkout.addAddress')} tone="secondary" hint={t('shop.checkout.changeHint')} onPress={() => router.push('/location?next=/checkout')} testID="checkout-add-address" />
                    </>
                  )}
                </CheckoutSection>

                <CheckoutSection n={2} title={t('shop.checkout.window')}>
                  {!s.served ? (
                    <Body tone="muted">{t('shop.checkout.notServed', { place: s.market })}</Body>
                  ) : s.options.length === 0 ? (
                    <Body tone="muted">{t('shop.checkout.noWindow')}</Body>
                  ) : (
                    <View style={styles.gap} accessibilityRole="radiogroup" accessibilityLabel={t('shop.checkout.window')}>
                      {s.options.map((o) => (
                        <WindowOption key={o.id} option={o} selected={o.id === option?.id} onPress={() => setOptionId(o.id)} />
                      ))}
                    </View>
                  )}
                  {option?.packBy ? <MetaLine icon="truck">{t('rv.checkout.packBy', { time: f.time(option.packBy), shops: s.cart.shopCount })}</MetaLine> : null}
                </CheckoutSection>

                <CheckoutSection n={3} title={t('rv.checkout.substitution')}>
                  <Segmented
                    label={t('rv.checkout.substitution')}
                    stack
                    options={SUBSTITUTIONS.map((x) => ({ value: x, label: t(`shop.checkout.${x}`) }))}
                    value={substitution}
                    onChange={setSubstitution}
                    testID="sub"
                  />
                </CheckoutSection>

                <CheckoutSection n={4} title={t('rv.checkout.tip')}>
                  <Body tone="small">{t('tip.note')}</Body>
                  <TipPicker value={tip} onChange={setTip} />
                </CheckoutSection>

                <CheckoutSection n={5} title={t('rd.checkout.step2')}>
                  {cards.isPending ? (
                    <LoadingList rows={2} height={space[12]} />
                  ) : (
                    <View style={styles.gap} accessibilityRole="radiogroup" accessibilityLabel={t('shop.pay.withCard')}>
                      {savedCards.map((c) => (
                        <OptionCard
                          key={c.id}
                          selected={card === c.id}
                          onPress={() => setMethod(c.id)}
                          title={t('shop.pay.card', { brand: c.brand, last4: c.last4 })}
                          description={t('rv.checkout.expires', { date: `${String(c.expMonth).padStart(2, '0')}/${String(c.expYear % 100).padStart(2, '0')}` })}
                          testID={`card-${c.id}`}
                        />
                      ))}
                      <OptionCard selected={card === 'new'} onPress={() => setMethod('new')} title={t('rv.checkout.newCard')} testID="card-new" />
                    </View>
                  )}
                  {card === 'new' && provider === 'stripe' ? <Body tone="small">{t('shop.pay.newCardNote')}</Body> : null}
                  {provider === 'fake' ? <Notice tone="info" message={t('shop.pay.fake')} /> : null}
                  <MetaLine icon="shield-check">{t('shop.pay.note', { total: quote.data ? f.money(quote.data.totalCents) : '—' })}</MetaLine>
                </CheckoutSection>

                <CheckoutSection n={6} title={t('rv.checkout.review')} testID="checkout-review">
                  {s.cart.groups.flatMap((g) =>
                    g.items.map((it) => (
                      <View key={it.itemId} style={styles.item} accessible accessibilityLabel={[it.name, t('rv.checkout.soldBy', { shop: g.shopName }), t('rd.checkout.qty', { qty: it.qty }), f.money(it.lineCents)].join(', ')}>
                        <View style={styles.thumb}>
                          <PhotoWell source={photoSource(it.imageUrl)} icon={iconFor('product')} inset={space[1]} />
                        </View>
                        <View style={styles.flex}>
                          <Text style={textStyles.bodyStrong} numberOfLines={2}>
                            {it.name}
                          </Text>
                          <Text style={textStyles.meta}>{[t('rv.checkout.soldBy', { shop: g.shopName }), it.qty > 1 ? t('rd.checkout.qty', { qty: it.qty }) : null].filter(Boolean).join(' · ')}</Text>
                        </View>
                        <Text style={textStyles.priceSm}>{f.money(it.lineCents)}</Text>
                      </View>
                    )),
                  )}
                  {age?.required ? <AgeCheck age={age} /> : null}
                </CheckoutSection>
              </View>

              <View style={[styles.panel, styles.summary]} testID="checkout-summary">
                {place('pay-button-box', true)}
                <Text style={[textStyles.meta, styles.center]}>{t('rd.checkout.terms')}</Text>
                <Text accessibilityRole="header" style={textStyles.label}>
                  {t('promo.title')}
                </Text>
                <PromoPoints
                  code={promoCode}
                  onCode={setPromoCode}
                  error={promoProblem(quote.error)}
                  discountCents={quote.data?.discountCents}
                  usePoints={usePoints}
                  onUsePoints={setUsePoints}
                  pointsAvailable={quote.data?.pointsAvailable}
                  pointsCents={quote.data?.pointsCents}
                />
                {problem ? <Notice message={problem} /> : null}
                {quote.isError && !problem && !promoProblem(quote.error) ? <ErrorState error={quote.error} onRetry={() => void quote.refetch()} /> : null}
                <Sums subtotalCents={s.cart.subtotalCents} deliveryCents={option?.feeCents} quote={quote} />
                <CheckoutConsent consent={consent} />
              </View>
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
    option.kind === 'direct' ? t('shop.win.direct') : option.households > 0 ? t('shop.win.pooledWith', { n: option.households }) : t('shop.win.pooled', { time: f.time(option.orderBy) });
  const fee = f.fee(option.feeCents);
  return <OptionCard title={name} description={desc} right={<Text style={textStyles.bodyStrong}>{fee}</Text>} label={`${name}, ${desc}, ${fee}`} selected={selected} onPress={onPress} testID={`window-${option.id}`} />;
}

const THUMB = SIZE.rail - space[2];

const styles = StyleSheet.create({
  sums: { gap: space[2], paddingTop: space[3], borderTopWidth: LINE.hair, borderTopColor: semantic.border },
  flex: { flex: 1, minWidth: 0, gap: LINE.bold },
  body: { gap: space[4] },
  heading: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  gap: { gap: space[2] },
  panel: { paddingHorizontal: space[4], borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: semantic.surface },
  summary: { gap: space[3], paddingVertical: space[4] },
  center: { textAlign: 'center' },
  item: { flexDirection: 'row', alignItems: 'center', gap: space[3], paddingBottom: space[3], borderBottomWidth: LINE.hair, borderBottomColor: semantic.border },
  thumb: { width: THUMB },
  bar: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  barButton: { flex: 1.3 },
});
