import type { QueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import { KEYS as ACCOUNT_KEYS } from '../account/common';
import { servicesApi } from '../api/services';
import { useI18n, type MessageKey } from '../i18n';
import { services } from '../services';
import { type, type TagTone } from '../ui/primitives';

/** Journey C's building blocks: the design's chips, the provider mark, summary lines, the wizard's progress bar. */

export const useServicesApi = () => servicesApi(services().api);

/** The favourites C reads (the heart on a provider's profile). */
export const FAVOURITES_KEY = ['services', 'favourites'] as const;

/**
 * After a favourite is added or removed here: C's heart, and Journey D's You count and Favourites list, which would
 * otherwise wait out their minute of staleness (MOBILE_PLAN § Contracts › Account summary).
 */
export const favouritesChanged = (qc: QueryClient) =>
  Promise.all([FAVOURITES_KEY, ACCOUNT_KEYS.summary, ACCOUNT_KEYS.favourites].map((queryKey) => qc.invalidateQueries({ queryKey })));

/** Tier code → its words and tag colour (design: Master accent, Trusted rosehip, Registered neutral). */
export function useTier() {
  const { t } = useI18n();
  return (tier: string): { label: string; tone: TagTone } => {
    switch (tier) {
      case 'master':
        return { label: t('services.tier.master'), tone: 'accent' };
      case 'trusted':
        return { label: t('services.tier.trusted'), tone: 'accent2' };
      default:
        return { label: t('services.tier.registered'), tone: 'neutral' };
    }
  };
}

/** The design's `chip`: a filter or a time (accent fill when chosen). */
export function Chip({ label, selected, onPress, disabled, hint, testID }: { label: string; selected: boolean; onPress: () => void; disabled?: boolean; hint?: string; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ selected, disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.chip, selected && styles.chipOn, pressed && !selected && styles.chipPressed, disabled && styles.chipOff]}
    >
      <Text style={[styles.chipText, selected && styles.chipTextOn, disabled && styles.chipTextOff]}>{label}</Text>
    </Pressable>
  );
}

const HEX = /^#[0-9a-fA-F]{6}$/;

/** The api's `brandColor` when it is a usable colour, else the accent. */
export const brand = (color: string | null | undefined) => (color && HEX.test(color) ? color : colors.accent);

/** The provider's initial on a colour (its brand colour, see {@link brand}). */
export function Mark({ name, color, size = 56, ink }: { name: string; color?: string | null; size?: number; ink?: string }) {
  return (
    <View
      accessible={false}
      importantForAccessibility="no-hide-descendants"
      style={[styles.mark, { width: size, height: size, backgroundColor: color ?? colors.accent }]}
    >
      <Text style={[styles.markText, { fontSize: size * 0.4 }, ink ? { color: ink } : null]}>{name.trim().charAt(0).toUpperCase()}</Text>
    </View>
  );
}

/** A summary line: words on the left, the amount on the right. */
export function Line({ left, right, strong, muted, testID }: { left: string; right: string; strong?: boolean; muted?: boolean; testID?: string }) {
  return (
    <View style={styles.line} testID={testID}>
      <Text style={[type.body, styles.lineLeft, strong && styles.lineStrong]}>{left}</Text>
      <Text style={[type.body, strong && styles.lineStrong, muted && styles.muted]}>{right}</Text>
    </View>
  );
}

/** A surface panel (the design's `background: surface` boxes). */
export function Panel({ children, tone = 'surface', style, testID }: { children: ReactNode; tone?: 'surface' | 'accent'; style?: StyleProp<ViewStyle>; testID?: string }) {
  return <View style={[styles.panel, tone === 'accent' && styles.panelAccent, style]} testID={testID}>{children}</View>;
}

/** The wizard's three-part progress bar (service · time · review). */
export function WizardSteps({ at }: { at: 1 | 2 | 3 }) {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('services.book.step', { n: at })} accessibilityValue={{ min: 1, max: 3, now: at }} style={styles.steps}>
      {[1, 2, 3].map((n) => (
        <View key={n} style={[styles.step, n <= at && styles.stepOn]} />
      ))}
    </View>
  );
}

/** A tappable row (list items: providers, notifications, menu prices). */
export function Row({ children, onPress, label, hint, testID }: { children: ReactNode; onPress: () => void; label: string; hint?: string; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}
    >
      {children}
    </Pressable>
  );
}

/** A kicker line (13px uppercase). */
export function Kicker({ children }: { children: ReactNode }) {
  return <Text style={type.kicker}>{children}</Text>;
}

export type T = (key: MessageKey, params?: Record<string, string | number>) => string;

const styles = StyleSheet.create({
  chip: {
    minHeight: MIN_TARGET,
    paddingHorizontal: 12,
    justifyContent: 'center',
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.divider,
  },
  chipOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  chipPressed: { backgroundColor: colors.accent100 },
  chipOff: { opacity: 0.45 },
  chipText: { fontFamily: fonts.body, fontSize: 13, color: colors.text },
  chipTextOn: { color: colors.onAccent },
  chipTextOff: { textDecorationLine: 'line-through' },
  mark: { borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  markText: { fontFamily: fonts.bodyStrong, color: colors.onAccent },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: space[3] },
  lineLeft: { flex: 1 },
  lineStrong: { fontFamily: fonts.bodyStrong, fontSize: 17 },
  muted: { color: colors.neutral700 },
  panel: { padding: 14, borderRadius: radius.md, backgroundColor: colors.surface, gap: space[2] },
  panelAccent: { backgroundColor: colors.accent100 },
  steps: { flexDirection: 'row', gap: 4 },
  step: { flex: 1, height: 3, backgroundColor: colors.neutral300 },
  stepOn: { backgroundColor: colors.accent },
  row: { minHeight: MIN_TARGET, paddingVertical: space[3], gap: space[2] },
  rowPressed: { backgroundColor: colors.accent100 },
});
