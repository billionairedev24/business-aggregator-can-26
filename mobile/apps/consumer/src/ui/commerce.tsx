import { router } from 'expo-router';
import { StyleSheet, View, type ImageSourcePropType, type StyleProp, type ViewStyle } from 'react-native';

import { Photo, PhotoWell, ProductCard as KitProductCard, photoFor, radius, semantic, unitCents, type Crop, type IconName, type PriceUnit } from '@northline/mobile-kit';

import type { ProductCard, Run } from '../api/shop';
import { config } from '../config';
import { useI18n } from '../i18n';
import { useAddToCart, useCart, useChangeCartLine, useShopFormat } from '../shop/common';

/**
 * S-178: the consumer's commerce pieces on the kit (design/revamp/a.html): a listing's photo as the kit's PhotoWell
 * source, the unit price from the listing's size ("900 g" → $0.94/100 g), "Get it Tonight, 6–9 p.m." and the product
 * card wired to the cart (Add to cart, then the − n + stepper).
 */

/** A photo url the api names (absolute, or a path on the api's origin), else null. */
export const photoUri = (uri?: string | null) => (uri ? (/^https?:\/\//.test(uri) ? uri : uri.startsWith('/') ? `${new URL(config.apiUrl).origin}${uri}` : null) : null);

/**
 * A search result's picture (`imageKey`): the catalogue's `media:<id>` → its public url on the api
 * (`/public/catalogue/media/{id}`, as the web's search), or an absolute url as it is (the fixture backend's photos).
 */
export const searchImage = (key?: string | null) =>
  key?.startsWith('media:') ? photoUri(`${new URL(config.apiUrl).pathname.replace(/\/+$/, '')}/public/catalogue/media/${encodeURIComponent(key.slice('media:'.length))}`) : photoUri(key);

/** The PhotoWell source of a listing's photo, or null (the dashed fallback with the category icon). */
export function photoSource(uri?: string | null): ImageSourcePropType | null {
  const u = photoUri(uri);
  return u ? { uri: u } : null;
}

const SIZE = /^(\d+(?:[.,]\d+)?)\s*(g|kg|ml|l)\b/i;

/**
 * The unit price of a pack from the listing's size words: "900 g loaf" → per 100 g, "1 kg" → per kg, "750 mL" → per
 * 100 mL, "2 L" → per L, "dozen" → each of 12. Anything else (each, bunch) has no unit price.
 */
export function unitOf(priceCents: number, unit?: string | null): { cents: number; per: PriceUnit } | undefined {
  if (!unit) return undefined;
  if (/\bdozen\b|\bdouzaine\b/i.test(unit)) return { cents: unitCents(priceCents, 12, 'each'), per: 'each' };
  const m = SIZE.exec(unit.trim());
  if (!m) return undefined;
  const n = Number(m[1]!.replace(',', '.'));
  const u = m[2]!.toLowerCase();
  if (!(n > 0)) return undefined;
  if (u === 'g') return { cents: unitCents(priceCents, n, '100g'), per: '100g' };
  if (u === 'kg') return { cents: unitCents(priceCents, n * 1000, 'kg'), per: 'kg' };
  if (u === 'ml') return { cents: unitCents(priceCents, n, '100ml'), per: '100ml' };
  return { cents: unitCents(priceCents, n * 1000, 'l'), per: 'l' };
}

/** "Tonight, 6–9 p.m." / "Tomorrow, 8–11 a.m." for a run (the market's zone). */
export function useWhen() {
  const f = useShopFormat();
  return (run: Pick<Run, 'day' | 'startsAt' | 'endsAt'> | null | undefined) => (run ? f.window(run, 'rv.when') : null);
}

/**
 * A stock photo of the library (a banner, a category, a hero) in the grey well, cover; when no slot has one, the
 * designed fallback — the dashed well with the icon (S-181/S-178: never the retired drawings). `style` sizes it.
 */
export function Picture({ slot, crop = 'card', icon = 'image', label, style, testID }: { slot: string | readonly (string | null | undefined)[]; crop?: Crop; icon?: IconName; label?: string; style?: StyleProp<ViewStyle>; testID?: string }) {
  const photo = photoFor(slot, crop);
  return (
    <View style={[styles.picture, style]} testID={testID}>
      {photo ? <Photo slot={[]} photo={photo} crop={crop} label={label} style={StyleSheet.absoluteFill} /> : <PhotoWell icon={icon} inset={0} label={label} style={StyleSheet.absoluteFill} />}
    </View>
  );
}

/** The icon of the dashed no-photo well, by what the listing is. */
export function iconFor(kind: 'product' | 'service' | 'shop' | 'food'): IconName {
  return kind === 'service' ? 'wrench' : kind === 'food' ? 'basket' : kind === 'shop' ? 'storefront' : 'shopping-bag';
}

/**
 * A product as the results, rails and "more from" show it (the kit's ProductCard): the photo well, two lines of name,
 * the shop, the price and its unit price, "Get it Tonight, 6–9 p.m.", then Add to cart — or, once it is in the cart,
 * the − n + stepper on that cart line.
 */
export function ShopProductCard({ p, eta: etaText, low, testID }: { p: ProductCard; eta?: string | null; low?: string | null; testID?: string }) {
  const { t } = useI18n();
  const when = useWhen();
  const cart = useCart();
  const add = useAddToCart();
  const change = useChangeCartLine();
  const line = cart.data?.groups.flatMap((g) => g.items).find((l) => l.offerId === p.offerId && !l.variantId);
  const eta = etaText !== undefined ? etaText : when(p.run);
  const f = useShopFormat();
  return (
    <KitProductCard
      title={p.name}
      seller={p.shopName}
      priceCents={p.priceCents}
      unit={unitOf(p.priceCents, p.unit)}
      eta={eta ? (etaText !== undefined ? eta : t('rv.card.eta', { when: eta })) : undefined}
      low={low ?? undefined}
      label={[p.name, p.shopName, f.money(p.priceCents), eta && etaText === undefined ? t('rv.card.eta', { when: eta }) : eta, low].filter(Boolean).join(', ')}
      source={photoSource(p.imageUrl)}
      icon={iconFor('product')}
      onPress={() => router.push({ pathname: '/product/[id]', params: { id: p.productId, offer: p.offerId } })}
      addLabel={t('rv.card.add')}
      onAdd={() => (line ? change.mutate({ itemId: line.itemId, qty: line.qty + 1 }) : add.mutate({ offerId: p.offerId, qty: 1 }))}
      inCart={line?.qty ?? 0}
      onLess={line ? () => change.mutate({ itemId: line.itemId, qty: line.qty - 1 }) : undefined}
      testID={testID ?? `product-card-${p.offerId}`}
    />
  );
}

const styles = StyleSheet.create({ picture: { overflow: 'hidden', borderRadius: radius.image, backgroundColor: semantic.well } });
