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

import { MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

/**
 * The consumer app's building blocks, one per class of design 01 (`Consumer Screen.dc.html`): `btn-primary` /
 * `btn-secondary` / `btn-ghost`, `field` + `input`, `tag-*`, the `opt` selectable card, the kicker. Colours, radii,
 * spacing and fonts come from @northline/mobile-kit (the tokens), never literals; every control is ≥ 48 dp.
 */

export const type = StyleSheet.create({
  /** Welcome's hero (h1 40px, tight). */
  hero: { fontFamily: fonts.heading, fontSize: 40, lineHeight: 42, letterSpacing: -1, color: colors.text },
  /** A screen's title (h2 28px). */
  title: { fontFamily: fonts.heading, fontSize: 28, lineHeight: 32, letterSpacing: -0.5, color: colors.text },
  /** A section (h3 20px). */
  section: { fontFamily: fonts.heading, fontSize: 20, lineHeight: 24, color: colors.text },
  body: { fontFamily: fonts.body, fontSize: 15, lineHeight: 22, color: colors.text },
  lead: { fontFamily: fonts.body, fontSize: 16, lineHeight: 23, color: colors.neutral700 },
  muted: { fontFamily: fonts.body, fontSize: 15, lineHeight: 22, color: colors.neutral700 },
  small: { fontFamily: fonts.body, fontSize: 13, lineHeight: 18, color: colors.neutral700 },
  strong: { fontFamily: fonts.bodyStrong },
  kicker: { fontFamily: fonts.body, fontSize: 13, letterSpacing: 1, textTransform: 'uppercase', color: colors.neutral700 },
  link: { fontFamily: fonts.body, color: colors.accent700, textDecorationLine: 'underline' },
  error: { fontFamily: fonts.body, fontSize: 13, lineHeight: 18, color: colors.accent2_700 },
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

export function Body({ children, tone = 'body', style }: { children: ReactNode; tone?: 'body' | 'lead' | 'muted' | 'small' | 'kicker'; style?: StyleProp<TextStyle> }) {
  return <Text style={[type[tone], style]}>{children}</Text>;
}

/** A text link standing on its own ("Edit", "Call me instead"): a 48 dp target around the words. */
export function Link({ label, onPress, hint, testID }: { label: string; onPress: () => void; hint?: string; testID?: string }) {
  return (
    <Pressable accessibilityRole="link" accessibilityLabel={label} accessibilityHint={hint} onPress={onPress} hitSlop={4} style={styles.link} testID={testID}>
      <Text style={type.link}>{label}</Text>
    </Pressable>
  );
}

export type ButtonTone = 'primary' | 'secondary' | 'ghost' | 'danger';

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
  /** The design's main call to action (50 dp, 16px). */
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
      {busy ? <ActivityIndicator color={tone === 'primary' ? colors.onAccent : colors.accent} /> : null}
      <Text style={[styles.buttonText, large && styles.buttonTextLarge, buttonText[tone]]}>{label}</Text>
    </Pressable>
  );
}

const buttonTone: Record<ButtonTone, ViewStyle> = {
  primary: { backgroundColor: colors.accent },
  secondary: { backgroundColor: colors.surface, borderColor: colors.divider, borderWidth: 1 },
  ghost: { backgroundColor: 'transparent' },
  danger: { backgroundColor: 'transparent' },
};
const pressedTone: Record<ButtonTone, ViewStyle> = {
  primary: { backgroundColor: colors.accent700 },
  secondary: { backgroundColor: colors.accent200 },
  ghost: { backgroundColor: colors.accent100 },
  danger: { backgroundColor: colors.accent2_100 },
};
const buttonText: Record<ButtonTone, TextStyle> = {
  primary: { color: colors.onAccent },
  secondary: { color: colors.text },
  ghost: { color: colors.accent },
  danger: { color: colors.accent2_700 },
};

export interface FieldProps extends Omit<TextInputProps, 'style'> {
  label: string;
  error?: string | null;
  hint?: string;
  testID?: string;
}

/** `field` + `input`: a label above, the error under it in rosehip (and announced), 46 dp, 16px so iOS doesn't zoom. */
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
        placeholderTextColor={colors.neutral600}
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

export type TagTone = 'accent' | 'accent2' | 'neutral';

export function Tag({ label, tone = 'neutral', small }: { label: string; tone?: TagTone; small?: boolean }) {
  return (
    <View style={[styles.tag, tagTone[tone], small && styles.tagSmall]}>
      <Text style={[styles.tagText, tagText[tone], small && styles.tagTextSmall]}>{label}</Text>
    </View>
  );
}

const tagTone: Record<TagTone, ViewStyle> = {
  accent: { backgroundColor: colors.accent100 },
  accent2: { backgroundColor: colors.accent2_100 },
  neutral: { backgroundColor: colors.neutral200 },
};
const tagText: Record<TagTone, TextStyle> = {
  accent: { color: colors.accent800 },
  accent2: { color: colors.accent2_800 },
  neutral: { color: colors.neutral900 },
};

/** The design's `opt`: a selectable card (accent border and accent-100 fill when chosen). */
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

/** A message about the whole form or step: rosehip for errors (announced), accent for news ("New code sent."). */
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
    paddingHorizontal: space[4],
    paddingVertical: space[2],
    borderRadius: radius.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space[2],
  },
  buttonLarge: { minHeight: 50 },
  buttonText: { fontFamily: fonts.bodyStrong, fontSize: 15, textAlign: 'center' },
  buttonTextLarge: { fontSize: 16 },
  disabled: { opacity: 0.45 },
  field: { gap: 6 },
  label: { fontFamily: fonts.body, fontSize: 13, color: colors.neutral800 },
  input: {
    minHeight: 48,
    paddingHorizontal: 14,
    paddingVertical: 10,
    fontFamily: fonts.body,
    fontSize: 16,
    color: colors.text,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.neutral300,
    borderRadius: radius.md,
  },
  inputError: { borderColor: colors.accent2 },
  checkRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 10, minHeight: MIN_TARGET, paddingVertical: space[2] },
  box: { width: 20, height: 20, marginTop: 1, borderRadius: radius.sm, borderWidth: 1.5, borderColor: colors.neutral400, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center' },
  boxOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  tick: { width: 8, height: 8, borderRadius: 2, backgroundColor: colors.onAccent },
  checkText: { flex: 1 },
  tag: { alignSelf: 'flex-start', borderRadius: radius.pill, paddingHorizontal: 10, paddingVertical: 3 },
  tagSmall: { paddingHorizontal: 7, paddingVertical: 1 },
  tagText: { fontFamily: fonts.bodyStrong, fontSize: 12 },
  tagTextSmall: { fontSize: 10 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    minHeight: MIN_TARGET,
    paddingHorizontal: 14,
    paddingVertical: space[3],
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.divider,
    backgroundColor: 'transparent',
  },
  optionOn: { borderColor: colors.accent, backgroundColor: colors.accent100 },
  optionPressed: { backgroundColor: colors.accent100 },
  optionOff: { opacity: 0.45 },
  optionText: { flex: 1, gap: 2 },
  optionTitle: { fontSize: 16 },
  optionDesc: { color: colors.neutral700 },
  notice: { borderRadius: radius.md, borderWidth: 1, padding: space[3] },
  noticeError: { borderColor: colors.accent2, backgroundColor: colors.accent2_100 },
  noticeErrorText: { color: colors.accent2_700 },
  noticeInfo: { borderColor: colors.accent200, backgroundColor: colors.accent100 },
  row: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  wrap: { flexWrap: 'wrap' },
  spacer: { flex: 1, minHeight: space[6] },
  link: { minHeight: MIN_TARGET, justifyContent: 'center' },
});
