import { ActivityIndicator, Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { MIN_TARGET, radius, semantic, space } from '../../theme';
import { Icon, type IconName } from '../Icon';
import { CAP, LINE, SIZE, TYPE, slopFor, textStyles } from './tokens';

/**
 * The four actions of Plainsight (revamp-epic § Locked direction) and the dark one:
 * - `primary`: the action blue, solid (Add to cart, Book, Place order: the one main action of a screen);
 * - `secondary`: white with the 3:1-ish neutral edge (Buy now, Edit);
 * - `tertiary`: the link colour, no box (See details, Call me instead);
 * - `destructive`: the danger red, solid (Cancel order, Delete, Refuse; every action that cannot be undone);
 * - `dark`: solid ink, rare (the search button, a bar over a photo).
 * 3 pt corners (`radius.control`), never a pill: buttons, tabs, segments and chips must never look alike. Sizes: `sm`
 * drawn 32 pt with a hit slop to 44, `md` 44, `lg` 46 (a sticky bottom bar's action). The label wraps under large
 * Dynamic Type instead of being cut.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'destructive' | 'dark';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps {
  label: string;
  onPress: () => void;
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: IconName;
  disabled?: boolean;
  busy?: boolean;
  hint?: string;
  /** Full width (a bottom bar, a card's Add to cart). */
  block?: boolean;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}

const HEIGHT: Record<ButtonSize, number> = { sm: SIZE['control-sm'], md: MIN_TARGET, lg: SIZE['control-lg'] };

export function Button({ label, onPress, variant = 'primary', size = 'md', icon, disabled, busy, hint, block, style, testID }: ButtonProps) {
  const off = !!(disabled || busy);
  const fg = FG[variant];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: off, busy: !!busy }}
      disabled={off}
      onPress={onPress}
      hitSlop={slopFor(HEIGHT[size])}
      testID={testID}
      style={({ pressed }) => [styles.base, SIZES[size], BG[variant], pressed && !off && PRESSED[variant], off && styles.off, block && styles.block, style]}
    >
      {busy ? <ActivityIndicator color={fg} size="small" /> : icon ? <Icon name={icon} size={size === 'sm' ? TYPE.sub : TYPE.section} color={fg} /> : null}
      <Text style={[styles.label, size === 'sm' && styles.labelSm, size === 'lg' && styles.labelLg, { color: fg }, variant === 'tertiary' && styles.tertiaryLabel]} maxFontSizeMultiplier={CAP.control}>
        {label}
      </Text>
    </Pressable>
  );
}

/**
 * A 44 pt square icon button (Back, Share, Close, the bell): no box at rest, the hover grey when pressed; `boxed`
 * draws the control edge (over a busy background), `onPhoto` a white square with a shadow so it reads on any picture.
 */
export function IconButton({
  icon,
  label,
  onPress,
  hint,
  filled,
  color,
  boxed,
  onPhoto,
  testID,
}: {
  icon: IconName;
  label: string;
  onPress: () => void;
  hint?: string;
  filled?: boolean;
  color?: string;
  boxed?: boolean;
  onPhoto?: boolean;
  testID?: string;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.icon, boxed && styles.iconBoxed, onPhoto && styles.iconOnPhoto, pressed && styles.iconPressed]}
    >
      <View pointerEvents="none">
        <Icon name={icon} size={TYPE.section + space[1]} filled={filled} color={color ?? semantic.text} />
      </View>
    </Pressable>
  );
}

const BG: Record<ButtonVariant, ViewStyle> = {
  primary: { backgroundColor: semantic['action-primary'], borderColor: semantic['action-primary'] },
  secondary: { backgroundColor: semantic['action-neutral'], borderColor: semantic['action-neutral-border'] },
  tertiary: { backgroundColor: 'transparent', borderColor: 'transparent', paddingHorizontal: space[2] },
  destructive: { backgroundColor: semantic['action-danger'], borderColor: semantic['action-danger'] },
  dark: { backgroundColor: semantic['action-dark'], borderColor: semantic['action-dark'] },
};
const PRESSED: Record<ButtonVariant, ViewStyle> = {
  primary: { backgroundColor: semantic['action-primary-press'], borderColor: semantic['action-primary-press'] },
  secondary: { backgroundColor: semantic['action-neutral-hover'] },
  tertiary: { backgroundColor: semantic['surface-hover'] },
  destructive: { backgroundColor: semantic['action-danger-hover'], borderColor: semantic['action-danger-hover'] },
  dark: { backgroundColor: semantic['action-dark-hover'], borderColor: semantic['action-dark-hover'] },
};
const FG: Record<ButtonVariant, string> = {
  primary: semantic['action-primary-text'],
  secondary: semantic['action-neutral-text'],
  tertiary: semantic.link,
  destructive: semantic['action-danger-text'],
  dark: semantic['action-dark-text'],
};
const SIZES: Record<ButtonSize, ViewStyle> = {
  sm: { minHeight: HEIGHT.sm, paddingHorizontal: space[3], gap: space[1] },
  md: { minHeight: HEIGHT.md, paddingHorizontal: space[4] },
  lg: { minHeight: HEIGHT.lg, paddingHorizontal: space[5] },
};

const styles = StyleSheet.create({
  base: {
    minWidth: MIN_TARGET,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space[2],
    paddingVertical: space[1],
    borderRadius: radius.control,
    borderWidth: LINE.hair,
  },
  block: { alignSelf: 'stretch' },
  off: { opacity: 0.45 },
  label: { ...StyleSheet.flatten(textStyles.bodyStrong), textAlign: 'center', flexShrink: 1 },
  labelSm: { fontSize: TYPE.label, lineHeight: Math.round(TYPE.label * 1.25) },
  labelLg: { fontSize: TYPE['price-sm'], lineHeight: Math.round(TYPE['price-sm'] * 1.25) },
  tertiaryLabel: { textDecorationLine: 'none' },
  icon: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: radius.control, alignItems: 'center', justifyContent: 'center' },
  iconBoxed: { borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: semantic.surface },
  iconOnPhoto: { backgroundColor: semantic.surface, borderWidth: 0, shadowColor: semantic.text, shadowOpacity: 0.12, shadowRadius: space[1], shadowOffset: { width: 0, height: 1 }, elevation: 2 },
  iconPressed: { backgroundColor: semantic['surface-hover'] },
});
