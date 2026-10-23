import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, Icon, LINE, MIN_TARGET, Switch, radius, semantic, space, textStyles, type IconName } from '@northline/mobile-kit';

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

/** A labelled on/off switch row: the kit's Switch (the whole row the target, ink when on). */
export function Toggle({ label, value, onChange, disabled, testID }: { label: string; value: boolean; onChange: (v: boolean) => void; disabled?: boolean; testID?: string }) {
  return <Switch label={label} value={value} onChange={onChange} disabled={disabled} testID={testID} />;
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
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space[1.5] },
  form: { gap: space[3] },
  actions: { gap: space[2], paddingTop: space[2] },
  sub: { gap: 2, flex: 1, minWidth: 0 },
});

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: Math.max(MIN_TARGET, 52), paddingVertical: space[1], paddingHorizontal: space[3.5] },
  pressed: { backgroundColor: semantic['surface-hover'] },
  name: { flex: 1, minWidth: 0 },
  value: { maxWidth: '45%' },
  danger: { color: semantic.danger },
  group: { gap: space[1.5] },
  box: { borderWidth: LINE.hair, borderColor: semantic['border-card'], borderRadius: radius.panel, overflow: 'hidden', backgroundColor: semantic.surface },
  heading: { ...StyleSheet.flatten(textStyles.section), marginTop: space[4] },
});
