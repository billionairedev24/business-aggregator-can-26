import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { Icon, MIN_TARGET, colors, fonts, radius, semantic, space } from '@northline/mobile-kit';

import type { Cart as CartData, CartLine } from '../api/shop';
import { Body, Button, Card, Notice, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, errorMessage } from '../ui/states';
import { shop, useCart, useChangeCartLine, useMarket, useShopFormat } from './common';
import { Stepper, SumRow, Thumb, shopStyles } from './parts';

const blocked = (l: CartLine) => !l.available || l.qty > l.stock;

/**
 * Cart (a tab; light direction): "Cart" in the display face, one card per shop (the shop's name, then each line: an
 * 80 pt picture — the photo or the drawn scene — the name and option, the price, the quantity pill and Delete), the
 * sums on linen (subtotal, the delivery from the market's next pooled run — "from …", chosen at checkout — and tax at
 * checkout); the sticky bottom bar is "Checkout · total". `GET /cart` (guests too, by the guest id), `PATCH`/`DELETE /cart/items/{id}`. Empty: the cart icon, one
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
                  <Icon name="storefront" size={18} color={semantic['action-primary']} />
                  <Text style={styles.shopName} numberOfLines={1}>
                    {g.shopName}
                  </Text>
                </View>
                {g.items.map((it) => (
                  <View key={it.itemId} style={styles.line} testID={`cart-line-${it.itemId}`}>
                    <Thumb uri={it.imageUrl} size={80} subject={it.name} />
                    <View style={shopStyles.rowText}>
                      <Text style={styles.name} numberOfLines={2}>
                        {it.name}
                      </Text>
                      {it.option || it.unit ? <Text style={styles.small}>{[it.option, it.unit].filter(Boolean).join(' · ')}</Text> : null}
                      <Text style={styles.lineTotal}>{f.money(it.lineCents)}</Text>
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
                          <Text style={styles.deleteText}>{t('rd.cart.deleteShort')}</Text>
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
  shopHead: { flexDirection: 'row', alignItems: 'center', gap: space[2], paddingBottom: space[3], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  shopName: { flex: 1, fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  line: { flexDirection: 'row', gap: space[3] },
  name: { fontFamily: fonts.semibold, fontSize: 15, lineHeight: 20, color: colors.text },
  small: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
  lineTotal: { fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  warn: { fontFamily: fonts.medium, fontSize: 13, color: colors.danger },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space[3], paddingTop: space[1] },
  delete: { minHeight: MIN_TARGET, justifyContent: 'center' },
  deleteText: { fontFamily: fonts.semibold, fontSize: 14, color: colors.link, textDecorationLine: 'underline' },
  sums: { gap: space[2], padding: space[4], borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'] },
});
