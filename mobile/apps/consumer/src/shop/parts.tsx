import type { ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { colors, FilterChip, fonts, Icon, LINE, MIN_TARGET, QuantityStepper, radius, semantic, space, textStyles, type IconName } from '@northline/mobile-kit';

import { useI18n, type MessageKey } from '../i18n';
import { type } from '../ui/primitives';
import { Picture } from '../ui/commerce';
import { GUTTER } from '../ui/screen';
import type { StepState } from '../api/shop';

/**
 * Journey B's pieces on the light, photo-first direction: the picture (the merchant's photo, else an illustrated scene
 * of the thing — `Art`, the web's drawn still lifes), the − n + pill stepper, pill chips, a sums row, the vertical
 * status timeline, illustrated tiles, the product card of a rail and a section head.
 */

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

/** − n + : the kit's QuantityStepper (ink edge on the selected tint, 44 pt targets; 46 pt in the bottom action bar), the count announced with the item's name. */
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
    <View style={compact ? styles.stepperCompact : styles.stepper}>
      <QuantityStepper
        value={value}
        onLess={onLess}
        onMore={onMore}
        lessLabel={lessLabel}
        moreLabel={moreLabel}
        valueLabel={valueLabel}
        canLess={canLess}
        canMore={canMore}
        text={null}
        size={compact ? 'sm' : 'lg'}
        testID={testID}
      />
    </View>
  );
}

/** A square chip (the kit's FilterChip): chosen = the ink edge on the selected tint with ✓, for one choice (radio) or many (checkbox). */
export function Chip({ label, on, onPress, role = 'checkbox', icon, testID }: { label: string; on: boolean; onPress: () => void; role?: 'checkbox' | 'radio'; icon?: IconName; testID?: string }) {
  return <FilterChip label={label} selected={on} onPress={onPress} role={role} icon={icon} testID={testID} />;
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
    <ScrollView horizontal showsHorizontalScrollIndicator={false} style={[styles.stripBox, !edge && styles.strip]} contentContainerStyle={styles.stripContent} testID={testID}>
      {children}
    </ScrollView>
  );
}

/**
 * A tile (the web's `Tile`): the photo in the grey well — the library's for its slot, else the dashed well with the
 * icon — the name (two lines at most) and a muted line under it ("22 pros", "Coming soon"). `width` sets a rail's
 * tile; without it the tile fills its column (grids).
 */
export function Tile({ label, sub, slot, icon = 'storefront', onPress, width, testID }: { label: string; sub?: string; slot?: string | null; icon?: IconName; onPress: () => void; width?: number; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={sub ? `${label}, ${sub}` : label}
      onPress={onPress}
      style={({ pressed }) => [styles.tileBox, width ? { width } : styles.tileFill, pressed && styles.pressed]}
      testID={testID}
    >
      <Picture slot={[slot]} crop="card" icon={icon} style={styles.tilePic} />
      <Text style={textStyles.bodyStrong} numberOfLines={2}>
        {label}
      </Text>
      {sub ? (
        <Text style={textStyles.meta} numberOfLines={1}>
          {sub}
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
  price: { ...StyleSheet.flatten(textStyles.bodyStrong) },
  bigPrice: { ...StyleSheet.flatten(textStyles.pageTitle) },
  accentText: { color: semantic['badge-brand-text'] },
  accent800: { color: semantic['badge-brand-text'] },
});

const styles = StyleSheet.create({
  flex: { flex: 1 },
  pressed: { opacity: 0.85 },
  sectionHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space[2] },
  headLink: { minHeight: MIN_TARGET, justifyContent: 'center' },
  headLinkText: { ...StyleSheet.flatten(textStyles.bodyStrong), color: semantic.link },
  stepper: { minWidth: space[16] + space[12] },
  stepperCompact: { minWidth: space[16] + space[8] },
  sum: { flexDirection: 'row', gap: space[2] },
  sumStrong: { paddingTop: space[1.5] },
  sumTotal: { ...StyleSheet.flatten(textStyles.sub) },
  sumAccent2: { color: semantic.success },
  ladder: { gap: 0 },
  rung: { flexDirection: 'row', gap: space[3] },
  rail: { width: 24, alignItems: 'center' },
  dot: { width: space[4], height: space[4], borderRadius: radius.round, marginTop: space[1] },
  dotDone: { backgroundColor: semantic['status-success-fg'], borderWidth: LINE.bold, borderColor: semantic['status-success-fg'] },
  dotCurrent: { backgroundColor: semantic.surface, borderWidth: LINE.bold + 1, borderColor: semantic.selected },
  dotTodo: { backgroundColor: semantic.surface, borderWidth: LINE.bold, borderColor: semantic['border-strong'] },
  line: { flex: 1, width: 2, minHeight: 22, backgroundColor: semantic['border-strong'] },
  lineDone: { backgroundColor: semantic['status-success-fg'] },
  rungText: { flex: 1, paddingBottom: space[3.5], gap: 2 },
  muted: { color: semantic['text-muted'] },
  panel: { padding: space[4], borderRadius: radius.panel, backgroundColor: semantic.well, gap: space[2] },
  panelAccent: { backgroundColor: semantic['status-info-bg'] },
  panelDark: { backgroundColor: semantic['status-info-bg'] },
  info: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  strip: { marginHorizontal: -GUTTER },
  stripBox: { flexGrow: 0 },
  stripContent: { paddingHorizontal: GUTTER, gap: space[3] },
  tileBox: { gap: space[1], minHeight: MIN_TARGET },
  tileFill: { flex: 1 },
  tilePic: { width: '100%', aspectRatio: 4 / 3, marginBottom: space[1] },
});
