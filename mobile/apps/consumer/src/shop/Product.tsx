import { useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { ApiError, colors, fonts, radius, space } from '@northline/mobile-kit';

import { Body, Button, Notice, Tag } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, QueryView, Skeleton, errorMessage } from '../ui/states';
import { serverMessage, shop, useAddToCart, useMarket, useShopFormat } from './common';
import { Chip, Kicker, Stepper, Thumb, shopStyles } from './parts';

/**
 * B3 Product (design 01 `product`): the picture, name, the seller with tier and rating, price and unit, the tags
 * ("On tonight's run", stock), the description, the options (variants), "Also from {shop}" (same pickup), and the
 * quantity + Add. `GET /public/shop/products/{id}?market=` (S-50; the id may be an offer's, `?offer=` picks the seller);
 * Add is `POST /cart/items` (guests too) and opens the cart, as the design does.
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
  // the design pre-selects the first option ("Whole")
  const chosen = variantId ?? offer?.variants[0]?.variantId;
  const variant = offer?.variants.find((v) => v.variantId === chosen);
  const unitCents = variant?.priceCents ?? offer?.priceCents ?? 0;
  const stock = variant?.stock ?? offer?.stock ?? 0;
  const needsVariant = !!offer && offer.variants.length > 0 && !variant;
  const tonight = offer?.runs.find((r) => r.day === 'today');

  const addToCart = () => {
    if (!offer) return;
    add.mutate(
      { offerId: offer.offerId, ...(variant ? { variantId: variant.variantId } : {}), qty },
      { onSuccess: () => router.push('/cart') },
    );
  };
  const addError = add.error
    ? add.error instanceof ApiError && add.error.status === 422
      ? serverMessage(add.error.message, t)
      : errorMessage(add.error, t)
    : null;

  return (
    <Screen
      title={offer?.shopName ?? t('title.product')}
      testID="product"
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
            <Skeleton height={240} />
            <Skeleton height={28} width="70%" />
            <Skeleton height={16} width="50%" />
          </View>
        }
        isEmpty={(p) => !p || !p.served || p.offers.length === 0}
        empty={<EmptyState message={t('shop.product.notServed', { place: product.data?.market ?? market.label ?? '' })} action={t('shop.cart.browse')} onAction={() => router.replace('/home')} />}
      >
        {(p) =>
          p && offer ? (
            <View style={styles.body}>
              <Thumb uri={variant?.images?.[0] ?? offer.images[0]} height={240} label={t('shop.product.image', { name: p.name })} />
              <View style={styles.titleRow}>
                <View style={shopStyles.rowText}>
                  <Text accessibilityRole="header" style={styles.name}>
                    {p.name}
                  </Text>
                  <Text style={styles.small}>
                    {t('shop.product.meta', { shop: offer.shopName, tier: f.tier(offer.tier), rating: offer.rating.toFixed(1), count: offer.ratingCount })}
                  </Text>
                </View>
                <View style={shopStyles.right}>
                  <Text style={shopStyles.bigPrice}>{f.money(unitCents)}</Text>
                  {p.unit ? <Text style={styles.small}>{p.unit}</Text> : null}
                </View>
              </View>
              <View style={shopStyles.chips}>
                {tonight ? <Tag label={t('shop.tag.tonight')} tone="accent" /> : null}
                {offer.lowStock && stock > 0 ? <Tag label={t('shop.product.lowStock', { n: stock })} tone="accent2" /> : null}
                {offer.condition ? <Tag label={offer.condition} /> : null}
              </View>
              {p.description ? <Body style={styles.description}>{p.description}</Body> : null}
              {p.bullets.map((b) => (
                <Body key={b} tone="small">
                  · {b}
                </Body>
              ))}
              {offer.variants.length > 0 ? (
                <View style={shopStyles.section}>
                  <Kicker>{t('shop.product.options')}</Kicker>
                  <View style={shopStyles.chips} accessibilityRole="radiogroup">
                    {offer.variants.map((v) => (
                      <Chip key={v.variantId} role="radio" label={v.value} on={v.variantId === chosen} onPress={() => setVariantId(v.variantId)} />
                    ))}
                  </View>
                </View>
              ) : null}
              {offer.more.length > 0 ? (
                <View style={shopStyles.section}>
                  <Kicker>{t('shop.product.alsoFrom', { shop: offer.shopName })}</Kicker>
                  <View style={styles.more}>
                    {offer.more.slice(0, 3).map((m) => (
                      <Pressable
                        key={m.productId}
                        accessibilityRole="button"
                        onPress={() => router.push({ pathname: '/product/[id]', params: { id: m.productId } })}
                        style={styles.moreItem}
                      >
                        <Text style={styles.moreText}>
                          {m.name} · {f.money(m.priceCents)}
                        </Text>
                      </Pressable>
                    ))}
                  </View>
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
  skeleton: { gap: space[3] },
  body: { gap: space[3] },
  titleRow: { flexDirection: 'row', alignItems: 'flex-start', gap: space[3] },
  name: { fontFamily: fonts.heading, fontSize: 24, lineHeight: 28, letterSpacing: -0.4, color: colors.text },
  small: { fontFamily: fonts.body, fontSize: 13, color: colors.neutral700 },
  description: { lineHeight: 22 },
  more: { flexDirection: 'row', gap: 10 },
  moreItem: { flex: 1, minHeight: 48, padding: 10, borderRadius: radius.md, backgroundColor: colors.surface, justifyContent: 'center' },
  moreText: { fontFamily: fonts.body, fontSize: 13, color: colors.text },
  buy: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  add: { flex: 1 },
});
