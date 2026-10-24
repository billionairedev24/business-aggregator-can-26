import { useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Pressable, Share, StyleSheet, Text, View } from 'react-native';

import {
  ApiError,
  Button as KitButton,
  CutoffPill,
  DealBadge,
  Icon,
  LINE,
  MIN_TARGET,
  MetaLine,
  PhotoWell,
  Price,
  ProductCard,
  QuantityStepper,
  Rating,
  Segmented,
  StatusTag,
  TYPE,
  radius,
  semantic,
  space,
  textStyles,
} from '@northline/mobile-kit';

import { Body, Notice } from '../ui/primitives';
import { iconFor, photoSource, unitOf } from '../ui/commerce';
import { GUTTER, Header, RoundButton, Screen } from '../ui/screen';
import { EmptyState, QueryView, Skeleton, errorMessage } from '../ui/states';
import { config } from '../config';
import { serverMessage, shop, useAddToCart, useMarket, useMinutesLeft, useShopFormat } from './common';
import { SectionHead, Strip } from './parts';


/**
 * Product (E-15 A · Plainsight, design/revamp/a.html "Product page with buy box" on a phone): the photo first, edge to
 * edge in the grey well (product on white; the dashed well with the category icon when there is none), Back and
 * Share over it; the shop as a link, the name, the rating, the tags; the options as joined boxes (the chosen one solid
 * ink); about the item and its bullets; the buy box — the price and unit price, delivery on the next pooled run to
 * the person's place with the order-by countdown, stock, the quantity, Add to cart, who sells it and the payment held;
 * "More from {shop}". The sticky bar keeps the price, the window and Add to cart in reach. `GET
 * /public/shop/products/{id}?market=` (S-50; the id may be an offer's, `?offer=` picks the seller); Add is `POST
 * /cart/items` (guests too) and opens the cart.
 */
export function Product() {
  const f = useShopFormat();
  const { t, locale } = f;
  const { id, offer: offerParam } = useLocalSearchParams<{ id: string; offer?: string }>();
  const market = useMarket();
  const product = useQuery({
    queryKey: ['shop', 'product', id, market.city?.toLowerCase() ?? '', locale],
    queryFn: () => shop().product(id, market.city, locale),
    enabled: market.ready && !!id,
    staleTime: 30_000,
  });
  const offer = product.data?.offers.find((o) => o.offerId === offerParam) ?? product.data?.offers[0];
  const [variantId, setVariantId] = useState<string>();
  const [qty, setQty] = useState(1);
  const add = useAddToCart();
  // the first option is chosen to start with
  const chosen = variantId ?? offer?.variants[0]?.variantId;
  const variant = offer?.variants.find((v) => v.variantId === chosen);
  const unitCents = variant?.priceCents ?? offer?.priceCents ?? 0;
  const stock = variant?.stock ?? offer?.stock ?? 0;
  const needsVariant = !!offer && offer.variants.length > 0 && !variant;
  const tonight = offer?.runs.find((r) => r.day === 'today');
  const next = offer?.runs[0];
  const left = useMinutesLeft(next?.orderBy);
  const soldOut = stock <= 0;

  const addToCart = () => {
    if (!offer) return;
    add.mutate({ offerId: offer.offerId, ...(variant ? { variantId: variant.variantId } : {}), qty }, { onSuccess: () => router.navigate('/cart') });
  };
  const addError = add.error ? (add.error instanceof ApiError && add.error.status === 422 ? serverMessage(add.error.message, t) : errorMessage(add.error, t)) : null;
  const share = () => {
    if (!product.data) return;
    void Share.share({ message: `${product.data.name} — ${config.siteOrigin}/products/${encodeURIComponent(product.data.productId)}` }).catch(() => undefined);
  };
  const windowText = next ? f.window(next, 'shop.win') : null;
  const addLabel = soldOut ? t('shop.product.soldOut') : qty > 1 ? t('shop.product.add', { qty, total: f.money(unitCents * qty) }) : t('rv.card.add');

  return (
    <Screen
      testID="product"
      padded={false}
      top={product.data && offer ? undefined : <Header title={t('title.product')} />}
      footer={
        offer && product.data?.served ? (
          <>
            {addError ? <Notice message={addError} /> : null}
            <View style={styles.sticky}>
              <View style={styles.stickyText}>
                <Price cents={unitCents * qty} size="md" />
                {windowText ? (
                  <Text style={textStyles.meta} numberOfLines={1}>
                    {windowText}
                  </Text>
                ) : null}
              </View>
              <KitButton label={addLabel} size="lg" busy={add.isPending} disabled={soldOut || needsVariant} onPress={addToCart} style={styles.stickyButton} testID="product-add" />
            </View>
          </>
        ) : undefined
      }
    >
      <QueryView
        query={product}
        skeleton={
          <View testID="loading" accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.skeleton}>
            <Skeleton height={space[16] * 5} />
            <Skeleton height={space[6]} width="70%" />
            <Skeleton height={space[4]} width="50%" />
          </View>
        }
        isEmpty={(p) => !p || !p.served || p.offers.length === 0}
        empty={
          <View style={styles.pad}>
            <EmptyState art="shop" message={t('shop.product.notServed', { place: product.data?.market ?? market.label ?? '' })} action={t('shop.cart.browse')} onAction={() => router.navigate('/home')} />
          </View>
        }
      >
        {(p) => {
          if (!p || !offer) return null;
          const images = variant?.images?.length ? variant.images : offer.images;
          return (
            <View style={styles.body}>
              <View>
                <PhotoWell source={photoSource(images[0])} icon={iconFor('product')} caption={offer.shopName} inset={0} label={t('shop.product.image', { name: p.name })} style={styles.gallery} testID={images[0] ? 'product-photo' : 'no-photo'} />
                <View style={styles.overlay}>
                  <RoundButton icon="caret-left" label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/home'))} onPhoto testID="header-back" />
                  <RoundButton icon="export" label={t('rd.product.share')} onPress={share} onPhoto testID="product-share" />
                </View>
                {images.length > 1 ? (
                  <View style={styles.counter} accessible={false}>
                    <Text style={styles.counterText}>{t('rv.product.photoCount', { n: 1, total: images.length })}</Text>
                  </View>
                ) : null}
              </View>

              <View style={styles.pad}>
                <Pressable
                  accessibilityRole="link"
                  accessibilityLabel={t('rv.product.visit', { shop: p.brand ?? offer.shopName })}
                  onPress={() => router.push({ pathname: '/search', params: { department: p.departmentSlug, name: p.departmentName } })}
                  style={styles.visit}
                >
                  <Icon name="storefront" size={TYPE.body} color={semantic.link} />
                  <Text style={[textStyles.label, textStyles.link]}>{p.brand ?? offer.shopName}</Text>
                </Pressable>
                <Text accessibilityRole="header" style={styles.name}>
                  {p.name}
                </Text>
                {offer.ratingCount > 0 ? <Rating value={offer.rating} count={offer.ratingCount} /> : null}
                <View style={styles.tags}>
                  {tonight ? <StatusTag label={t('shop.tag.tonight')} tone="info" /> : null}
                  {offer.condition ? <StatusTag label={offer.condition} /> : null}
                </View>
              </View>

              {offer.variants.length > 0 ? (
                <View style={[styles.pad, styles.options]}>
                  <Text style={textStyles.body}>
                    {t('shop.product.options')}: <Text style={styles.strong}>{variant?.value ?? ''}</Text>
                  </Text>
                  <Segmented label={t('shop.product.options')} options={offer.variants.map((v) => ({ value: v.variantId, label: v.value }))} value={chosen ?? null} onChange={setVariantId} testID="variant" />
                </View>
              ) : null}

              {p.description || p.bullets.length ? (
                <View style={[styles.pad, styles.about]}>
                  <Text accessibilityRole="header" style={textStyles.sub}>
                    {t('rv.product.about')}
                  </Text>
                  {p.description ? <Body>{p.description}</Body> : null}
                  {p.bullets.map((b) => (
                    <View key={b} style={styles.bullet}>
                      <Text style={textStyles.body} importantForAccessibility="no">
                        •
                      </Text>
                      <Body style={styles.flex}>{b}</Body>
                    </View>
                  ))}
                  {p.unit ? (
                    <View style={styles.spec}>
                      <Text style={[textStyles.label, styles.specKey]}>{t('rv.product.size')}</Text>
                      <Text style={[textStyles.body, styles.flex]}>{p.unit}</Text>
                    </View>
                  ) : null}
                </View>
              ) : null}

              <View style={styles.pad}>
                <View style={styles.box} testID="buy-box">
                  {offer.compareAtCents && offer.compareAtCents > unitCents ? (
                    <DealBadge label={t('product.dealOff', { n: Math.round((1 - unitCents / offer.compareAtCents) * 100) })} testID="product-deal" />
                  ) : null}
                  <Price cents={unitCents} unit={unitOf(unitCents, p.unit)} was={offer.compareAtCents && offer.compareAtCents > unitCents ? offer.compareAtCents : undefined} size="lg" />
                  {next ? (
                    <View style={styles.boxLine}>
                      <Icon name="truck" size={TYPE.price} color={semantic.text} />
                      <View style={styles.flex}>
                        <Text style={textStyles.bodyStrong}>{t('rv.product.delivery', { when: f.window(next, 'shop.when') })}</Text>
                        <Text style={textStyles.meta}>{t('rv.product.deliveryTo', { place: market.label ?? p.market, fee: f.fee(next.feeCents) })}</Text>
                        {left !== null && left > 0 ? (
                          <View style={styles.orderWithin}>
                            <CutoffPill label={left >= 60 ? t('rv.product.withinHours', { h: Math.floor(left / 60), m: left % 60 }) : t('rv.product.withinMinutes', { m: left })} testID="product-cutoff" />
                          </View>
                        ) : null}
                      </View>
                    </View>
                  ) : null}
                  {soldOut ? (
                    <MetaLine icon="warning" tone="warning">
                      {t('shop.product.soldOut')}
                    </MetaLine>
                  ) : offer.lowStock ? (
                    <MetaLine icon="warning" tone="warning">
                      {t('shop.product.lowStock', { n: stock })}
                    </MetaLine>
                  ) : (
                    <MetaLine icon="check" tone="success">
                      {t('rd.product.inStock')}
                    </MetaLine>
                  )}
                  <View style={styles.qty}>
                    <Text style={textStyles.body}>{t('rv.product.qty')}</Text>
                    <View style={styles.qtyStepper}>
                      <QuantityStepper
                        value={qty}
                        onLess={() => setQty((n) => Math.max(1, n - 1))}
                        onMore={() => setQty((n) => Math.min(99, n + 1))}
                        canLess={qty > 1}
                        canMore={qty < Math.min(99, Math.max(1, stock))}
                        lessLabel={t('shop.product.less')}
                        moreLabel={t('shop.product.more')}
                        valueLabel={t('shop.product.qty', { qty })}
                        text={null}
                      />
                    </View>
                  </View>
                  <KitButton label={addLabel} icon="shopping-cart" size="lg" block busy={add.isPending} disabled={soldOut || needsVariant} onPress={addToCart} testID="product-add-box" />
                  <MetaLine icon="storefront">{t('rd.product.soldBy', { shop: offer.shopName })}</MetaLine>
                  {offer.returnsPolicy ? <MetaLine icon="arrow-u-up-left">{offer.returnsPolicy}</MetaLine> : null}
                  <MetaLine icon="shield-check">{t('rd.product.held')}</MetaLine>
                </View>
              </View>

              {offer.more.length > 0 ? (
                <View style={styles.section}>
                  <View style={styles.pad}>
                    <SectionHead title={t('shop.product.alsoFrom', { shop: offer.shopName })} />
                  </View>
                  <Strip edge>
                    {offer.more.slice(0, 8).map((m) => (
                      <View key={m.productId} style={styles.railCard}>
                      <ProductCard
                        title={m.name}
                        seller={offer.shopName}
                        priceCents={m.priceCents}
                        icon={iconFor('product')}
                        onPress={() => router.push({ pathname: '/product/[id]', params: { id: m.productId } })}
                        label={`${m.name}, ${f.money(m.priceCents)}`}
                      />
                      </View>
                    ))}
                  </Strip>
                </View>
              ) : null}
            </View>
          );
        }}
      </QueryView>
    </Screen>
  );
}

const RAIL = space[16] * 2 + space[10];

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  strong: { fontFamily: textStyles.section.fontFamily },
  skeleton: { gap: space[3], paddingHorizontal: GUTTER },
  pad: { paddingHorizontal: GUTTER, gap: space[2] },
  body: { gap: space[5], marginTop: -space[3] },
  gallery: { borderRadius: 0 },
  overlay: { position: 'absolute', top: space[3], left: GUTTER, right: GUTTER, flexDirection: 'row', justifyContent: 'space-between' },
  counter: { position: 'absolute', right: GUTTER, bottom: space[3], paddingHorizontal: space[2.5], paddingVertical: space[1], borderRadius: radius.round, backgroundColor: semantic.selected },
  counterText: { ...StyleSheet.flatten(textStyles.label), color: semantic['selected-text'] },
  visit: { flexDirection: 'row', alignItems: 'center', gap: space[1.5], minHeight: MIN_TARGET, alignSelf: 'flex-start' },
  name: textStyles.pageTitle,
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: space[1.5] },
  options: { gap: space[2], paddingTop: space[3], borderTopWidth: LINE.hair, borderTopColor: semantic.border, marginHorizontal: GUTTER, paddingHorizontal: 0 },
  about: { gap: space[2] },
  bullet: { flexDirection: 'row', gap: space[2], paddingLeft: space[1] },
  spec: { flexDirection: 'row', gap: space[3], paddingVertical: space[2.5], borderTopWidth: LINE.hair, borderTopColor: semantic.border },
  specKey: { width: space[16] + space[8], color: semantic['text-muted'] },
  box: { gap: space[3], padding: space[4], borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: semantic.surface },
  boxLine: { flexDirection: 'row', gap: space[3] },
  orderWithin: { paddingTop: space[1] },
  qty: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  qtyStepper: { minWidth: space[16] * 2 },
  section: { gap: space[2] },
  railCard: { width: RAIL },
  sticky: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  stickyText: { flex: 1, minWidth: 0 },
  stickyButton: { flex: 1.3 },
});
