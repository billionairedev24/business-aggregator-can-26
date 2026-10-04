import type { ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type AccessibilityRole,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

/**
 * The courier app's few building blocks, in Northline's tokens (colours, radii, spacing and fonts come from
 * @northline/mobile-kit, never literals). Large type and 48 dp targets: couriers use the app outdoors, often one-handed.
 */

export function Screen({ children, scroll = true, testID }: { children: ReactNode; scroll?: boolean; testID?: string }) {
  const body = <View style={styles.screenBody}>{children}</View>;
  return (
    <SafeAreaView style={styles.screen} edges={['bottom', 'left', 'right']} testID={testID}>
      {scroll ? (
        <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
          {body}
        </ScrollView>
      ) : (
        body
      )}
    </SafeAreaView>
  );
}

export function Heading({ children, level = 1 }: { children: ReactNode; level?: 1 | 2 }) {
  return (
    <Text accessibilityRole="header" style={level === 1 ? styles.h1 : styles.h2}>
      {children}
    </Text>
  );
}

export function Body({ children, muted, strong, style }: { children: ReactNode; muted?: boolean; strong?: boolean; style?: StyleProp<TextStyle> }) {
  return <Text style={[styles.body, muted && styles.muted, strong && styles.strong, style]}>{children}</Text>;
}

type Tone = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  label,
  onPress,
  tone = 'primary',
  disabled,
  busy,
  hint,
  testID,
}: {
  label: string;
  onPress: () => void;
  tone?: Tone;
  disabled?: boolean;
  busy?: boolean;
  hint?: string;
  testID?: string;
}) {
  const off = disabled || busy;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!off, busy: !!busy }}
      disabled={off}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.button, buttonTone[tone], pressed && pressedTone[tone], off && styles.disabled]}
    >
      {busy ? <ActivityIndicator color={tone === 'primary' ? colors.onAccent : colors.accent} /> : null}
      <Text style={[styles.buttonText, tone === 'primary' ? styles.onAccent : tone === 'danger' ? styles.dangerText : styles.accentText]}>{label}</Text>
    </Pressable>
  );
}

export function Card({ children, onPress, label, highlight, testID }: { children: ReactNode; onPress?: () => void; label?: string; highlight?: boolean; testID?: string }) {
  const style = [styles.card, highlight && styles.cardHighlight];
  if (!onPress) {
    return (
      <View style={style} testID={testID}>
        {children}
      </View>
    );
  }
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} testID={testID} style={({ pressed }) => [...style, pressed && styles.cardPressed]}>
      {children}
    </Pressable>
  );
}

type ChipTone = 'neutral' | 'accent' | 'done' | 'warn';

export function Chip({ label, tone = 'neutral' }: { label: string; tone?: ChipTone }) {
  return (
    <View style={[styles.chip, chipTone[tone]]}>
      <Text style={[styles.chipText, tone === 'accent' && styles.onAccent]}>{label}</Text>
    </View>
  );
}

type BannerTone = 'info' | 'warn' | 'error';

export function Banner({ children, tone = 'info', action, role = 'alert' }: { children: ReactNode; tone?: BannerTone; action?: ReactNode; role?: AccessibilityRole }) {
  return (
    <View accessibilityRole={role} accessibilityLiveRegion="polite" style={[styles.banner, bannerTone[tone]]}>
      <Text style={[styles.body, tone === 'error' && styles.errorText]}>{children}</Text>
      {action}
    </View>
  );
}

export function Segmented<T extends string>({ options, value, onChange, label }: { options: Array<{ value: T; label: string }>; value: T; onChange: (v: T) => void; label: string }) {
  return (
    <View accessibilityRole="tablist" accessibilityLabel={label} style={styles.segmented}>
      {options.map((o) => {
        const selected = o.value === value;
        return (
          <Pressable
            key={o.value}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            accessibilityLabel={o.label}
            onPress={() => onChange(o.value)}
            style={[styles.segment, selected && styles.segmentOn]}
          >
            <Text style={[styles.buttonText, selected ? styles.onAccent : styles.accentText]}>{o.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/** A checkbox (or one radio of a group) with a 48 dp target: the ID check's confirmations and the refusal's reasons. */
export function Check({ label, on, onPress, role = 'checkbox', testID }: { label: string; on: boolean; onPress: () => void; role?: 'checkbox' | 'radio'; testID?: string }) {
  return (
    <Pressable
      accessibilityRole={role}
      accessibilityLabel={label}
      accessibilityState={role === 'radio' ? { selected: on, checked: on } : { checked: on }}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.check, (on || pressed) && styles.checkOn]}
    >
      <View style={[styles.box, role === 'radio' && styles.round, on && styles.boxOn]}>{on ? <Text style={[styles.tick, styles.onAccent]}>✓</Text> : null}</View>
      <Text style={styles.body}>{label}</Text>
    </Pressable>
  );
}

export function Row({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[styles.row, style]}>{children}</View>;
}

export function Gap({ size = 4 }: { size?: keyof typeof space }) {
  return <View style={{ height: space[size] }} />;
}

export function Loading({ label }: { label: string }) {
  return (
    <View style={styles.loading} accessibilityLabel={label} accessibilityRole="progressbar">
      <ActivityIndicator color={colors.accent} size="large" />
    </View>
  );
}

const buttonTone: Record<Tone, ViewStyle> = {
  primary: { backgroundColor: colors.accent },
  secondary: { backgroundColor: colors.surface, borderColor: colors.neutral300, borderWidth: 1 },
  ghost: { backgroundColor: 'transparent' },
  danger: { backgroundColor: colors.surface, borderColor: colors.accent2, borderWidth: 1 },
};
const pressedTone: Record<Tone, ViewStyle> = {
  primary: { backgroundColor: colors.accent700 },
  secondary: { backgroundColor: colors.accent100 },
  ghost: { backgroundColor: colors.accent100 },
  danger: { backgroundColor: colors.accent2_100 },
};
const chipTone: Record<ChipTone, ViewStyle> = {
  neutral: { backgroundColor: colors.neutral200 },
  accent: { backgroundColor: colors.accent },
  done: { backgroundColor: colors.accent100 },
  warn: { backgroundColor: colors.highlight100 },
};
const bannerTone: Record<BannerTone, ViewStyle> = {
  info: { backgroundColor: colors.accent100, borderColor: colors.accent200 },
  warn: { backgroundColor: colors.highlight100, borderColor: colors.highlight300 },
  error: { backgroundColor: colors.accent2_100, borderColor: colors.accent2 },
};

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  scroll: { flexGrow: 1 },
  screenBody: { flex: 1, padding: space[4], gap: space[3] },
  h1: { fontFamily: fonts.heading, fontSize: 28, lineHeight: 34, color: colors.text },
  h2: { fontFamily: fonts.bodyStrong, fontSize: 17, lineHeight: 24, color: colors.text },
  body: { fontFamily: fonts.body, fontSize: 16, lineHeight: 23, color: colors.text, flexShrink: 1 },
  muted: { color: colors.neutral700 },
  strong: { fontFamily: fonts.bodyStrong },
  button: {
    minHeight: MIN_TARGET,
    minWidth: MIN_TARGET,
    paddingHorizontal: space[4],
    paddingVertical: space[3],
    borderRadius: radius.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space[2],
  },
  buttonText: { fontFamily: fonts.bodyStrong, fontSize: 16 },
  onAccent: { color: colors.onAccent },
  accentText: { color: colors.accent },
  dangerText: { color: colors.accent2_700 },
  errorText: { color: colors.accent2_700 },
  disabled: { opacity: 0.45 },
  card: { backgroundColor: colors.surface, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.divider, padding: space[4], gap: space[2] },
  cardHighlight: { borderColor: colors.accent, borderWidth: 2 },
  cardPressed: { backgroundColor: colors.accent100 },
  chip: { alignSelf: 'flex-start', borderRadius: radius.pill, paddingHorizontal: space[3], paddingVertical: space[1] },
  chipText: { fontFamily: fonts.bodyStrong, fontSize: 13, color: colors.text },
  banner: { borderRadius: radius.md, borderWidth: 1, padding: space[3], gap: space[2] },
  segmented: { flexDirection: 'row', backgroundColor: colors.surface2, borderRadius: radius.md, padding: space[1], gap: space[1] },
  segment: { flex: 1, minHeight: MIN_TARGET, borderRadius: radius.sm, alignItems: 'center', justifyContent: 'center' },
  segmentOn: { backgroundColor: colors.accent },
  row: { flexDirection: 'row', alignItems: 'center', gap: space[2], flexWrap: 'wrap' },
  check: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET, paddingHorizontal: space[3], paddingVertical: space[2], borderRadius: radius.md, borderWidth: 1, borderColor: colors.neutral300, backgroundColor: colors.surface },
  checkOn: { borderColor: colors.accent, backgroundColor: colors.accent100 },
  box: { width: 24, height: 24, borderRadius: radius.sm, borderWidth: 2, borderColor: colors.neutral500, alignItems: 'center', justifyContent: 'center' },
  round: { borderRadius: radius.pill },
  boxOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  tick: { fontFamily: fonts.bodyStrong, fontSize: 16 },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space[8] },
});
