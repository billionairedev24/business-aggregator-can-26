import { forwardRef, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, type StyleProp, type TextInput, type TextStyle, type ViewStyle } from 'react-native';

import {
  Button as KitButton,
  CAP,
  LINE,
  MIN_TARGET,
  Mark,
  OptionCard,
  TYPE,
  TextField,
  radius,
  semantic,
  space,
  textStyles,
  type ButtonVariant,
  type TextFieldProps,
} from '@northline/mobile-kit';

/**
 * The consumer app's building blocks, on the shared kit (S-169, tokens v2 · Plainsight): these keep the props the
 * screens were written against and draw with @northline/mobile-kit's components and text roles, so every screen moved
 * to IBM Plex Sans, 3 / 4 pt corners, the action blue, ink selection and flat white panels at once. Page layouts are
 * S-178. Colours, radii, spacing and type come from the kit (no literals); every control is ≥ 44 pt (MIN_TARGET);
 * text scales with Dynamic Type.
 */

export const type = StyleSheet.create({
  /** Welcome's and Home's hero: the page title role. */
  hero: textStyles.pageTitle,
  /** A screen's title (24 / 700). */
  title: textStyles.pageTitle,
  /** A section (18 / 700). */
  section: textStyles.section,
  /** A card's or row's title (16 / 600). */
  item: textStyles.sub,
  body: textStyles.body,
  lead: { ...StyleSheet.flatten(textStyles.body), fontSize: TYPE.sub, lineHeight: Math.round(TYPE.sub * 1.45), color: semantic['text-muted'] },
  muted: { ...StyleSheet.flatten(textStyles.body), color: semantic['text-muted'] },
  small: textStyles.meta,
  strong: { fontFamily: textStyles.section.fontFamily },
  /** Group labels: 11 / 700 uppercase. */
  kicker: textStyles.caps,
  link: { ...StyleSheet.flatten(textStyles.link), textDecorationLine: 'underline' },
  error: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.cardTitle.fontFamily, color: semantic.danger },
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
 * A flat panel (Plainsight): white, 4 pt corners, the hairline edge, no shadow; `sunken` = the well grey without the
 * edge (info boxes, the parts of a page that recede). With `onPress` it is one button named by `label`.
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

/** A text link standing on its own ("Edit", "Call me instead"): a 44 pt target around the words. */
export function Link({ label, onPress, hint, testID }: { label: string; onPress: () => void; hint?: string; testID?: string }) {
  return (
    <Pressable accessibilityRole="link" accessibilityLabel={label} accessibilityHint={hint} onPress={onPress} hitSlop={4} style={styles.link} testID={testID}>
      <Text style={type.link}>{label}</Text>
    </Pressable>
  );
}

/**
 * The older tone names on the kit's four actions: primary (and accent, which is the same action blue in Plainsight),
 * secondary, ghost = tertiary (a link), danger = destructive (red: cancel, remove, delete), dark.
 */
export type ButtonTone = 'primary' | 'accent' | 'secondary' | 'dark' | 'ghost' | 'danger';
const VARIANT: Record<ButtonTone, ButtonVariant> = { primary: 'primary', accent: 'primary', secondary: 'secondary', dark: 'dark', ghost: 'tertiary', danger: 'destructive' };

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
  /** The screen's main call to action (46 pt, a sticky bar's). */
  large?: boolean;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}) {
  return <KitButton label={label} onPress={onPress} variant={VARIANT[tone]} size={large ? 'lg' : 'md'} disabled={disabled} busy={busy} hint={hint} style={style} testID={testID} />;
}

export type FieldProps = TextFieldProps;

/** The kit's labelled field: label above, a 44 pt input (16 pt text), the error under it (announced). */
export const Field = forwardRef<TextInput, FieldProps>(function Field(props, ref) {
  return <TextField ref={ref} {...props} />;
});

/** A checkbox row (the label may hold links): the kit's square mark, solid ink when checked. */
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
        <Mark on={checked} />
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

/** accent = information (the blue tint), accent2 = attention (danger), neutral; success / warning for states. */
export type TagTone = 'accent' | 'accent2' | 'neutral' | 'success' | 'warning';
const TAG: Record<TagTone, 'info' | 'danger' | 'neutral' | 'success' | 'warning'> = { accent: 'info', accent2: 'danger', neutral: 'neutral', success: 'success', warning: 'warning' };

/** A square tag (2 pt corners) in a status's tint and colour. */
export function Tag({ label, tone = 'neutral', small }: { label: string; tone?: TagTone; small?: boolean }) {
  const s = TAG[tone];
  return (
    <View style={[styles.tag, small && styles.tagSmall, { backgroundColor: semantic[`status-${s}-bg`] }]}>
      <Text style={[styles.tagText, small && styles.tagTextSmall, { color: semantic[`status-${s}-fg`] }]} maxFontSizeMultiplier={CAP.bar}>
        {label}
      </Text>
    </View>
  );
}

/** A selectable row (radio cards): the kit's OptionCard — a radio mark, an ink edge when chosen. */
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
    <OptionCard
      title={title}
      description={description}
      right={tag ? <Tag label={tag} small /> : undefined}
      selected={selected}
      onPress={onPress}
      disabled={disabled}
      role={role}
      style={style}
      testID={testID}
    />
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
  field: { gap: space[1] + LINE.bold },
  checkRow: { flexDirection: 'row', alignItems: 'flex-start', gap: space[3], minHeight: MIN_TARGET, paddingVertical: space[2] },
  checkText: { flex: 1 },
  tag: { alignSelf: 'flex-start', borderRadius: radius.status, paddingHorizontal: space[2], paddingVertical: LINE.bold },
  tagSmall: { paddingHorizontal: space[1] + LINE.bold, paddingVertical: LINE.hair },
  tagText: { ...StyleSheet.flatten(textStyles.metaStrong), fontFamily: textStyles.section.fontFamily },
  tagTextSmall: { fontSize: TYPE.caps },
  notice: { borderRadius: radius.panel, padding: space[3], borderLeftWidth: LINE.bold + LINE.hair },
  noticeError: { backgroundColor: semantic['status-danger-bg'], borderLeftColor: semantic['status-danger-fg'] },
  noticeErrorText: { color: semantic.danger },
  noticeInfo: { backgroundColor: semantic['status-info-bg'], borderLeftColor: semantic['status-info-fg'] },
  row: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  wrap: { flexWrap: 'wrap' },
  card: { gap: space[2], padding: space[4], borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: semantic.surface },
  cardSunken: { borderWidth: 0, backgroundColor: semantic.well },
  cardFlush: { padding: 0, overflow: 'hidden' },
  cardPressed: { backgroundColor: semantic['surface-hover'] },
  spacer: { flex: 1, minHeight: space[6] },
  link: { minHeight: MIN_TARGET, justifyContent: 'center' },
});
