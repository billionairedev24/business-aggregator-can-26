import { useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Share, StyleSheet, Text, View } from 'react-native';

import { ApiError, Icon, colors, fonts, space } from '@northline/mobile-kit';

import { Body, Button, Notice, Tag } from '../ui/primitives';
import { RoundButton, Screen } from '../ui/screen';
import { EmptyState, QueryView, Skeleton, errorMessage } from '../ui/states';
import { config } from '../config';
import { serverMessage, shop, useAddToCart, useMarket, useShopFormat } from './common';
import { Chip, InfoLine, Panel, ProductTile, SectionHead, Stepper, Strip, Thumb, shopStyles } from './parts';

/**
 * Product (redesign spec § 2.4 → MA:222 PRODUCT): the 300 pt picture, the shop, the name (22 / 700), the rating, the
 * price (28 / 800) and unit, the facts panel (tonight's run to your place, sold by, stock, returns), the description and
 * its bullets, the options, "More from {shop}" as a rail; the sticky bottom bar is the quantity pill and Add to cart
 * (MA:529). `GET /public/shop/products/{id}?market=` (S-50; the id may be an offer's, `?offer=` picks the seller); Add
 * is `POST /cart/items` (guests too) and opens the cart. Tier labels left the byline (thrash item 13).
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

  const addToCart = () => {
    if (!offer) return;
    add.mutate(
      { offerId: offer.offerId, ...(variant ? { variantId: variant.variantId } : {}), qty },
      { onSuccess: () => router.navigate('/cart') },
    );
  };
  const addError = add.error
    ? add.error instanceof ApiError && add.error.status === 422
      ? serverMessage(add.error.message, t)
      : errorMessage(add.error, t)
    : null;
  const share = () => {
    if (!product.data) return;
    void Share.share({ message: `${product.data.name} — ${config.siteOrigin}/products/${encodeURIComponent(product.data.productId)}` }).catch(() => undefined);
  };

  return (
    <Screen
      title={offer?.shopName ?? t('title.product')}
      testID="product"
      action={product.data ? <RoundButton icon="export" label={t('rd.product.share')} onPress={share} testID="product-share" /> : undefined}
      footer={
        offer && product.data?.served ? (
          <>
            {addError ? <Notice message={addError} /> : null}
            <View style={styles.buy}>
              <Stepper
                value={qty}
                onLess={() => setQty((n) => Math.max(1, n - 1))}
                onMore={() => setQty((n) => Math.min(99, n + 1))}
                canLess={qty > 1}
                canMore={qty < Math.min(99, Math.max(1, stock))}
                lessLabel={t('shop.product.less')}
                moreLabel={t('shop.product.more')}
                valueLabel={t('shop.product.qty', { qty })}
              />
              <Button
                label={stock <= 0 ? t('shop.product.soldOut') : t('shop.product.add', { qty, total: f.money(unitCents * qty) })}
                tone="accent"
                large
                busy={add.isPending}
                disabled={stock <= 0 || needsVariant}
                onPress={addToCart}
                style={styles.add}
                testID="product-add"
              />
            </View>
          </>
        ) : undefined
      }
    >
      <QueryView
        query={product}
        skeleton={
          <View testID="loading" accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.skeleton}>
            <Skeleton height={300} />
            <Skeleton height={28} width="70%" />
            <Skeleton height={16} width="50%" />
          </View>
        }
        isEmpty={(p) => !p || !p.served || p.offers.length === 0}
        empty={<EmptyState message={t('shop.product.notServed', { place: product.data?.market ?? market.label ?? '' })} action={t('shop.cart.browse')} onAction={() => router.navigate('/home')} />}
      >
        {(p) =>
          p && offer ? (
            <View style={styles.body}>
              <Thumb uri={variant?.images?.[0] ?? offer.images[0]} height={300} style={styles.picture} label={t('shop.product.image', { name: p.name })} />
              <View style={styles.titleBlock}>
                <Text style={styles.small}>{p.brand ?? offer.shopName}</Text>
                <Text accessibilityRole="header" style={styles.name}>
                  {p.name}
                </Text>
                {offer.ratingCount > 0 ? (
                  <View style={styles.rating} accessible accessibilityLabel={t('rd.product.rating', { rating: offer.rating.toFixed(1), count: offer.ratingCount })}>
                    <Icon name="star" filled size={14} color={colors.rating} />
                    <Text style={styles.ratingText}>{t('rd.product.ratingShort', { rating: offer.rating.toFixed(1), count: offer.ratingCount })}</Text>
                  </View>
                ) : null}
              </View>
              <View style={styles.priceRow}>
                <Text style={shopStyles.bigPrice}>{f.money(unitCents)}</Text>
                {p.unit ? <Text style={styles.small}>{p.unit}</Text> : null}
              </View>
              <View style={shopStyles.chips}>
                {tonight ? <Tag label={t('shop.tag.tonight')} tone="accent" /> : null}
                {offer.lowStock && stock > 0 ? <Tag label={t('shop.product.lowStock', { n: stock })} tone="accent2" /> : null}
                {offer.condition ? <Tag label={offer.condition} /> : null}
              </View>
              <Panel>
                {next ? (
                  <InfoLine icon="lightning" color={colors.link}>
                    {t('rd.product.run', { when: f.window(next, 'shop.when'), place: market.label ?? p.market })}
                  </InfoLine>
                ) : null}
                <InfoLine icon="storefront">{t('rd.product.soldBy', { shop: offer.shopName })}</InfoLine>
                <InfoLine icon="package">{stock > 0 ? t('rd.product.inStock') : t('shop.product.soldOut')}</InfoLine>
                {offer.returnsPolicy ? <InfoLine icon="arrow-u-up-left">{offer.returnsPolicy}</InfoLine> : null}
                <InfoLine icon="shield-check">{t('rd.product.held')}</InfoLine>
              </Panel>
              {p.description ? <Body style={styles.description}>{p.description}</Body> : null}
              {p.bullets.length ? (
                <View style={styles.bullets}>
                  {p.bullets.map((b) => (
                    <View key={b} style={styles.bullet}>
                      <Icon name="check" size={14} color={colors.textMuted} />
                      <Body tone="small" style={styles.flex}>
                        {b}
                      </Body>
                    </View>
                  ))}
                </View>
              ) : null}
              {offer.variants.length > 0 ? (
                <View style={shopStyles.section}>
                  <Text accessibilityRole="header" style={styles.h3}>
                    {t('shop.product.options')}
                  </Text>
                  <View style={shopStyles.chips} accessibilityRole="radiogroup">
                    {offer.variants.map((v) => (
                      <Chip key={v.variantId} role="radio" label={v.value} on={v.variantId === chosen} onPress={() => setVariantId(v.variantId)} />
                    ))}
                  </View>
                </View>
              ) : null}
              {offer.more.length > 0 ? (
                <View style={shopStyles.section}>
                  <SectionHead title={t('shop.product.alsoFrom', { shop: offer.shopName })} />
                  <Strip>
                    {offer.more.slice(0, 8).map((m) => (
                      <ProductTile
                        key={m.productId}
                        name={m.name}
                        price={f.money(m.priceCents)}
                        label={`${m.name}, ${f.money(m.priceCents)}`}
                        onPress={() => router.push({ pathname: '/product/[id]', params: { id: m.productId } })}
                      />
                    ))}
                  </Strip>
                </View>
              ) : null}
            </View>
          ) : null
        }
      </QueryView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  skeleton: { gap: space[3] },
  body: { gap: space[4] },
  picture: { borderRadius: 12 },
  titleBlock: { gap: 6 },
  name: { fontFamily: fonts.bold, fontSize: 22, lineHeight: 27, color: colors.text },
  small: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
  rating: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  ratingText: { fontFamily: fonts.regular, fontSize: 13, color: colors.text },
  priceRow: { flexDirection: 'row', alignItems: 'baseline', gap: space[2] },
  description: { lineHeight: 21 },
  bullets: { gap: 6 },
  bullet: { flexDirection: 'row', alignItems: 'flex-start', gap: 6 },
  h3: { fontFamily: fonts.bold, fontSize: 16, color: colors.text },
  buy: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  add: { flex: 1 },
});
