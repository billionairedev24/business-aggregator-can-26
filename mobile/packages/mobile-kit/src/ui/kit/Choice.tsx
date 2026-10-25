import type { ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { MIN_TARGET, radius, semantic, space } from '../../theme';
import { Icon, type IconName } from '../Icon';
import { useKit } from './KitProvider';
import { CAP, LINE, SIZE, TYPE, slopFor, textStyles } from './tokens';

/**
 * Choices (Plainsight): every choice has a visible selected state, and the four shapes never look alike:
 * - {@link UnderlineTabs}: text with a 2 pt ink underline on the current one (and its count in an ink box);
 * - {@link Segmented}: joined boxes, the chosen one solid ink with a ✓ (a tip, a mode, "if out of stock" stacked);
 * - {@link FilterChip}: square-cornered 32 pt chips; applied = ink edge on the selected tint with ✓, removable ×;
 * - {@link ChoiceTiles}: separate boxes for one choice of many (time slots), the chosen one solid ink;
 * - {@link OptionCard}: a row with a radio mark (delivery windows, services), the chosen one an ink edge.
 */

export interface ChoiceOption<T extends string> {
  value: T;
  label: string;
  /** A count beside a tab's label ("To pack 3"). */
  count?: number;
  disabled?: boolean;
}

/**
 * The one tab style: underline tabs with counts, scrolling sideways when they don't fit. `chrome` (S-188): on the
 * consumer's violet department strip — white words, the current one bold over a 3 pt white bar (`header-indicator`),
 * the strip's lighter violet when pressed; no violet text on violet.
 */
export function UnderlineTabs<T extends string>({ options, value, onChange, label, chrome, testID }: { options: ChoiceOption<T>[]; value: T; onChange: (v: T) => void; label: string; chrome?: boolean; testID?: string }) {
  return (
    <View style={[styles.tabsRule, chrome && styles.tabsRuleChrome]} testID={testID}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} accessibilityRole="tablist" accessibilityLabel={label} contentContainerStyle={styles.tabs}>
        {options.map((o) => {
          const on = o.value === value;
          return (
            <Pressable
              key={o.value}
              accessibilityRole="tab"
              accessibilityState={{ selected: on, disabled: !!o.disabled }}
              accessibilityLabel={o.count !== undefined ? `${o.label}, ${o.count}` : o.label}
              disabled={o.disabled}
              onPress={() => onChange(o.value)}
              style={({ pressed }) => [styles.tab, on && styles.tabOn, chrome && styles.tabChrome, chrome && on && styles.tabOnChrome, pressed && (chrome ? styles.pressedChrome : styles.pressed)]}
              testID={testID ? `${testID}-${o.value}` : undefined}
            >
              <Text style={[styles.tabText, on && styles.tabTextOn, chrome && styles.tabTextChrome]} maxFontSizeMultiplier={CAP.control}>
                {o.label}
              </Text>
              {o.count !== undefined ? (
                <View style={[styles.count, on && styles.countOn]}>
                  <Text style={[styles.countText, on && styles.countTextOn]} maxFontSizeMultiplier={CAP.badge}>
                    {o.count}
                  </Text>
                </View>
              ) : null}
            </Pressable>
          );
        })}
      </ScrollView>
    </View>
  );
}

/**
 * Joined boxes for one choice: the chosen one solid ink with a ✓. `stack` lays them out as rows (long labels),
 * `fill` gives every box the same width. `role="tab"` when the boxes switch what the screen shows (a mode).
 */
export function Segmented<T extends string>({
  options,
  value,
  onChange,
  label,
  stack,
  fill,
  role = 'radio',
  testID,
}: {
  options: ChoiceOption<T>[];
  value: T | null;
  onChange: (v: T) => void;
  label: string;
  stack?: boolean;
  fill?: boolean;
  role?: 'radio' | 'tab';
  testID?: string;
}) {
  return (
    <View accessibilityRole={role === 'tab' ? 'tablist' : 'radiogroup'} accessibilityLabel={label} style={[styles.seg, stack && styles.segStack, fill && styles.segFill]} testID={testID}>
      {options.map((o, i) => {
        const on = o.value === value;
        return (
          <Pressable
            key={o.value}
            accessibilityRole={role}
            accessibilityLabel={o.label}
            accessibilityState={role === 'tab' ? { selected: on, disabled: !!o.disabled } : { checked: on, selected: on, disabled: !!o.disabled }}
            disabled={o.disabled}
            onPress={() => onChange(o.value)}
            style={({ pressed }) => [
              styles.segItem,
              (fill || !stack) && styles.segGrow,
              stack && styles.segItemStack,
              i > 0 && (stack ? styles.segDivTop : styles.segDivLeft),
              pressed && !on && styles.pressed,
              on && styles.segOn,
              o.disabled && styles.off,
            ]}
            testID={testID ? `${testID}-${o.value}` : undefined}
          >
            {on ? <Icon name="check" size={TYPE.label} color={semantic['selected-text']} /> : null}
            <Text style={[styles.segText, on && styles.segTextOn, stack && styles.segTextStack]} maxFontSizeMultiplier={CAP.control}>
              {o.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/**
 * A square filter chip (32 pt drawn, a 44 pt target): a toggle (`selected` + `onPress`, role checkbox), or an applied
 * filter that removes itself (`onRemove`: one button "✓ Tonight ×" named "Remove filter: Tonight").
 */
export function FilterChip({
  label,
  selected = false,
  onPress,
  onRemove,
  icon,
  count,
  role = 'checkbox',
  disabled,
  hint,
  testID,
}: {
  label: string;
  selected?: boolean;
  disabled?: boolean;
  hint?: string;
  onPress?: () => void;
  onRemove?: () => void;
  icon?: IconName;
  /** The number of matches it would show ("Vegan 12"). */
  count?: number;
  role?: 'checkbox' | 'radio' | 'button';
  testID?: string;
}) {
  const { t } = useKit();
  const applied = !!onRemove;
  const on = applied || selected;
  return (
    <Pressable
      accessibilityRole={applied ? 'button' : role}
      accessibilityLabel={applied ? t('kit.removeFilter', { label }) : count !== undefined ? `${label}, ${count}` : label}
      accessibilityHint={hint}
      accessibilityState={applied ? undefined : role === 'button' ? { selected: on, disabled: !!disabled } : { checked: on, selected: on, disabled: !!disabled }}
      disabled={disabled}
      onPress={applied ? onRemove : onPress}
      hitSlop={slopFor(SIZE['control-sm'])}
      style={({ pressed }) => [styles.chip, on && styles.chipOn, pressed && !on && styles.pressed, disabled && styles.off]}
      testID={testID}
    >
      {on ? <Icon name="check" size={TYPE.label} color={semantic['selected-ink']} /> : icon ? <Icon name={icon} size={TYPE.body} color={semantic.text} /> : null}
      <Text style={[styles.chipText, on && styles.chipTextOn, disabled && styles.chipTextOff]} maxFontSizeMultiplier={CAP.control}>
        {label}
      </Text>
      {count !== undefined ? <Text style={styles.chipCount}>{count}</Text> : null}
      {applied ? <Icon name="x" size={TYPE.label} color={semantic['selected-ink']} /> : null}
    </Pressable>
  );
}

/** Separate boxes, one choice (time slots, sizes): the chosen one solid ink; full slots disabled. Wraps to rows. */
export function ChoiceTiles<T extends string>({ options, value, onChange, label, testID }: { options: ChoiceOption<T>[]; value: T | null; onChange: (v: T) => void; label: string; testID?: string }) {
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel={label} style={styles.tiles} testID={testID}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <Pressable
            key={o.value}
            accessibilityRole="radio"
            accessibilityLabel={o.label}
            accessibilityState={{ checked: on, selected: on, disabled: !!o.disabled }}
            disabled={o.disabled}
            onPress={() => onChange(o.value)}
            style={({ pressed }) => [styles.tile, pressed && !on && styles.pressed, on && styles.tileOn, o.disabled && styles.tileOff]}
            testID={testID ? `${testID}-${o.value}` : undefined}
          >
            <Text style={[styles.tileText, on && styles.tileTextOn, o.disabled && styles.tileTextOff]} maxFontSizeMultiplier={CAP.control}>
              {o.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/**
 * One option of a list (a delivery window, a service, a payment method): a radio mark, the title and a note, something
 * on the right (a price). Chosen = an ink edge (2 pt) and a filled mark; the whole row is the target.
 */
export function OptionCard({
  title,
  description,
  right,
  selected,
  onPress,
  disabled,
  role = 'radio',
  label,
  children,
  style,
  testID,
}: {
  title: string;
  description?: string;
  right?: ReactNode;
  /** What a screen reader says (default "title, description"); S-178: with what `right` shows ("…, $2.99"). */
  label?: string;
  selected: boolean;
  onPress: () => void;
  disabled?: boolean;
  role?: 'radio' | 'checkbox' | 'button';
  children?: ReactNode;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}) {
  return (
    <Pressable
      accessibilityRole={role}
      accessibilityLabel={label ?? [title, description].filter(Boolean).join(', ')}
      accessibilityState={role === 'button' ? { selected, disabled: !!disabled } : { checked: selected, selected, disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [styles.option, selected && styles.optionOn, pressed && !selected && styles.pressed, disabled && styles.off, style]}
      testID={testID}
    >
      {role === 'button' ? null : <Mark on={selected} shape={role === 'checkbox' ? 'box' : 'round'} />}
      <View style={styles.optionText}>
        <Text style={textStyles.bodyStrong}>{title}</Text>
        {description ? <Text style={textStyles.meta}>{description}</Text> : null}
        {children}
      </View>
      {typeof right === 'string' ? <Text style={textStyles.bodyStrong}>{right}</Text> : right}
    </Pressable>
  );
}

/** A radio dot or a checkbox square, solid ink when on (decorative: the control around it carries the state). */
export function Mark({ on, shape = 'box' }: { on: boolean; shape?: 'box' | 'round' }) {
  return (
    <View style={[styles.mark, shape === 'round' && styles.markRound, on && styles.markOn]} accessible={false} importantForAccessibility="no-hide-descendants">
      {on ? shape === 'round' ? <View style={styles.dot} /> : <Icon name="check" size={TYPE.body} color={semantic['selected-text']} /> : null}
    </View>
  );
}

/** A checkbox row (44 pt): the label beside the box; `children` replaces the label's text (words with links). */
export function Checkbox({ checked, onChange, label, count, children, testID }: { checked: boolean; onChange: (v: boolean) => void; label: string; count?: number; children?: ReactNode; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityLabel={count !== undefined ? `${label}, ${count}` : label}
      accessibilityState={{ checked }}
      onPress={() => onChange(!checked)}
      style={({ pressed }) => [styles.check, pressed && styles.pressed]}
      testID={testID}
    >
      <Mark on={checked} />
      <View style={styles.optionText}>{children ?? <Text style={textStyles.body}>{label}</Text>}</View>
      {count !== undefined ? <Text style={textStyles.meta}>{count}</Text> : null}
    </Pressable>
  );
}

/** A switch row: a round track (`radius.round`, it is a circle), ink when on, the label on the left. */
export function Switch({ value, onChange, label, description, disabled, testID }: { value: boolean; onChange: (v: boolean) => void; label: string; description?: string; disabled?: boolean; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityHint={description}
      accessibilityState={{ checked: value, disabled: !!disabled }}
      disabled={disabled}
      onPress={() => onChange(!value)}
      style={({ pressed }) => [styles.check, pressed && styles.pressed, disabled && styles.off]}
      testID={testID}
    >
      <View style={styles.optionText}>
        <Text style={textStyles.body}>{label}</Text>
        {description ? <Text style={textStyles.meta}>{description}</Text> : null}
      </View>
      <View style={[styles.track, value && styles.trackOn]}>
        <View style={[styles.knob, value && styles.knobOn]} />
      </View>
    </Pressable>
  );
}

const TRACK_W = space[10] + space[1];
const TRACK_H = space[6] + space[1];
const KNOB = space[5] + LINE.bold;

const styles = StyleSheet.create({
  pressed: { backgroundColor: semantic['surface-hover'] },
  off: { opacity: 0.45 },
  // tabs
  tabsRule: { borderBottomWidth: LINE.hair, borderBottomColor: semantic['border-strong'] },
  tabs: { flexDirection: 'row', gap: space[1] },
  tab: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[2],
    minHeight: MIN_TARGET,
    paddingHorizontal: space[3],
    borderBottomWidth: LINE.bold,
    borderBottomColor: 'transparent',
    marginBottom: -LINE.hair,
  },
  tabOn: { borderBottomColor: semantic['selected'] },
  tabsRuleChrome: { borderBottomWidth: 0, backgroundColor: semantic['subheader-bg'] },
  tabChrome: { borderBottomWidth: LINE.bold + LINE.hair, marginBottom: 0 },
  tabOnChrome: { borderBottomColor: semantic['header-indicator'] },
  tabTextChrome: { color: semantic['subheader-text'] },
  pressedChrome: { backgroundColor: semantic['subheader-hover-bg'] },
  tabText: { ...StyleSheet.flatten(textStyles.body), color: semantic['text-muted'], fontFamily: textStyles.label.fontFamily },
  tabTextOn: { color: semantic.text, fontFamily: textStyles.section.fontFamily },
  count: { paddingHorizontal: space[1] + LINE.bold, borderRadius: radius.status, backgroundColor: semantic['neutral-bg'] },
  countOn: { backgroundColor: semantic['selected'] },
  countText: { ...StyleSheet.flatten(textStyles.metaStrong), color: semantic['neutral-fg'], fontVariant: ['tabular-nums'] },
  countTextOn: { color: semantic['selected-text'] },
  // segmented: joined boxes
  seg: { flexDirection: 'row', alignSelf: 'flex-start', maxWidth: '100%', borderWidth: LINE.hair, borderColor: semantic['border-control'], borderRadius: radius.control, overflow: 'hidden', backgroundColor: semantic.surface },
  segStack: { flexDirection: 'column', alignSelf: 'stretch' },
  segFill: { alignSelf: 'stretch' },
  segGrow: { flexGrow: 1, flexBasis: 0 },
  segItem: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space[1], minHeight: MIN_TARGET, paddingHorizontal: space[3], paddingVertical: space[1] },
  segItemStack: { justifyContent: 'flex-start', paddingHorizontal: space[3] },
  segDivLeft: { borderLeftWidth: LINE.hair, borderLeftColor: semantic['border-strong'] },
  segDivTop: { borderTopWidth: LINE.hair, borderTopColor: semantic['border-strong'] },
  segOn: { backgroundColor: semantic['selected'], borderColor: semantic['selected'] },
  segText: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.cardTitle.fontFamily, color: semantic['text-muted'], textAlign: 'center' },
  segTextStack: { textAlign: 'left', flexShrink: 1 },
  segTextOn: { color: semantic['selected-text'], fontFamily: textStyles.section.fontFamily },
  // chips: square, 32 drawn
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    gap: space[1] + LINE.bold,
    minHeight: SIZE['control-sm'],
    paddingHorizontal: space[3],
    borderWidth: LINE.hair,
    borderColor: semantic['action-neutral-border'],
    borderRadius: radius.control,
    backgroundColor: semantic.surface,
  },
  chipOn: { backgroundColor: semantic['selected-tint'], borderColor: semantic['selected'], borderWidth: LINE.bold, paddingHorizontal: space[3] - LINE.hair },
  chipText: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.body.fontFamily },
  chipTextOn: { fontFamily: textStyles.section.fontFamily, color: semantic['selected-ink'] },
  chipTextOff: { textDecorationLine: 'line-through' },
  chipCount: { ...StyleSheet.flatten(textStyles.meta) },
  // tiles
  tiles: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  tile: { minHeight: MIN_TARGET, minWidth: MIN_TARGET * 2, paddingHorizontal: space[3], alignItems: 'center', justifyContent: 'center', borderWidth: LINE.hair, borderColor: semantic['border-control'], borderRadius: radius.control, backgroundColor: semantic.surface },
  tileOn: { backgroundColor: semantic['selected'], borderColor: semantic['selected'] },
  tileOff: { backgroundColor: semantic['surface-muted'], borderColor: semantic.border },
  tileText: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.cardTitle.fontFamily },
  tileTextOn: { color: semantic['selected-text'], fontFamily: textStyles.section.fontFamily },
  tileTextOff: { color: semantic['text-subtle'], textDecorationLine: 'line-through' },
  // option card
  option: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: space[3],
    minHeight: MIN_TARGET,
    paddingHorizontal: space[3] + LINE.hair,
    paddingVertical: space[3],
    borderWidth: LINE.hair,
    borderColor: semantic['border-strong'],
    borderRadius: radius.panel,
    backgroundColor: semantic.surface,
  },
  optionOn: { borderColor: semantic['selected'], borderWidth: LINE.bold, paddingHorizontal: space[3], paddingVertical: space[3] - LINE.hair },
  optionText: { flex: 1, gap: LINE.bold },
  // marks
  mark: { width: space[5], height: space[5], marginTop: LINE.hair, borderRadius: radius.status, borderWidth: LINE.box, borderColor: semantic['border-control'], backgroundColor: semantic.surface, alignItems: 'center', justifyContent: 'center' },
  markRound: { borderRadius: radius.round },
  markOn: { backgroundColor: semantic['selected'], borderColor: semantic['selected'] },
  dot: { width: space[2], height: space[2], borderRadius: radius.round, backgroundColor: semantic['selected-text'] },
  check: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET, paddingVertical: space[2] },
  // switch: round (it is a circle)
  track: { width: TRACK_W, height: TRACK_H, borderRadius: radius.round, padding: LINE.bold, backgroundColor: semantic['border-control'], justifyContent: 'center' },
  trackOn: { backgroundColor: semantic['selected'] },
  knob: { width: KNOB, height: KNOB, borderRadius: radius.round, backgroundColor: semantic.surface },
  knobOn: { alignSelf: 'flex-end' },
});
