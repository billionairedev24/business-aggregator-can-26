import { forwardRef, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextInputProps,
  type TextStyle,
  type ViewStyle,
} from 'react-native';

import { MIN_TARGET, colors, fonts, radius, semantic, shadow, space } from '@northline/mobile-kit';

/**
 * The consumer app's building blocks on the light, photo-first direction (owner 2026-10-19, palette Evergreen; the
 * consumer web's look): white surfaces, the palette's display face (Fraunces) for titles and sections at 600 and its
 * sans (DM Sans) for everything else, pill buttons (44 / 52 pt) in the brand colour, 48 pt inputs, soft cards with a
 * visible edge (`border-card`, 3:1) and a small shadow, linen (`surface-sunken`) for sunken panels. Colours, radii,
 * spacing and fonts come from @northline/mobile-kit (brand.json's palette), never literals; every control is ≥ 44 pt
 * (MIN_TARGET). Text sizes scale with the person's Dynamic Type / font size setting (React Native's default), capped
 * only where a one-line label would break its bar.
 */

export const type = StyleSheet.create({
  /** Welcome's and Home's hero (the display face). */
  hero: { fontFamily: fonts.display, fontSize: 36, lineHeight: 41, letterSpacing: -0.5, color: colors.text },
  /** A screen's title (the display face, 28). */
  title: { fontFamily: fonts.display, fontSize: 28, lineHeight: 34, letterSpacing: -0.3, color: colors.text },
  /** A section (the display face, 20). */
  section: { fontFamily: fonts.display, fontSize: 20, lineHeight: 26, color: colors.text },
  /** A card's or row's title (the sans, 16 / 600). */
  item: { fontFamily: fonts.semibold, fontSize: 16, lineHeight: 21, color: colors.text },
  body: { fontFamily: fonts.regular, fontSize: 15, lineHeight: 21, color: colors.text },
  lead: { fontFamily: fonts.regular, fontSize: 16, lineHeight: 23, color: colors.textMuted },
  muted: { fontFamily: fonts.regular, fontSize: 15, lineHeight: 21, color: colors.textMuted },
  small: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 18, color: colors.textMuted },
  strong: { fontFamily: fonts.bold },
  /** MA's group labels: 12 / 700 uppercase, .04em. */
  kicker: { fontFamily: fonts.bold, fontSize: 12, letterSpacing: 0.5, textTransform: 'uppercase', color: colors.textMuted },
  link: { fontFamily: fonts.semibold, color: colors.link, textDecorationLine: 'underline' },
  error: { fontFamily: fonts.medium, fontSize: 13, lineHeight: 18, color: colors.danger },
});

export function Title({ children, hero, testID }: { children: ReactNode; hero?: boolean; testID?: string }) {
  return (
    <Text accessibilityRole="header" style={hero ? type.hero : type.title} testID={testID}>
      {children}
    </Text>
  );
}

export function Section({ children }: { children: ReactNode }) {
  return (
    <Text accessibilityRole="header" style={type.section}>
      {children}
    </Text>
  );
}

export function Body({ children, tone = 'body', style, lines }: { children: ReactNode; tone?: 'body' | 'lead' | 'muted' | 'small' | 'kicker' | 'item'; style?: StyleProp<TextStyle>; lines?: number }) {
  return (
    <Text style={[type[tone], style]} numberOfLines={lines}>
      {children}
    </Text>
  );
}

/**
 * A soft card (light direction): white, radius 12, the 3:1 edge and a small shadow; `sunken` = a linen panel without
 * the edge (info boxes, the parts of a page that recede). With `onPress` it is one button named by `label`.
 */
export function Card({
  children,
  onPress,
  label,
  hint,
  sunken,
  flush,
  style,
  testID,
}: {
  children: ReactNode;
  onPress?: () => void;
  label?: string;
  hint?: string;
  sunken?: boolean;
  /** No inner padding (a card that opens on a picture). */
  flush?: boolean;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}) {
  const look = [styles.card, sunken && styles.cardSunken, flush && styles.cardFlush, style];
  if (!onPress) {
    return (
      <View style={look} testID={testID}>
        {children}
      </View>
    );
  }
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [...look, pressed && styles.cardPressed]}
    >
      {children}
    </Pressable>
  );
}

/** A text link standing on its own ("Edit", "Call me instead"): a 48 dp target around the words. */
export function Link({ label, onPress, hint, testID }: { label: string; onPress: () => void; hint?: string; testID?: string }) {
  return (
    <Pressable accessibilityRole="link" accessibilityLabel={label} accessibilityHint={hint} onPress={onPress} hitSlop={4} style={styles.link} testID={testID}>
      <Text style={type.link}>{label}</Text>
    </Pressable>
  );
}

/**
 * primary = the brand colour (Place order, Continue, Add to cart — the one main action of a screen); accent = the
 * palette's accent, for a rare highlight; secondary = white with an edge; dark = the brand-dark panel colour; ghost =
 * text only; danger = red text on white (Sign out).
 */
export type ButtonTone = 'primary' | 'accent' | 'secondary' | 'dark' | 'ghost' | 'danger';

export function Button({
  label,
  onPress,
  tone = 'primary',
  disabled,
  busy,
  hint,
  large,
  style,
  testID,
}: {
  label: string;
  onPress: () => void;
  tone?: ButtonTone;
  disabled?: boolean;
  busy?: boolean;
  hint?: string;
  /** The screen's main call to action (52 pt, 16 / 700 — MA's bottom action bar). */
  large?: boolean;
  style?: StyleProp<ViewStyle>;
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
      style={({ pressed }) => [styles.button, large && styles.buttonLarge, buttonTone[tone], pressed && pressedTone[tone], off && styles.disabled, style]}
    >
      {busy ? <ActivityIndicator color={buttonText[tone].color as string} /> : null}
      <Text style={[styles.buttonText, large && styles.buttonTextLarge, buttonText[tone]]}>{label}</Text>
    </Pressable>
  );
}

const buttonTone: Record<ButtonTone, ViewStyle> = {
  primary: { backgroundColor: semantic['action-primary'] },
  accent: { backgroundColor: semantic['action-accent'] },
  secondary: { backgroundColor: semantic['action-neutral'], borderColor: semantic['action-neutral-border'], borderWidth: 1 },
  dark: { backgroundColor: semantic['action-dark'] },
  ghost: { backgroundColor: 'transparent' },
  danger: { backgroundColor: semantic.surface, borderColor: semantic['border-control'], borderWidth: 1 },
};
const pressedTone: Record<ButtonTone, ViewStyle> = {
  primary: { backgroundColor: semantic['action-primary-press'] },
  accent: { backgroundColor: semantic['action-accent-hover'] },
  secondary: { backgroundColor: semantic['action-neutral-hover'] },
  dark: { backgroundColor: semantic['action-dark-hover'] },
  ghost: { backgroundColor: semantic['surface-hover'] },
  danger: { backgroundColor: semantic['danger-bg'] },
};
const buttonText: Record<ButtonTone, TextStyle> = {
  primary: { color: semantic['action-primary-text'] },
  accent: { color: semantic['action-accent-text'] },
  secondary: { color: semantic['action-neutral-text'] },
  dark: { color: semantic['action-dark-text'] },
  ghost: { color: semantic.link },
  danger: { color: semantic.danger },
};

export interface FieldProps extends Omit<TextInputProps, 'style'> {
  label: string;
  error?: string | null;
  hint?: string;
  testID?: string;
}

/** A label above (13 / 600), a 48 pt input (16 pt so iOS doesn't zoom), the error under it in the danger colour (and announced). */
export const Field = forwardRef<TextInput, FieldProps>(function Field({ label, error, hint, testID, ...input }, ref) {
  return (
    <View style={styles.field}>
      <Text style={styles.label} nativeID={testID ? `${testID}-label` : undefined}>
        {label}
      </Text>
      <TextInput
        ref={ref}
        accessibilityLabel={label}
        accessibilityHint={hint}
        aria-invalid={!!error}
        placeholderTextColor={colors.textSubtle}
        style={[styles.input, !!error && styles.inputError]}
        testID={testID}
        {...input}
      />
      {error ? (
        <Text accessibilityRole="alert" accessibilityLiveRegion="polite" style={type.error}>
          {error}
        </Text>
      ) : null}
    </View>
  );
});

/** A checkbox row (`radio` with a square dot): the label may hold links. */
export function Checkbox({ checked, onChange, label, children, error, testID }: { checked: boolean; onChange: (v: boolean) => void; label: string; children: ReactNode; error?: string | null; testID?: string }) {
  return (
    <View style={styles.field}>
      <Pressable
        accessibilityRole="checkbox"
        accessibilityLabel={label}
        accessibilityState={{ checked }}
        onPress={() => onChange(!checked)}
        style={styles.checkRow}
        testID={testID}
      >
        <View style={[styles.box, checked && styles.boxOn]}>{checked ? <View style={styles.tick} /> : null}</View>
        <View style={styles.checkText}>{children}</View>
      </Pressable>
      {error ? (
        <Text accessibilityRole="alert" style={type.error}>
          {error}
        </Text>
      ) : null}
    </View>
  );
}

/** accent = brand (primary tint), accent2 = attention (danger tint), neutral; success / warning for states. */
export type TagTone = 'accent' | 'accent2' | 'neutral' | 'success' | 'warning';

export function Tag({ label, tone = 'neutral', small }: { label: string; tone?: TagTone; small?: boolean }) {
  return (
    <View style={[styles.tag, tagTone[tone], small && styles.tagSmall]}>
      <Text style={[styles.tagText, tagText[tone], small && styles.tagTextSmall]}>{label}</Text>
    </View>
  );
}

const tagTone: Record<TagTone, ViewStyle> = {
  accent: { backgroundColor: semantic['badge-brand-bg'] },
  accent2: { backgroundColor: semantic['danger-bg'] },
  neutral: { backgroundColor: semantic['badge-neutral-bg'] },
  success: { backgroundColor: semantic['success-bg'] },
  warning: { backgroundColor: semantic['warning-bg'] },
};
const tagText: Record<TagTone, TextStyle> = {
  accent: { color: semantic['badge-brand-text'] },
  accent2: { color: semantic.danger },
  neutral: { color: semantic['badge-neutral-text'] },
  success: { color: semantic.success },
  warning: { color: semantic.warning },
};

/** A selectable card (MA's radio cards): a 2 pt border, the primary colour and the brand tint when chosen. */
export function Option({
  selected,
  onPress,
  title,
  description,
  tag,
  disabled,
  role = 'radio',
  style,
  testID,
}: {
  selected: boolean;
  onPress: () => void;
  title: string;
  description?: string;
  tag?: string;
  disabled?: boolean;
  role?: 'radio' | 'button';
  style?: StyleProp<ViewStyle>;
  testID?: string;
}) {
  return (
    <Pressable
      accessibilityRole={role}
      accessibilityLabel={[title, description, tag].filter(Boolean).join(', ')}
      accessibilityState={{ selected, checked: role === 'radio' ? selected : undefined, disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.option, selected && styles.optionOn, pressed && !selected && styles.optionPressed, disabled && styles.optionOff, style]}
    >
      <View style={styles.optionText}>
        <Text style={[type.body, type.strong, styles.optionTitle]}>{title}</Text>
        {description ? <Text style={[type.small, styles.optionDesc]}>{description}</Text> : null}
      </View>
      {tag ? <Tag label={tag} small /> : null}
    </Pressable>
  );
}

/** A message about the whole form or step: danger for errors (announced), the info tint for news ("New code sent."). */
export function Notice({ message, tone = 'error', testID }: { message: string; tone?: 'error' | 'info'; testID?: string }) {
  return (
    <View
      accessibilityRole={tone === 'error' ? 'alert' : 'text'}
      accessibilityLiveRegion="polite"
      style={[styles.notice, tone === 'error' ? styles.noticeError : styles.noticeInfo]}
      testID={testID ?? `notice-${tone}`}
    >
      <Text style={[type.body, tone === 'error' && styles.noticeErrorText]}>{message}</Text>
    </View>
  );
}

export function Row({ children, style, wrap }: { children: ReactNode; style?: StyleProp<ViewStyle>; wrap?: boolean }) {
  return <View style={[styles.row, wrap && styles.wrap, style]}>{children}</View>;
}

/** Pushes what follows to the bottom of a screen (the design's `margin-top:auto` action area). */
export function Spacer() {
  return <View style={styles.spacer} />;
}

const styles = StyleSheet.create({
  button: {
    minHeight: MIN_TARGET,
    minWidth: MIN_TARGET,
    paddingHorizontal: space[5],
    paddingVertical: space[2],
    borderRadius: radius.pill,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space[2],
  },
  buttonLarge: { minHeight: 52, paddingHorizontal: space[6] },
  buttonText: { fontFamily: fonts.bold, fontSize: 15, textAlign: 'center' },
  buttonTextLarge: { fontSize: 16 },
  disabled: { opacity: 0.45 },
  field: { gap: 6 },
  label: { fontFamily: fonts.semibold, fontSize: 14, color: colors.text },
  input: {
    minHeight: 48,
    paddingHorizontal: 14,
    paddingVertical: 10,
    fontFamily: fonts.regular,
    fontSize: 16,
    color: colors.text,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.borderControl,
    borderRadius: radius.lg,
  },
  inputError: { borderColor: semantic['danger-border'] },
  checkRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10, minHeight: MIN_TARGET, paddingVertical: space[2] },
  box: { width: 20, height: 20, marginTop: 1, borderRadius: radius.xs, borderWidth: 1.5, borderColor: colors.borderControl, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center' },
  boxOn: { backgroundColor: colors.actionPrimary, borderColor: colors.actionPrimary },
  tick: { width: 8, height: 8, borderRadius: 2, backgroundColor: colors.actionPrimaryText },
  checkText: { flex: 1 },
  tag: { alignSelf: 'flex-start', borderRadius: radius.pill, paddingHorizontal: 10, paddingVertical: 3 },
  tagSmall: { paddingHorizontal: 8, paddingVertical: 2 },
  tagText: { fontFamily: fonts.bold, fontSize: 12 },
  tagTextSmall: { fontSize: 11 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    minHeight: 52,
    paddingHorizontal: 14,
    paddingVertical: space[3],
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: semantic['border-card'],
    backgroundColor: colors.surface,
  },
  optionOn: { borderWidth: 2, paddingHorizontal: 13, borderColor: colors.actionPrimary, backgroundColor: semantic['surface-brand'] },
  optionPressed: { backgroundColor: semantic['surface-hover'] },
  optionOff: { opacity: 0.45 },
  optionText: { flex: 1, gap: 2 },
  optionTitle: { fontSize: 15 },
  optionDesc: { color: colors.textMuted },
  notice: { borderRadius: radius.lg, padding: space[3] },
  noticeError: { backgroundColor: semantic['danger-bg'] },
  noticeErrorText: { color: semantic.danger },
  noticeInfo: { backgroundColor: semantic['info-bg'] },
  row: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  wrap: { flexWrap: 'wrap' },
  card: { gap: space[2], padding: space[4], borderRadius: radius.lg, borderWidth: 1, borderColor: semantic['border-card'], backgroundColor: colors.surface, ...shadow.sm },
  cardSunken: { borderWidth: 0, backgroundColor: semantic['surface-sunken'], shadowOpacity: 0, elevation: 0 },
  cardFlush: { padding: 0, overflow: 'hidden' },
  cardPressed: { backgroundColor: semantic['surface-hover'] },
  spacer: { flex: 1, minHeight: space[6] },
  link: { minHeight: MIN_TARGET, justifyContent: 'center' },
});
