import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Switch, Text, View } from 'react-native';

import { Icon, MIN_TARGET, colors, fonts, radius, semantic, space, type IconName } from '@northline/mobile-kit';

import { type } from '../ui/primitives';

/**
 * Journey D's small pieces on the redesign's account layout (MA:296): a settings row (icon, name, value and a caret,
 * 52 pt, the whole row the target) inside a bordered group, a labelled on/off switch, a section heading (16 / 700).
 */

export function SettingsRow({ name, value, onPress, hint, icon, tone, testID }: { name: string; value?: string | null; onPress: () => void; hint?: string; icon?: IconName; tone?: 'danger'; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={value ? `${name}, ${value}` : name}
      accessibilityHint={hint}
      onPress={onPress}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      testID={testID}
    >
      {icon ? <Icon name={icon} size={22} color={tone === 'danger' ? colors.danger : colors.text} /> : null}
      <Text style={[type.body, styles.name, tone === 'danger' && styles.danger]}>{name}</Text>
      {value ? (
        <Text style={[type.small, styles.value]} numberOfLines={1}>
          {value}
        </Text>
      ) : null}
      <Icon name="caret-right" size={16} color={colors.textMuted} />
    </Pressable>
  );
}

/** MA:301 — a group of settings rows: the 12 pt uppercase label, then the rows in one bordered, rounded box. */
export function SettingsGroup({ title, children, testID }: { title?: string; children: ReactNode; testID?: string }) {
  return (
    <View style={styles.group} testID={testID}>
      {title ? (
        <Text accessibilityRole="header" style={type.kicker}>
          {title}
        </Text>
      ) : null}
      <View style={styles.box}>{children}</View>
    </View>
  );
}

/** A row with words on the left and anything on the right (a tag, a button). */
export function LineRow({ children, right, testID }: { children: ReactNode; right?: ReactNode; testID?: string }) {
  return (
    <View style={styles.row} testID={testID}>
      <View style={styles.name}>{children}</View>
      {right}
    </View>
  );
}

export function Toggle({ label, value, onChange, disabled, testID }: { label: string; value: boolean; onChange: (v: boolean) => void; disabled?: boolean; testID?: string }) {
  return (
    <View style={styles.row}>
      <Text style={[type.body, styles.name]}>{label}</Text>
      <Switch
        accessibilityRole="switch"
        accessibilityLabel={label}
        accessibilityState={{ checked: value, disabled: !!disabled }}
        value={value}
        onValueChange={onChange}
        disabled={disabled}
        trackColor={{ true: colors.accent, false: colors.neutral300 }}
        thumbColor={colors.surface}
        testID={testID}
      />
    </View>
  );
}

/** The design's h3 (16px, semibold) between groups of rows. */
export function Heading({ children }: { children: ReactNode }) {
  return (
    <Text accessibilityRole="header" style={styles.heading}>
      {children}
    </Text>
  );
}

export const accountStyles = StyleSheet.create({
  list: { gap: 0 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  form: { gap: space[3] },
  actions: { gap: space[2], paddingTop: space[2] },
  sub: { gap: 2, flex: 1, minWidth: 0 },
});

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: Math.max(MIN_TARGET, 52), paddingVertical: 4, paddingHorizontal: 14 },
  pressed: { backgroundColor: semantic['surface-hover'] },
  name: { flex: 1, minWidth: 0 },
  value: { maxWidth: '45%' },
  danger: { color: colors.danger },
  group: { gap: 6 },
  box: { borderWidth: 1, borderColor: semantic['border-card'], borderRadius: radius.lg, overflow: 'hidden', backgroundColor: colors.surface },
  heading: { fontFamily: fonts.display, fontSize: 20, lineHeight: 26, color: colors.text, marginTop: space[4] },
});
