import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { MIN_TARGET, semantic, space } from '../../theme';
import { Icon, type IconName } from '../Icon';
import { IconButton } from './Button';
import { CountBadge } from './Commerce';
import { useKit } from './KitProvider';
import { CAP, LINE, TYPE, textStyles } from './tokens';

/**
 * Native navigation patterns on Plainsight: a white header with a hairline (a 44 pt Back, the title in the sans, up
 * to two actions; `large` adds the page title under it, as iOS does) and the bottom tab bar (a Phosphor icon over a
 * short label; the current tab in ink with the filled icon and a 2 pt ink bar on top, like an underline tab turned
 * over; the cart's count in the round commerce badge).
 */

export function AppHeader({
  title,
  onBack,
  backLabel,
  actions,
  large,
  topInset = 0,
  testID,
}: {
  title: string;
  /** Shows Back. */
  onBack?: () => void;
  backLabel?: string;
  actions?: ReactNode;
  /** The page title drawn large under the bar (a tab's root screen). */
  large?: boolean;
  /** The status bar's height when the header draws under it. */
  topInset?: number;
  testID?: string;
}) {
  const { t } = useKit();
  return (
    <View style={[styles.header, { paddingTop: topInset }]} testID={testID}>
      <View style={styles.headerRow}>
        {onBack ? <IconButton icon="caret-left" label={backLabel ?? t('kit.back')} onPress={onBack} testID="header-back" /> : <View style={styles.headerPad} />}
        {large ? (
          <View style={styles.flex} />
        ) : (
          <Text accessibilityRole="header" numberOfLines={1} maxFontSizeMultiplier={CAP.control} style={[textStyles.sub, styles.flex]}>
            {title}
          </Text>
        )}
        {actions ? <View style={styles.actions}>{actions}</View> : null}
      </View>
      {large ? (
        <Text accessibilityRole="header" style={[textStyles.pageTitle, styles.largeTitle]}>
          {title}
        </Text>
      ) : null}
    </View>
  );
}

export interface TabItem<K extends string = string> {
  key: K;
  label: string;
  icon: IconName;
  /** A count on the icon (the cart's). */
  badge?: number;
  /** Read after the label ("Cart, 3 items"). */
  badgeLabel?: string;
  testID?: string;
}

export function BottomTabBar<K extends string>({ items, current, onSelect, bottomInset = 0, label, testID }: { items: TabItem<K>[]; current: K; onSelect: (k: K) => void; bottomInset?: number; label?: string; testID?: string }) {
  const { t } = useKit();
  return (
    <View accessibilityRole="tablist" accessibilityLabel={label ?? t('kit.tabs')} style={[styles.bar, { paddingBottom: Math.max(bottomInset, space[2]) }]} testID={testID}>
      {items.map((it) => {
        const on = it.key === current;
        const badge = it.badge ?? 0;
        return (
          <Pressable
            key={it.key}
            accessibilityRole="tab"
            accessibilityState={{ selected: on }}
            accessibilityLabel={badge > 0 ? `${it.label}, ${it.badgeLabel ?? t('kit.count', { n: badge })}` : it.label}
            onPress={() => onSelect(it.key)}
            style={({ pressed }) => [styles.tab, pressed && styles.pressed]}
            testID={it.testID ?? `tab-${it.key}`}
          >
            {on ? <View style={styles.marker} /> : null}
            <View>
              <Icon name={it.icon} size={TYPE.section + space[1] + LINE.hair} filled={on} color={on ? semantic['selected'] : semantic['text-muted']} />
              {badge > 0 ? (
                <View style={styles.badge}>
                  <CountBadge count={badge} />
                </View>
              ) : null}
            </View>
            <Text style={[styles.tabLabel, on && styles.tabLabelOn]} numberOfLines={1} maxFontSizeMultiplier={CAP.bar}>
              {it.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  pressed: { backgroundColor: semantic['surface-hover'] },
  header: { backgroundColor: semantic.surface, borderBottomWidth: LINE.hair, borderBottomColor: semantic.border },
  headerRow: { flexDirection: 'row', alignItems: 'center', gap: space[1], minHeight: MIN_TARGET + space[2], paddingHorizontal: space[1] },
  headerPad: { width: space[3] },
  actions: { flexDirection: 'row', alignItems: 'center' },
  largeTitle: { paddingHorizontal: space[4], paddingBottom: space[3] },
  bar: { flexDirection: 'row', paddingTop: space[1], paddingHorizontal: space[1], backgroundColor: semantic.surface, borderTopWidth: LINE.hair, borderTopColor: semantic['border-strong'] },
  tab: { flex: 1, minHeight: MIN_TARGET + space[2], alignItems: 'center', justifyContent: 'center', gap: LINE.bold + LINE.hair },
  marker: { position: 'absolute', top: -space[1], left: '25%', right: '25%', height: LINE.bold, backgroundColor: semantic['selected'] },
  tabLabel: { ...StyleSheet.flatten(textStyles.caps), textTransform: 'none', letterSpacing: 0, fontFamily: textStyles.cardTitle.fontFamily, color: semantic['text-muted'] },
  tabLabelOn: { fontFamily: textStyles.section.fontFamily, color: semantic['selected'] },
  badge: { position: 'absolute', top: -space[1], left: '60%' },
});
