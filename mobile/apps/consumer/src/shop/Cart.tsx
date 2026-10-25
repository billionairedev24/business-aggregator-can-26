import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { Icon, LINE, MIN_TARGET, PhotoWell, SIZE, TYPE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import type { Cart as CartData, CartLine } from '../api/shop';
import { Body, Button, Card, Notice, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, errorMessage } from '../ui/states';
import { shop, useCart, useChangeCartLine, useMarket, useShopFormat } from './common';
import { iconFor, photoSource } from '../ui/commerce';
import { Stepper, SumRow } from './parts';

const blocked = (l: CartLine) => !l.available || l.qty > l.stock;

/**
 * Cart (a tab; E-15 Plainsight): "Cart", one panel per shop (the shop's name, then each line: the photo in the grey
 * well — the dashed well with the category icon when there is none — the name and option, the price, the − n +
 * stepper and Delete), the sums on the well grey (subtotal, the delivery from the market's next pooled run — "from
 * …", chosen at checkout — and tax at checkout); the sticky bottom bar is "Checkout · total". `GET /cart` (guests too, by the guest id), `PATCH`/`DELETE /cart/items/{id}`. Empty: the cart icon, one
 * bold line, Start shopping. Payment left the cart (thrash item 8): it's the checkout's step 2.
 */
export function Cart() {
  const f = useShopFormat();
  const { t, locale } = f;
  const cart = useCart();
  const change = useChangeCartLine();
  const market = useMarket();
  // the fee of the next pooled run (Home already fetched it)
  const landing = useQuery({
    queryKey: ['shop', 'landing', market.city?.toLowerCase() ?? '', locale],
    queryFn: () => shop().landing(market.city, locale),
    enabled: market.ready,
    staleTime: 60_000,
  });
  const runFee = landing.data?.run?.feeCents;
  const c = cart.data;
  const stuck = !!c && c.groups.some((g) => g.items.some(blocked));

  return (
    <Screen
      testID="cart"
      refreshControl={<RefreshControl refreshing={cart.isRefetching} onRefresh={() => void cart.refetch()} />}
      footer={
        c && c.itemCount > 0 ? (
          <>
            {stuck ? <Body tone="small">{t('shop.cart.fixFirst')}</Body> : null}
            <Button
              label={t('shop.cart.checkout', { total: f.money(c.subtotalCents) })}
              large
              disabled={stuck || change.isPending}
              onPress={() => router.push('/checkout')}
              testID="cart-checkout"
            />
          </>
        ) : undefined
      }
    >
      <Title>{t('title.cart')}</Title>
      <QueryView
        query={cart}
        skeleton={<LoadingList rows={4} height={80} />}
        isEmpty={(d) => d.itemCount === 0}
        empty={
          <EmptyState art="grocery" title={t('shop.cart.empty')} message="" action={t('shop.cart.browse')} onAction={() => router.push('/search')} />
        }
      >
        {(d: CartData) => (
          <View style={styles.body}>
            <Body tone="small">{t('shop.cart.summary', { items: d.itemCount, shops: d.shopCount })}</Body>
            {d.groups.map((g) => (
              <Card key={g.merchantId} style={styles.group}>
                <View style={styles.shopHead}>
                  <Icon name="storefront" size={TYPE.price} color={semantic.text} />
                  <Text style={styles.shopName} numberOfLines={1}>
                    {g.shopName}
                  </Text>
                </View>
                {g.items.map((it) => (
                  <View key={it.itemId} style={styles.line} testID={`cart-line-${it.itemId}`}>
                    <View style={styles.thumb}>
                      <PhotoWell source={photoSource(it.imageUrl)} icon={iconFor('product')} inset={space[1.5]} />
                    </View>
                    <View style={styles.rowText}>
                      <Text style={textStyles.bodyStrong} numberOfLines={2}>
                        {it.name}
                      </Text>
                      {it.option || it.unit ? <Text style={textStyles.meta}>{[it.option, it.unit].filter(Boolean).join(' · ')}</Text> : null}
                      <Text style={textStyles.priceSm}>{f.money(it.lineCents)}</Text>
                      {!it.available ? (
                        <Text accessibilityRole="alert" style={styles.warn}>
                          {t('shop.cart.unavailable')}
                        </Text>
                      ) : it.qty > it.stock ? (
                        <Text accessibilityRole="alert" style={styles.warn}>
                          {t('shop.cart.notEnough', { n: it.stock })}
                        </Text>
                      ) : null}
                      <View style={styles.actions}>
                        <Stepper
                          compact
                          value={it.qty}
                          onLess={() => change.mutate({ itemId: it.itemId, qty: it.qty - 1 })}
                          onMore={() => change.mutate({ itemId: it.itemId, qty: it.qty + 1 })}
                          canLess={!change.isPending}
                          canMore={!change.isPending && it.available && it.qty < Math.min(99, it.stock)}
                          lessLabel={it.qty === 1 ? t('shop.cart.remove', { name: it.name }) : t('shop.cart.dec', { name: it.name })}
                          moreLabel={t('shop.cart.inc', { name: it.name })}
                          valueLabel={t('shop.cart.qty', { qty: it.qty, name: it.name })}
                        />
                        <Pressable
                          accessibilityRole="button"
                          accessibilityLabel={t('rd.cart.delete', { name: it.name })}
                          disabled={change.isPending}
                          onPress={() => change.mutate({ itemId: it.itemId, qty: 0 })}
                          style={styles.delete}
                          testID={`cart-delete-${it.itemId}`}
                        >
                          <Text style={[textStyles.label, textStyles.link]}>{t('rd.cart.deleteShort')}</Text>
                        </Pressable>
                      </View>
                    </View>
                  </View>
                ))}
              </Card>
            ))}
            {change.error ? <Notice message={errorMessage(change.error, t)} /> : null}
            <View style={styles.sums} testID="cart-sums">
              <SumRow label={t('rd.cart.subtotal', { n: d.itemCount })} value={f.money(d.subtotalCents)} strong />
              {runFee !== undefined ? <SumRow label={t('shop.cart.deliveryNext')} value={t('shop.cart.from', { fee: f.fee(runFee) })} /> : null}
              <SumRow label={t('shop.cart.taxNext')} value={t('shop.cart.taxLater')} />
            </View>
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: { gap: space[4] },
  group: { gap: space[4] },
  shopHead: { flexDirection: 'row', alignItems: 'center', gap: space[2], paddingBottom: space[3], borderBottomWidth: LINE.hair, borderBottomColor: semantic.border },
  shopName: { ...StyleSheet.flatten(textStyles.sub), flex: 1 },
  line: { flexDirection: 'row', gap: space[3] },
  thumb: { width: SIZE.rail + space[2] },
  rowText: { flex: 1, minWidth: 0, gap: LINE.bold },
  warn: { ...StyleSheet.flatten(textStyles.label), color: semantic.danger },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space[3], paddingTop: space[1] },
  delete: { minHeight: MIN_TARGET, justifyContent: 'center' },
  sums: { gap: space[2], padding: space[4], borderRadius: radius.panel, backgroundColor: semantic.well },
});
