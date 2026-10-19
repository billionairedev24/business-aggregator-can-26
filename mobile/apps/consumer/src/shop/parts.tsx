import type { ReactNode } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { Icon, MIN_TARGET, colors, fonts, radius, semantic, space, type IconName } from '@northline/mobile-kit';

import { config } from '../config';
import { useI18n, type MessageKey } from '../i18n';
import { type } from '../ui/primitives';
import { GUTTER } from '../ui/screen';
import type { StepState } from '../api/shop';

/**
 * Journey B's pieces on the redesign's mobile layout (MA, spec § 1.3 Mobile): the picture block (a tinted tile with an
 * icon when the shop has no photo), the − n + pill stepper, pill chips, a sums row, the vertical status timeline
 * (MA:261), category circles (60 pt in a 72 pt column), the 132 pt product tile of a rail and a section head.
 */

/** An image the api names (absolute, or a path on the api's origin); none → a tinted tile with the item's icon. */
export function Thumb({ uri, size, height, label, icon = 'package', style }: { uri?: string | null; size?: number; height?: number; label?: string; icon?: IconName; style?: StyleProp<ViewStyle> }) {
  const src = uri ? (/^https?:\/\//.test(uri) ? uri : uri.startsWith('/') ? `${new URL(config.apiUrl).origin}${uri}` : null) : null;
  const box = [styles.thumb, size ? { width: size, height: size } : { height: height ?? 200 }, style];
  if (src) return <Image source={{ uri: src }} style={box as never} accessibilityLabel={label} accessibilityRole="image" />;
  const glyph = Math.round(Math.min(size ?? height ?? 200, 120) * 0.36);
  return (
    <View style={[box, styles.placeholder]} accessible={!!label} accessibilityLabel={label} accessibilityRole={label ? 'image' : undefined}>
      <Icon name={icon} size={glyph} color={semantic['text-muted']} />
    </View>
  );
}

/** A department's icon from words in its slug (the catalogue's departments have no icon of their own). */
export function departmentIcon(slug: string): IconName {
  if (/groc|pantry|produce|food|bake|butcher|deli/.test(slug)) return 'basket';
  if (/auto|part|hardware|tool/.test(slug)) return 'wrench';
  if (/beauty|hair|barber/.test(slug)) return 'scissors';
  if (/drink|bar|wine|beer|liquor/.test(slug)) return 'martini';
  if (/home|garden|furn/.test(slug)) return 'house-line';
  return 'shopping-bag';
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

/** A panel: the muted surface (MA's info boxes), the brand tint (`accent`) or the dark header colour (`dark`). */
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

/** A horizontal strip that bleeds to the screen's edges (rails, category circles). */
export function Strip({ children, testID }: { children: ReactNode; testID?: string }) {
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.strip} contentContainerStyle={styles.stripContent} testID={testID}>
      {children}
    </ScrollView>
  );
}

/** A category circle (MA:191): a 60 pt tinted circle with the icon over a 12 pt semibold label, in a 72 pt column. */
export function Circle({ label, icon, onPress, testID }: { label: string; icon: IconName; onPress: () => void; testID?: string }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={styles.circleCol} testID={testID}>
      <View style={styles.circle}>
        <Icon name={icon} size={26} />
      </View>
      <Text style={styles.circleText} numberOfLines={2} maxFontSizeMultiplier={1.3}>
        {label}
      </Text>
    </Pressable>
  );
}

/** A rail's product tile (MA:200): a 132 pt picture, the name on two lines, the price. */
export function ProductTile({ name, price, imageUrl, onPress, label, testID }: { name: string; price: string; imageUrl?: string | null; onPress: () => void; label: string; testID?: string }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={styles.tile} testID={testID}>
      <Thumb uri={imageUrl} size={132} />
      <Text style={styles.tileName} numberOfLines={2}>
        {name}
      </Text>
      <Text style={styles.tilePrice}>{price}</Text>
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
  bigPrice: { fontFamily: fonts.black, fontSize: 28, color: colors.text },
  accentText: { color: semantic['badge-brand-text'] },
  accent800: { color: semantic['badge-brand-text'] },
});

const styles = StyleSheet.create({
  flex: { flex: 1 },
  thumb: { borderRadius: radius.md, backgroundColor: semantic['surface-sunken'], overflow: 'hidden' },
  placeholder: { alignItems: 'center', justifyContent: 'center' },
  sectionHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space[2] },
  headLink: { minHeight: MIN_TARGET, justifyContent: 'center' },
  headLinkText: { fontFamily: fonts.semibold, fontSize: 14, color: colors.text, textDecorationLine: 'underline' },
  stepper: { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: colors.borderControl, borderRadius: radius.pill, minHeight: 52 },
  stepperCompact: { minHeight: MIN_TARGET },
  step: { width: 48, height: 52, alignItems: 'center', justifyContent: 'center' },
  stepCompact: { width: MIN_TARGET, height: MIN_TARGET },
  stepValue: { minWidth: 24, textAlign: 'center', fontFamily: fonts.bold, fontSize: 15, color: colors.text },
  chip: { minHeight: MIN_TARGET, flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 14, borderRadius: radius.pill, borderWidth: 1, borderColor: colors.borderControl, backgroundColor: colors.surface },
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
  panel: { padding: 14, borderRadius: radius.md, backgroundColor: semantic['surface-muted'], gap: space[2] },
  panelAccent: { backgroundColor: semantic['surface-brand'] },
  panelDark: { backgroundColor: colors.headerBg },
  info: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  strip: { marginHorizontal: -GUTTER },
  stripContent: { paddingHorizontal: GUTTER, gap: space[3] },
  circleCol: { width: 72, alignItems: 'center', gap: 6, minHeight: MIN_TARGET },
  circle: { width: 60, height: 60, borderRadius: 30, backgroundColor: semantic['surface-brand'], alignItems: 'center', justifyContent: 'center' },
  circleText: { fontFamily: fonts.semibold, fontSize: 12, lineHeight: 15, textAlign: 'center', color: colors.text },
  tile: { width: 132, gap: 6, minHeight: MIN_TARGET },
  tileName: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 16, color: colors.text },
  tilePrice: { fontFamily: fonts.bold, fontSize: 14, color: colors.text },
});
