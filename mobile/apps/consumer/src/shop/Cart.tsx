import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { colors, fonts, space } from '@northline/mobile-kit';

import type { Cart as CartData, CartLine } from '../api/shop';
import { Body, Button, Notice, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, errorMessage } from '../ui/states';
import { shop, useCart, useChangeCartLine, useMarket, useShopFormat } from './common';
import { Stepper, SumRow, Thumb, shopStyles } from './parts';

const blocked = (l: CartLine) => !l.available || l.qty > l.stock;

/**
 * B4 Cart (design 01 `cart`, a tab): "{n} items · {n} shops · one delivery", the lines grouped by shop with − n +,
 * the items' total, the delivery fee from the market's next pooled run ("from …", chosen at checkout), tax at checkout
 * and "Checkout · {total}". `GET /cart` (guests too, by the guest id), `PATCH`/`DELETE /cart/items/{id}`. The design's
 * promo / points code and points line have no api in checkout (S-51) and aren't shown.
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

  return (
    <Screen testID="cart" refreshControl={<RefreshControl refreshing={cart.isRefetching} onRefresh={() => void cart.refetch()} />}>
      <Title>{t('title.cart')}</Title>
      <QueryView
        query={cart}
        skeleton={<LoadingList rows={4} height={56} />}
        isEmpty={(c) => c.itemCount === 0}
        empty={<EmptyState message={t('shop.cart.empty')} action={t('shop.cart.browse')} onAction={() => router.push('/search')} />}
      >
        {(c: CartData) => {
          const stuck = c.groups.some((g) => g.items.some(blocked));
          return (
            <View style={styles.body}>
              <Body tone="small">{t('shop.cart.summary', { items: c.itemCount, shops: c.shopCount })}</Body>
              {c.groups.map((g) => (
                <View key={g.merchantId} style={shopStyles.section}>
                  <Text accessibilityRole="header" style={[styles.body15, shopStyles.strong]}>
                    {g.shopName}
                  </Text>
                  {g.items.map((it) => (
                    <View key={it.itemId} style={styles.line} testID={`cart-line-${it.itemId}`}>
                      <View style={shopStyles.row}>
                        <Thumb uri={it.imageUrl} size={44} />
                        <View style={shopStyles.rowText}>
                          <Text style={styles.body15}>{it.name}</Text>
                          {it.option || it.unit ? <Text style={styles.small}>{[it.option, it.unit].filter(Boolean).join(' · ')}</Text> : null}
                        </View>
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
                        <Text style={styles.lineTotal}>{f.money(it.lineCents)}</Text>
                      </View>
                      {!it.available ? (
                        <Text accessibilityRole="alert" style={styles.warn}>
                          {t('shop.cart.unavailable')}
                        </Text>
                      ) : it.qty > it.stock ? (
                        <Text accessibilityRole="alert" style={styles.warn}>
                          {t('shop.cart.notEnough', { n: it.stock })}
                        </Text>
                      ) : null}
                    </View>
                  ))}
                </View>
              ))}
              {change.error ? <Notice message={errorMessage(change.error, t)} /> : null}
              <View style={styles.sums}>
                <SumRow label={t('shop.sum.items')} value={f.money(c.subtotalCents)} />
                {runFee !== undefined ? <SumRow label={t('shop.cart.deliveryNext')} value={t('shop.cart.from', { fee: f.fee(runFee) })} /> : null}
                <SumRow label={t('shop.cart.taxNext')} value={t('shop.cart.taxLater')} />
              </View>
              {stuck ? <Body tone="small">{t('shop.cart.fixFirst')}</Body> : null}
              <Button
                label={t('shop.cart.checkout', { total: f.money(c.subtotalCents) })}
                large
                disabled={stuck || change.isPending}
                onPress={() => router.push('/checkout')}
                testID="cart-checkout"
              />
            </View>
          );
        }}
      </QueryView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  body: { gap: space[2] },
  line: { gap: 2 },
  body15: { fontFamily: fonts.body, fontSize: 15, color: colors.text },
  small: { fontFamily: fonts.body, fontSize: 12, color: colors.neutral700 },
  lineTotal: { minWidth: 60, textAlign: 'right', fontFamily: fonts.body, fontSize: 15, color: colors.text },
  warn: { fontFamily: fonts.body, fontSize: 13, color: colors.accent2_700 },
  sums: { gap: 6, paddingTop: space[4], paddingBottom: space[3] },
});
