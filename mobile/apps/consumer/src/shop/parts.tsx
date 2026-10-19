import type { ReactNode } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { Art, Icon, MIN_TARGET, colors, fonts, radius, semantic, space, type IconName } from '@northline/mobile-kit';

import { config } from '../config';
import { useI18n, type MessageKey } from '../i18n';
import { type } from '../ui/primitives';
import { GUTTER } from '../ui/screen';
import type { StepState } from '../api/shop';

/**
 * Journey B's pieces on the light, photo-first direction: the picture (the merchant's photo, else an illustrated scene
 * of the thing — `Art`, the web's drawn still lifes), the − n + pill stepper, pill chips, a sums row, the vertical
 * status timeline, illustrated tiles, the product card of a rail and a section head.
 */

/** A photo url the api names (absolute, or a path on the api's origin), else null. */
export const photoUri = (uri?: string | null) => (uri ? (/^https?:\/\//.test(uri) ? uri : uri.startsWith('/') ? `${new URL(config.apiUrl).origin}${uri}` : null) : null);

/**
 * An image the api names; none → the designed no-photo picture: an illustrated scene of `subject` (the product's,
 * shop's or category's name or slug — `Art` picks the drawing from its words), never a grey box.
 */
export function Thumb({
  uri,
  size,
  height,
  label,
  subject,
  icon: _icon,
  style,
}: {
  uri?: string | null;
  size?: number;
  height?: number;
  label?: string;
  /** What the picture shows when there is no photo (a name or slug). */
  subject?: string;
  /** @deprecated the no-photo picture is drawn from `subject` now. */
  icon?: IconName;
  style?: StyleProp<ViewStyle>;
}) {
  const src = photoUri(uri);
  const box = [styles.thumb, size ? { width: size, height: size } : { height: height ?? 200 }, style];
  if (src) return <Image source={{ uri: src }} style={box as never} accessibilityLabel={label} accessibilityRole="image" />;
  return (
    <View style={box} accessible={!!label} accessibilityLabel={label} accessibilityRole={label ? 'image' : undefined} testID="no-photo">
      <Art subject={subject ?? label ?? 'shop'} style={StyleSheet.absoluteFill} />
    </View>
  );
}

export function Kicker({ children }: { children: ReactNode }) {
  return <Text style={type.kicker}>{children}</Text>;
}

/** A section's head: the title (18 / 700) and an optional link on the right ("See all"). */
export function SectionHead({ title, link, onLink, testID }: { title: string; link?: string; onLink?: () => void; testID?: string }) {
  return (
    <View style={styles.sectionHead} testID={testID}>
      <Text accessibilityRole="header" style={type.section}>
        {title}
      </Text>
      {link && onLink ? (
        <Pressable accessibilityRole="link" accessibilityLabel={link} onPress={onLink} hitSlop={8} style={styles.headLink}>
          <Text style={styles.headLinkText}>{link}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

/** − n + : a pill with 44 pt targets (52 pt tall in the bottom action bar), the count announced with the item's name. */
export function Stepper({
  value,
  onLess,
  onMore,
  lessLabel,
  moreLabel,
  valueLabel,
  canLess = true,
  canMore = true,
  compact,
  testID,
}: {
  value: number;
  onLess: () => void;
  onMore: () => void;
  lessLabel: string;
  moreLabel: string;
  valueLabel: string;
  canLess?: boolean;
  canMore?: boolean;
  compact?: boolean;
  testID?: string;
}) {
  return (
    <View style={[styles.stepper, compact && styles.stepperCompact]} testID={testID}>
      <Pressable accessibilityRole="button" accessibilityLabel={lessLabel} accessibilityState={{ disabled: !canLess }} disabled={!canLess} onPress={onLess} style={[styles.step, compact && styles.stepCompact]}>
        <Icon name="minus" size={18} color={canLess ? colors.text : colors.textSubtle} />
      </Pressable>
      <Text accessibilityLabel={valueLabel} accessibilityLiveRegion="polite" style={styles.stepValue}>
        {value}
      </Text>
      <Pressable accessibilityRole="button" accessibilityLabel={moreLabel} accessibilityState={{ disabled: !canMore }} disabled={!canMore} onPress={onMore} style={[styles.step, compact && styles.stepCompact]}>
        <Icon name="plus" size={18} color={canMore ? colors.text : colors.textSubtle} />
      </Pressable>
    </View>
  );
}

/** A pill chip (MA's recent searches, reasons, kinds): the primary colour when on, a hairline otherwise. */
export function Chip({ label, on, onPress, role = 'checkbox', icon, testID }: { label: string; on: boolean; onPress: () => void; role?: 'checkbox' | 'radio'; icon?: IconName; testID?: string }) {
  return (
    <Pressable
      accessibilityRole={role}
      accessibilityLabel={label}
      accessibilityState={{ checked: on, selected: on }}
      onPress={onPress}
      style={[styles.chip, on && styles.chipOn]}
      testID={testID}
    >
      {icon ? <Icon name={icon} size={16} color={on ? colors.actionPrimaryText : colors.text} /> : null}
      <Text style={[styles.chipText, on && styles.chipTextOn]}>{label}</Text>
    </Pressable>
  );
}

export function SumRow({ label, value, strong, tone }: { label: string; value: string; strong?: boolean; tone?: 'accent2' }) {
  return (
    <View style={[styles.sum, strong && styles.sumStrong]}>
      <Text style={[strong ? styles.sumTotal : type.body, tone === 'accent2' && styles.sumAccent2, styles.flex]}>{label}</Text>
      <Text style={[strong ? styles.sumTotal : type.body, tone === 'accent2' && styles.sumAccent2]}>{value}</Text>
    </View>
  );
}

/**
 * The status timeline (MA:261): a 16 pt dot per step on a 2 pt line — done = filled primary, current = a primary ring,
 * to do = a grey ring and muted text.
 */
export function Ladder({ steps }: { steps: Array<{ key: string; state: StepState; label: ReactNode; detail?: string }> }) {
  const { t } = useI18n();
  return (
    <View style={styles.ladder} accessibilityRole="list">
      {steps.map((s, i) => (
        <View key={s.key} style={styles.rung} accessible accessibilityLabel={`${typeof s.label === 'string' ? s.label : ''}${s.detail ? ` · ${s.detail}` : ''}, ${t(`shop.stepState.${s.state}` as MessageKey)}`}>
          <View style={styles.rail}>
            <View style={[styles.dot, s.state === 'done' ? styles.dotDone : s.state === 'current' ? styles.dotCurrent : styles.dotTodo]} />
            {i < steps.length - 1 ? <View style={[styles.line, s.state === 'done' && styles.lineDone]} /> : null}
          </View>
          <View style={styles.rungText}>
            {typeof s.label === 'string' ? <Text style={[type.body, type.strong, s.state === 'todo' && styles.muted]}>{s.label}</Text> : s.label}
            {s.detail ? <Text style={type.small}>{s.detail}</Text> : null}
          </View>
        </View>
      ))}
    </View>
  );
}

/** A panel: linen (info boxes, the parts that recede), the brand tint (`accent`); `dark` is the brand tint too now (the light direction has no dark panels). */
export function Panel({ children, tone = 'surface', style, testID }: { children: ReactNode; tone?: 'surface' | 'accent' | 'dark'; style?: StyleProp<ViewStyle>; testID?: string }) {
  return <View style={[styles.panel, tone === 'accent' && styles.panelAccent, tone === 'dark' && styles.panelDark, style]} testID={testID}>{children}</View>;
}

/** A row of an info panel: an icon and its line (MA:229 — "Tonight to …", "Sold by …", returns). */
export function InfoLine({ icon, children, color }: { icon: IconName; children: ReactNode; color?: string }) {
  return (
    <View style={styles.info}>
      <Icon name={icon} size={18} color={color ?? colors.text} />
      <Text style={[type.body, styles.flex]}>{children}</Text>
    </View>
  );
}

/**
 * A sideways rail that runs to the screen's edges, so the next card peeks (rails of tiles and cards). In a padded
 * screen it reaches past the gutter; `edge` = the parent has no side padding (a full-width screen).
 */
export function Strip({ children, edge, testID }: { children: ReactNode; edge?: boolean; testID?: string }) {
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} style={!edge && styles.strip} contentContainerStyle={styles.stripContent} testID={testID}>
      {children}
    </ScrollView>
  );
}

/**
 * An illustrated tile (the web's `Tile`): a picture with rounded corners — the photo, else the scene of `subject` — the
 * name (two lines at most) and a muted line under it ("22 pros", "Coming soon"). `width` sets a rail's tile; without
 * it the tile fills its column (grids).
 */
export function Tile({ label, sub, subject, imageUrl, onPress, width, testID }: { label: string; sub?: string; subject: string; imageUrl?: string | null; onPress: () => void; width?: number; testID?: string }) {
  const w = width ?? undefined;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={sub ? `${label}, ${sub}` : label}
      onPress={onPress}
      style={({ pressed }) => [styles.tileBox, w ? { width: w } : styles.tileFill, pressed && styles.pressed]}
      testID={testID}
    >
      <View style={[styles.tilePic, { aspectRatio: 4 / 3 }]}>
        {photoUri(imageUrl) ? <Image source={{ uri: photoUri(imageUrl)! }} style={StyleSheet.absoluteFill} /> : <Art subject={subject} style={StyleSheet.absoluteFill} />}
      </View>
      <Text style={styles.tileName} numberOfLines={2}>
        {label}
      </Text>
      {sub ? (
        <Text style={styles.tileSub} numberOfLines={1}>
          {sub}
        </Text>
      ) : null}
    </Pressable>
  );
}

/** A rail's product card (photo first): a 156 pt square picture, the name on two lines, the price and the shop. */
export function ProductTile({ name, price, imageUrl, onPress, label, meta, testID }: { name: string; price: string; imageUrl?: string | null; onPress: () => void; label: string; meta?: string; testID?: string }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={({ pressed }) => [styles.tile, pressed && styles.pressed]} testID={testID}>
      <Thumb uri={imageUrl} size={156} subject={name} />
      <Text style={styles.productName} numberOfLines={2}>
        {name}
      </Text>
      <Text style={styles.tilePrice}>{price}</Text>
      {meta ? (
        <Text style={styles.tileSub} numberOfLines={1}>
          {meta}
        </Text>
      ) : null}
    </Pressable>
  );
}

export const shopStyles = StyleSheet.create({
  section: { gap: space[2], paddingTop: space[2] },
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  row: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET, paddingVertical: space[2] },
  rowText: { flex: 1, minWidth: 0, gap: 2 },
  right: { alignItems: 'flex-end', gap: 2 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  strong: { fontFamily: fonts.bold },
  price: { fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  bigPrice: { fontFamily: fonts.bold, fontSize: 28, color: colors.text },
  accentText: { color: semantic['badge-brand-text'] },
  accent800: { color: semantic['badge-brand-text'] },
});

const styles = StyleSheet.create({
  flex: { flex: 1 },
  thumb: { borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'], overflow: 'hidden' },
  pressed: { opacity: 0.85 },
  sectionHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space[2] },
  headLink: { minHeight: MIN_TARGET, justifyContent: 'center' },
  headLinkText: { fontFamily: fonts.semibold, fontSize: 15, color: colors.link },
  stepper: { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: colors.borderControl, borderRadius: radius.pill, minHeight: 52 },
  stepperCompact: { minHeight: MIN_TARGET },
  step: { width: 48, height: 52, alignItems: 'center', justifyContent: 'center' },
  stepCompact: { width: MIN_TARGET, height: MIN_TARGET },
  stepValue: { minWidth: 24, textAlign: 'center', fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  chip: { minHeight: MIN_TARGET, flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 16, borderRadius: radius.pill, borderWidth: 1, borderColor: colors.borderControl, backgroundColor: colors.surface },
  chipOn: { backgroundColor: colors.actionPrimary, borderColor: colors.actionPrimary },
  chipText: { fontFamily: fonts.medium, fontSize: 14, color: colors.text },
  chipTextOn: { fontFamily: fonts.semibold, color: colors.actionPrimaryText },
  sum: { flexDirection: 'row', gap: space[2] },
  sumStrong: { paddingTop: 6 },
  sumTotal: { fontFamily: fonts.black, fontSize: 17, color: colors.text },
  sumAccent2: { color: semantic.success },
  ladder: { gap: 0 },
  rung: { flexDirection: 'row', gap: space[3] },
  rail: { width: 24, alignItems: 'center' },
  dot: { width: 16, height: 16, borderRadius: 8, marginTop: 3 },
  dotDone: { backgroundColor: colors.actionPrimary, borderWidth: 2, borderColor: colors.actionPrimary },
  dotCurrent: { backgroundColor: colors.surface, borderWidth: 3, borderColor: colors.actionPrimary },
  dotTodo: { backgroundColor: colors.surface, borderWidth: 2, borderColor: colors.borderStrong },
  line: { flex: 1, width: 2, minHeight: 22, backgroundColor: colors.borderStrong },
  lineDone: { backgroundColor: colors.actionPrimary },
  rungText: { flex: 1, paddingBottom: 14, gap: 2 },
  muted: { color: colors.textMuted },
  panel: { padding: space[4], borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'], gap: space[2] },
  panelAccent: { backgroundColor: semantic['surface-brand'] },
  panelDark: { backgroundColor: semantic['surface-brand'] },
  info: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  strip: { marginHorizontal: -GUTTER },
  stripContent: { paddingHorizontal: GUTTER, gap: space[3] },
  tileBox: { gap: 4, minHeight: MIN_TARGET },
  tileFill: { flex: 1 },
  tilePic: { width: '100%', borderRadius: radius.lg, overflow: 'hidden', backgroundColor: semantic['surface-sunken'], marginBottom: 4 },
  tileName: { fontFamily: fonts.semibold, fontSize: 15, lineHeight: 20, color: colors.text },
  tileSub: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 18, color: colors.textMuted },
  tile: { width: 156, gap: 4, minHeight: MIN_TARGET },
  productName: { fontFamily: fonts.medium, fontSize: 14, lineHeight: 19, color: colors.text, marginTop: 4 },
  tilePrice: { fontFamily: fonts.bold, fontSize: 15, color: colors.text },
});
