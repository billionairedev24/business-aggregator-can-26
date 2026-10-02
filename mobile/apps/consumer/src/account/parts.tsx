import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Switch, Text, View } from 'react-native';

import { MIN_TARGET, colors, fonts, space } from '@northline/mobile-kit';

import { type } from '../ui/primitives';

/**
 * Journey D's small pieces, drawn as design 01 draws the account: a settings row (name left, value and › right, 48 dp,
 * the whole row the target), a labelled on/off switch, a section heading (the design's h3 16px).
 */

export function SettingsRow({ name, value, onPress, hint, testID }: { name: string; value?: string | null; onPress: () => void; hint?: string; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={value ? `${name}, ${value}` : name}
      accessibilityHint={hint}
      onPress={onPress}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      testID={testID}
    >
      <Text style={[type.body, styles.name]}>{name}</Text>
      <Text style={type.small}>{value ? `${value} ›` : '›'}</Text>
    </Pressable>
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
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space[3], minHeight: MIN_TARGET, paddingVertical: 4 },
  pressed: { backgroundColor: colors.accent100 },
  name: { flex: 1, minWidth: 0 },
  heading: { fontFamily: fonts.bodyStrong, fontSize: 16, lineHeight: 22, color: colors.text, marginTop: space[4] },
});
