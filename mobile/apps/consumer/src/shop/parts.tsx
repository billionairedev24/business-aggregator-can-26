import type { ReactNode } from 'react';
import { Image, Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import { config } from '../config';
import { useI18n, type MessageKey } from '../i18n';
import { type } from '../ui/primitives';
import type { StepState } from '../api/shop';

/**
 * Journey B's small pieces, drawn as design 01 draws them: the halftone picture block, the − n + stepper, a filter
 * chip, a sums row, the status ladder (filled / ringed / grey dots) and the uppercase kicker.
 */

/** An image the api names (absolute, or a path on the api's origin); none → the design's grey halftone block. */
export function Thumb({ uri, size, height, label, style }: { uri?: string | null; size?: number; height?: number; label?: string; style?: StyleProp<ViewStyle> }) {
  const src = uri ? (/^https?:\/\//.test(uri) ? uri : uri.startsWith('/') ? `${new URL(config.apiUrl).origin}${uri}` : null) : null;
  const box = [styles.thumb, size ? { width: size, height: size } : { height: height ?? 200 }, style];
  if (src) return <Image source={{ uri: src }} style={box as never} accessibilityLabel={label} accessibilityRole="image" />;
  return <View style={box} accessible={!!label} accessibilityLabel={label} accessibilityRole={label ? 'image' : undefined} />;
}

export function Kicker({ children }: { children: ReactNode }) {
  return <Text style={type.kicker}>{children}</Text>;
}

/** − n + : 48 dp targets, the count announced with the item's name. */
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
    <View style={styles.stepper} testID={testID}>
      <Pressable accessibilityRole="button" accessibilityLabel={lessLabel} accessibilityState={{ disabled: !canLess }} disabled={!canLess} onPress={onLess} style={[styles.step, compact && styles.stepCompact]}>
        <Text style={[styles.stepSign, !canLess && styles.off]}>−</Text>
      </Pressable>
      <Text accessibilityLabel={valueLabel} accessibilityLiveRegion="polite" style={styles.stepValue}>
        {value}
      </Text>
      <Pressable accessibilityRole="button" accessibilityLabel={moreLabel} accessibilityState={{ disabled: !canMore }} disabled={!canMore} onPress={onMore} style={[styles.step, compact && styles.stepCompact]}>
        <Text style={[styles.stepSign, !canMore && styles.off]}>+</Text>
      </Pressable>
    </View>
  );
}

/** The design's `chip`: filled accent when on. */
export function Chip({ label, on, onPress, role = 'checkbox', testID }: { label: string; on: boolean; onPress: () => void; role?: 'checkbox' | 'radio'; testID?: string }) {
  return (
    <Pressable
      accessibilityRole={role}
      accessibilityLabel={label}
      accessibilityState={{ checked: on, selected: on }}
      onPress={onPress}
      style={[styles.chip, on && styles.chipOn]}
      testID={testID}
    >
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

/** The design's status ladder: done = filled dot, current = accent ring, todo = grey ring and muted text. */
export function Ladder({ steps }: { steps: Array<{ key: string; state: StepState; label: ReactNode; detail?: string }> }) {
  const { t } = useI18n();
  return (
    <View style={styles.ladder} accessibilityRole="list">
      {steps.map((s) => (
        <View key={s.key} style={styles.rung} accessible accessibilityLabel={`${typeof s.label === 'string' ? s.label : ''}${s.detail ? ` · ${s.detail}` : ''}, ${t(`shop.stepState.${s.state}` as MessageKey)}`}>
          <View style={[styles.dot, s.state === 'done' ? styles.dotDone : s.state === 'current' ? styles.dotCurrent : styles.dotTodo]} />
          <Text style={[type.body, styles.flex, s.state === 'todo' && styles.muted]}>
            {typeof s.label === 'string' ? <Text style={s.detail ? type.strong : undefined}>{s.label}</Text> : s.label}
            {s.detail ? ` · ${s.detail}` : ''}
          </Text>
        </View>
      ))}
    </View>
  );
}

export function Panel({ children, tone = 'surface', style, testID }: { children: ReactNode; tone?: 'surface' | 'accent'; style?: StyleProp<ViewStyle>; testID?: string }) {
  return <View style={[styles.panel, tone === 'accent' && styles.panelAccent, style]} testID={testID}>{children}</View>;
}

export const shopStyles = StyleSheet.create({
  section: { gap: space[2], paddingTop: space[3] },
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 14, minHeight: MIN_TARGET, paddingVertical: space[2] },
  rowText: { flex: 1, minWidth: 0, gap: 2 },
  right: { alignItems: 'flex-end', gap: 2 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  strong: { fontFamily: fonts.bodyStrong },
  price: { fontFamily: fonts.bodyStrong, fontSize: 15, color: colors.text },
  bigPrice: { fontFamily: fonts.bodyStrong, fontSize: 22, color: colors.text },
  accentText: { color: colors.accent900 },
  accent800: { color: colors.accent800 },
});

const styles = StyleSheet.create({
  flex: { flex: 1 },
  thumb: { borderRadius: radius.md, backgroundColor: colors.neutral400, overflow: 'hidden' },
  stepper: { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: colors.divider, borderRadius: radius.md },
  step: { width: MIN_TARGET, height: MIN_TARGET, alignItems: 'center', justifyContent: 'center' },
  stepCompact: { width: 44, height: MIN_TARGET },
  stepSign: { fontFamily: fonts.body, fontSize: 20, color: colors.accent700 },
  off: { color: colors.neutral400 },
  stepValue: { minWidth: 22, textAlign: 'center', fontFamily: fonts.bodyStrong, fontSize: 15, color: colors.text },
  chip: { minHeight: MIN_TARGET, justifyContent: 'center', paddingHorizontal: 12, borderRadius: radius.md, borderWidth: 1, borderColor: colors.divider },
  chipOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  chipText: { fontFamily: fonts.body, fontSize: 13, color: colors.text },
  chipTextOn: { color: colors.onAccent },
  sum: { flexDirection: 'row', gap: space[2] },
  sumStrong: { paddingTop: 6 },
  sumTotal: { fontFamily: fonts.bodyStrong, fontSize: 17, color: colors.text },
  sumAccent2: { color: colors.accent2_700 },
  ladder: { gap: 10 },
  rung: { flexDirection: 'row', gap: 12, alignItems: 'flex-start' },
  dot: { width: 20, height: 20, borderRadius: 10, marginTop: 1 },
  dotDone: { backgroundColor: colors.accent },
  dotCurrent: { borderWidth: 2, borderColor: colors.accent },
  dotTodo: { borderWidth: 2, borderColor: colors.neutral300 },
  muted: { color: colors.neutral700 },
  panel: { padding: space[4], borderRadius: radius.md, backgroundColor: colors.surface, gap: space[1] },
  panelAccent: { backgroundColor: colors.accent100 },
});
