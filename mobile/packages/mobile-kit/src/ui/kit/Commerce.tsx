import type { ReactNode } from 'react';
import { Image, Pressable, StyleSheet, Text, View, type ImageSourcePropType, type StyleProp, type ViewStyle } from 'react-native';

import { MIN_TARGET, radius, semantic, space } from '../../theme';
import { Icon, type IconName } from '../Icon';
import { Button } from './Button';
import { formatPrice, formatUnitPrice, type PriceUnit } from './format';
import { useKit } from './KitProvider';
import { CAP, DENSITY, LINE, SIZE, TYPE, slopFor, textStyles } from './tokens';

/**
 * Commerce pieces (Plainsight): product on white in a 1:1 grey well with a 12 pt inset, the dashed well with the
 * category icon when there is no photo; one price format; ink stars; the magenta deal badge and cart count (the
 * commerce colour is never a button); status tags with a square dot, one colour per status.
 */

/** The photo well: 1:1, the well grey, the photo inset and contained (product on white); no photo = the dashed fallback. */
export function PhotoWell({
  source,
  photo,
  icon = 'image',
  caption,
  label,
  badge,
  inset = DENSITY['well-inset'],
  style,
  testID,
}: {
  /** An image to draw (a url as `{ uri }`, or a bundled asset). */
  source?: ImageSourcePropType | null;
  /** A ready picture (the kit's `Photo`, a stock photo) instead of `source`. */
  photo?: ReactNode;
  /** The category's icon in the fallback. */
  icon?: IconName;
  /** Under the icon in the fallback (the shop's name). */
  caption?: string;
  /** Read out for the picture; omitted, it is decorative (the card's title names the thing). */
  label?: string;
  /** Over the top-left corner (a {@link DealBadge}). */
  badge?: ReactNode;
  inset?: number;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}) {
  const has = !!source || !!photo;
  const a11y = label ? ({ accessible: true, accessibilityRole: 'image', accessibilityLabel: label } as const) : ({ accessible: false } as const);
  return (
    <View style={[styles.well, { padding: inset }, style]} testID={testID ?? (has ? 'photo-well' : 'photo-fallback')} {...a11y}>
      {source ? (
        <Image source={source} style={styles.wellImage} resizeMode="contain" />
      ) : photo ? (
        <View style={styles.wellImage}>{photo}</View>
      ) : (
        <View style={styles.fallback}>
          <Icon name={icon} size={TYPE['price-lg'] + space[1]} color={semantic['well-ink']} />
          {caption ? (
            <Text style={styles.fallbackCap} numberOfLines={2}>
              {caption}
            </Text>
          ) : null}
        </View>
      )}
      {badge ? <View style={styles.badgeSpot}>{badge}</View> : null}
    </View>
  );
}

/**
 * A price in the one format (`$8.50` / `8,50 $`), with the unit price as meta (`$0.94/100 g`) and the earlier price
 * struck through. `from` for a service's starting price.
 */
export function Price({ cents, unit, was, from, size = 'md', per, testID }: { cents: number; unit?: { cents: number; per: PriceUnit }; was?: number; from?: boolean; size?: 'sm' | 'md' | 'lg'; per?: string; testID?: string }) {
  const { locale, t } = useKit();
  const amount = formatPrice(cents, locale);
  const role = size === 'sm' ? textStyles.priceSm : size === 'lg' ? textStyles.priceLg : textStyles.price;
  const read = [from ? t('kit.from', { price: amount }) : amount, per, unit ? formatUnitPrice(unit.cents, unit.per, locale) : null, was !== undefined ? t('kit.wasPrice', { price: formatPrice(was, locale) }) : null].filter(Boolean).join(', ');
  return (
    <View style={styles.price} accessible accessibilityLabel={read} testID={testID}>
      {from ? <Text style={textStyles.meta}>{t('kit.from', { price: '' }).trim()}</Text> : null}
      <Text style={role}>
        {amount}
        {per ? <Text style={textStyles.meta}>{per}</Text> : null}
      </Text>
      {unit ? <Text style={textStyles.meta}>{formatUnitPrice(unit.cents, unit.per, locale)}</Text> : null}
      {was !== undefined ? <Text style={[textStyles.meta, styles.was]}>{formatPrice(was, locale)}</Text> : null}
    </View>
  );
}

/** Five ink stars (decorative: {@link Rating} carries the words). */
export function Stars({ value, size = TYPE.label }: { value: number; size?: number }) {
  const full = Math.round(value);
  return (
    <View style={styles.stars} accessible={false} importantForAccessibility="no-hide-descendants">
      {[1, 2, 3, 4, 5].map((i) => (
        <Icon key={i} name="star" filled size={size} color={i <= full ? semantic.star : semantic['star-empty']} />
      ))}
    </View>
  );
}

/** "4.8 ★★★★★ (312)", read as "4.8 out of 5 stars, 312 reviews". */
export function Rating({ value, count, testID }: { value: number; count?: number; testID?: string }) {
  const { locale, t } = useKit();
  const shown = new Intl.NumberFormat(locale === 'fr-CA' ? 'fr-CA' : 'en-CA', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(value);
  return (
    <View style={styles.rating} accessible accessibilityLabel={count !== undefined ? t('kit.rating', { rating: shown, n: count }) : t('kit.ratingOnly', { rating: shown })} testID={testID}>
      <Text style={textStyles.metaStrong}>{shown}</Text>
      <Stars value={value} />
      {count !== undefined ? <Text style={textStyles.meta}>{t('kit.reviews', { n: count })}</Text> : null}
    </View>
  );
}

/** The deal badge: commerce magenta, 2 pt corners ("12% off"). */
export function DealBadge({ label, testID }: { label: string; testID?: string }) {
  return (
    <View style={styles.deal} testID={testID}>
      <Text style={styles.dealText} maxFontSizeMultiplier={CAP.badge}>
        {label}
      </Text>
    </View>
  );
}

export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

/** A status tag: the status's tint, its colour for the words and a square dot (Delivered · Packed · To pack · Wrong item · Paused). */
export function StatusTag({ label, tone = 'neutral', large, testID }: { label: string; tone?: StatusTone; large?: boolean; testID?: string }) {
  const fg = semantic[`status-${tone}-fg`];
  return (
    <View style={[styles.tag, large && styles.tagLarge, { backgroundColor: semantic[`status-${tone}-bg`] }]} testID={testID}>
      <View style={[styles.tagDot, { backgroundColor: fg }]} />
      <Text style={[styles.tagText, large && styles.tagTextLarge, { color: fg }]} maxFontSizeMultiplier={CAP.badge}>
        {label}
      </Text>
    </View>
  );
}

/** A round count (the cart's): commerce magenta, `radius.round` — a circle stays a circle. */
export function CountBadge({ count, max = 99, testID }: { count: number; max?: number; testID?: string }) {
  if (count <= 0) return null;
  return (
    <View style={styles.countBadge} testID={testID} accessible={false}>
      <Text style={styles.countBadgeText} maxFontSizeMultiplier={CAP.badge}>
        {count > max ? `${max}+` : count}
      </Text>
    </View>
  );
}

/**
 * − n + : the ink edge on the selected tint (what's in the cart is a choice made), 32 pt drawn with 44 pt targets
 * (`size="lg"`: 46 pt, a bottom bar). The middle reads "1 in cart" unless `valueLabel` says otherwise.
 */
export function QuantityStepper({
  value,
  onLess,
  onMore,
  canLess = true,
  canMore = true,
  lessLabel,
  moreLabel,
  valueLabel,
  text,
  size = 'sm',
  testID,
}: {
  value: number;
  onLess: () => void;
  onMore: () => void;
  canLess?: boolean;
  canMore?: boolean;
  lessLabel?: string;
  moreLabel?: string;
  valueLabel?: string;
  /** What the middle shows (default "1 in cart"); `null` = the bare number. */
  text?: string | null;
  size?: 'sm' | 'lg';
  testID?: string;
}) {
  const { t } = useKit();
  const h = size === 'lg' ? SIZE['control-lg'] : SIZE['control-sm'];
  // the buttons sit inside the 2 pt ink edge: their own height plus the slop makes the 44 pt target
  const inner = h - 2 * LINE.bold;
  const shown = text === null ? String(value) : (text ?? t('kit.inCart', { n: value }));
  return (
    <View style={styles.stepper} testID={testID}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={lessLabel ?? t('kit.less')}
        accessibilityState={{ disabled: !canLess }}
        disabled={!canLess}
        onPress={onLess}
        hitSlop={slopFor(inner)}
        style={({ pressed }) => [styles.stepBtn, { minHeight: inner }, pressed && styles.stepPressed]}
      >
        <Icon name="minus" size={TYPE.sub} color={canLess ? semantic['selected-ink'] : semantic['text-subtle']} />
      </Pressable>
      <Text style={styles.stepValue} accessibilityLabel={valueLabel ?? t('kit.inCart', { n: value })} accessibilityLiveRegion="polite" maxFontSizeMultiplier={CAP.control}>
        {shown}
      </Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={moreLabel ?? t('kit.more')}
        accessibilityState={{ disabled: !canMore }}
        disabled={!canMore}
        onPress={onMore}
        hitSlop={slopFor(inner)}
        style={({ pressed }) => [styles.stepBtn, { minHeight: inner }, pressed && styles.stepPressed]}
      >
        <Icon name="plus" size={TYPE.sub} color={canMore ? semantic['selected-ink'] : semantic['text-subtle']} />
      </Pressable>
    </View>
  );
}

/** A meta line with an icon ("Get it Tonight, 6–9 p.m.", "Only 4 left"): `tone` warning for low stock. */
export function MetaLine({ icon, children, tone, testID }: { icon: IconName; children: ReactNode; tone?: 'warning' | 'success'; testID?: string }) {
  const color = tone === 'warning' ? semantic['status-warning-fg'] : tone === 'success' ? semantic['status-success-fg'] : semantic['text-muted'];
  return (
    <View style={styles.meta} testID={testID}>
      <Icon name={icon} size={TYPE.body} color={color} />
      <Text style={[textStyles.meta, tone && { color, fontFamily: textStyles.metaStrong.fontFamily }, styles.metaText]}>{children}</Text>
    </View>
  );
}

export interface ProductCardProps {
  title: string;
  seller?: string;
  priceCents: number;
  unit?: { cents: number; per: PriceUnit };
  wasCents?: number;
  rating?: { value: number; count?: number };
  /** "Get it Tonight, 6–9 p.m." */
  eta?: string;
  /** "Only 4 left" (warning). */
  low?: string;
  deal?: string;
  source?: ImageSourcePropType | null;
  photo?: ReactNode;
  icon?: IconName;
  onPress?: () => void;
  /** Add to cart; with `inCart` > 0 the stepper takes its place. */
  addLabel?: string;
  onAdd?: () => void;
  inCart?: number;
  onLess?: () => void;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}

/** A product: the photo well (or the dashed fallback with the category icon and the shop), two lines of title, the shop, rating, price + unit, delivery, then Add to cart or the stepper. */
export function ProductCard({ title, seller, priceCents, unit, wasCents, rating, eta, low, deal, source, photo, icon, onPress, addLabel, onAdd, inCart = 0, onLess, style, testID }: ProductCardProps) {
  const body = (
    <>
      <PhotoWell source={source} photo={photo} icon={icon} caption={seller} badge={deal ? <DealBadge label={deal} /> : null} />
      <View style={styles.cardBody}>
        <Text style={textStyles.cardTitle} numberOfLines={2}>
          {title}
        </Text>
        {seller ? (
          <Text style={textStyles.meta} numberOfLines={1}>
            {seller}
          </Text>
        ) : null}
        {rating ? <Rating value={rating.value} count={rating.count} /> : null}
        <Price cents={priceCents} unit={unit} was={wasCents} />
        {eta ? <MetaLine icon="truck" tone="success">{eta}</MetaLine> : null}
        {low ? <MetaLine icon="warning" tone="warning">{low}</MetaLine> : null}
      </View>
    </>
  );
  return (
    <View style={[styles.card, style]} testID={testID}>
      {onPress ? (
        <Pressable accessibilityRole="button" accessibilityLabel={title} onPress={onPress} style={({ pressed }) => [styles.cardPress, pressed && styles.cardPressed]}>
          {body}
        </Pressable>
      ) : (
        body
      )}
      {onAdd ? (
        <View style={styles.cardAction}>
          {inCart > 0 && onLess ? <QuantityStepper value={inCart} onLess={onLess} onMore={onAdd} /> : <Button label={addLabel ?? ''} size="sm" block onPress={onAdd} />}
        </View>
      ) : null}
    </View>
  );
}

/** A service provider or service: the photo well, the name, what they do, rating, badges, "from $79.00". */
export function ServiceCard({
  title,
  meta,
  fromCents,
  per,
  rating,
  badges,
  source,
  photo,
  icon,
  onPress,
  style,
  testID,
}: {
  title: string;
  meta?: string;
  fromCents?: number;
  per?: string;
  rating?: { value: number; count?: number };
  badges?: ReactNode;
  source?: ImageSourcePropType | null;
  photo?: ReactNode;
  icon?: IconName;
  onPress?: () => void;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}) {
  const body = (
    <>
      <PhotoWell source={source} photo={photo} icon={icon ?? 'wrench'} caption={title} inset={0} />
      <View style={styles.cardBody}>
        <Text style={textStyles.cardTitle} numberOfLines={2}>
          {title}
        </Text>
        {meta ? (
          <Text style={textStyles.meta} numberOfLines={2}>
            {meta}
          </Text>
        ) : null}
        {rating ? <Rating value={rating.value} count={rating.count} /> : null}
        {badges ? <View style={styles.badges}>{badges}</View> : null}
        {fromCents !== undefined ? <Price cents={fromCents} from per={per} size="sm" /> : null}
      </View>
    </>
  );
  return (
    <View style={[styles.card, style]} testID={testID}>
      {onPress ? (
        <Pressable accessibilityRole="button" accessibilityLabel={title} onPress={onPress} style={({ pressed }) => [styles.cardPress, pressed && styles.cardPressed]}>
          {body}
        </Pressable>
      ) : (
        body
      )}
    </View>
  );
}

const DOT = space[2] - LINE.hair;

const styles = StyleSheet.create({
  well: { width: '100%', aspectRatio: 1, backgroundColor: semantic.well, borderRadius: radius.image, overflow: 'hidden' },
  wellImage: { flex: 1, width: '100%', borderRadius: radius.image, overflow: 'hidden' },
  fallback: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space[1] + LINE.bold,
    padding: space[2],
    borderWidth: LINE.hair,
    borderStyle: 'dashed',
    borderColor: semantic['well-line'],
    borderRadius: radius.image,
  },
  fallbackCap: { ...StyleSheet.flatten(textStyles.metaStrong), color: semantic['well-ink'], textAlign: 'center' },
  badgeSpot: { position: 'absolute', left: space[2], top: space[2] },
  price: { flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap', columnGap: space[1] + LINE.bold },
  was: { textDecorationLine: 'line-through' },
  stars: { flexDirection: 'row', gap: LINE.hair },
  rating: { flexDirection: 'row', alignItems: 'center', gap: space[1] },
  deal: { alignSelf: 'flex-start', paddingHorizontal: space[2] - LINE.hair, paddingVertical: LINE.bold, borderRadius: radius.status, backgroundColor: semantic.commerce },
  dealText: { ...StyleSheet.flatten(textStyles.caps), textTransform: 'none', letterSpacing: 0, color: semantic['commerce-on'] },
  tag: { flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', gap: space[1] + LINE.hair, minHeight: space[6], paddingHorizontal: space[2], borderRadius: radius.status },
  tagLarge: { minHeight: space[6] + space[1], paddingHorizontal: space[2] + LINE.bold },
  tagDot: { width: DOT, height: DOT, borderRadius: LINE.hair },
  tagText: { ...StyleSheet.flatten(textStyles.metaStrong), fontFamily: textStyles.section.fontFamily },
  tagTextLarge: { fontSize: TYPE.label },
  countBadge: {
    minWidth: space[5] - LINE.bold,
    height: space[5] - LINE.bold,
    paddingHorizontal: space[1],
    borderRadius: radius.round,
    backgroundColor: semantic['cart-badge'],
    alignItems: 'center',
    justifyContent: 'center',
  },
  countBadgeText: { ...StyleSheet.flatten(textStyles.caps), textTransform: 'none', letterSpacing: 0, lineHeight: undefined, color: semantic['cart-badge-text'] },
  stepper: {
    flexDirection: 'row',
    alignItems: 'stretch',
    justifyContent: 'space-between',
    alignSelf: 'stretch',
    borderWidth: LINE.bold,
    borderColor: semantic['selected'],
    borderRadius: radius.control,
    backgroundColor: semantic['selected-tint'],
  },
  stepBtn: { minWidth: MIN_TARGET - space[3], alignItems: 'center', justifyContent: 'center' },
  stepPressed: { backgroundColor: semantic['surface-hover'] },
  stepValue: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.section.fontFamily, color: semantic['selected-ink'], alignSelf: 'center', textAlign: 'center', flexShrink: 1 },
  meta: { flexDirection: 'row', alignItems: 'flex-start', gap: space[1] },
  metaText: { flexShrink: 1 },
  card: { flex: 1, minWidth: 0, backgroundColor: semantic.surface },
  cardPress: { gap: space[2] },
  cardPressed: { opacity: 0.85 },
  cardBody: { gap: space[1] - LINE.hair },
  cardAction: { marginTop: space[2] },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: space[1] },
});
