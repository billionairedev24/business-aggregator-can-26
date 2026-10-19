import type { ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type AccessibilityRole,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, MIN_TARGET, colors, fonts, radius, semantic, shadow, space, type IconName } from '@northline/mobile-kit';

import { useI18n } from '../i18n';

/**
 * The courier app's building blocks on the light direction (owner 2026-10-19, palette Evergreen; the consumer app's
 * language): a white top bar with the screen's title in the display face (and a round Back on a sub-screen), pill
 * buttons in the brand colour, soft cards with the 3:1 edge (`border-card`), linen for sunken panels, a sticky bottom
 * action bar. Colours, radii, spacing and fonts come from
 * @northline/mobile-kit (brand.json's palette), never literals. Large type and ≥ 44 pt targets (52 pt for the main
 * action): couriers use the app outdoors, often one-handed.
 */

export function Screen({
  children,
  scroll = true,
  title,
  back,
  onBack,
  action,
  footer,
  testID,
}: {
  children: ReactNode;
  scroll?: boolean;
  /** The top bar's title; none = no bar (the tab screens and sign-in draw their own top). */
  title?: string;
  /** A round Back button in the bar (sub-screens). */
  back?: boolean;
  onBack?: () => void;
  action?: ReactNode;
  /** The sticky bottom action bar (the screen's main action). */
  footer?: ReactNode;
  testID?: string;
}) {
  const body = <View style={styles.screenBody}>{children}</View>;
  return (
    <SafeAreaView style={styles.screen} edges={title !== undefined ? ['left', 'right'] : ['top', 'left', 'right']} testID={testID}>
      {title !== undefined ? <TopBar title={title} back={back} onBack={onBack} action={action} /> : null}
      {scroll ? (
        <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
          {body}
        </ScrollView>
      ) : (
        body
      )}
      {footer ? <BottomBar>{footer}</BottomBar> : null}
    </SafeAreaView>
  );
}

/** The top bar: white with a hairline, the round Back button on a sub-screen, the title (the display face), one action on the right. */
export function TopBar({ title, back, onBack, action }: { title: string; back?: boolean; onBack?: () => void; action?: ReactNode }) {
  const { t } = useI18n();
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.bar, { paddingTop: insets.top + space[2] }]}>
      {back ? (
        <Pressable accessibilityRole="button" accessibilityLabel={t('common.back')} onPress={onBack} hitSlop={4} style={styles.round} testID="header-back">
          <Icon name="caret-left" size={20} color={colors.text} />
        </Pressable>
      ) : null}
      <Text accessibilityRole="header" numberOfLines={2} maxFontSizeMultiplier={1.4} style={styles.barTitle}>
        {title}
      </Text>
      {action}
    </View>
  );
}

/** The sticky bottom action bar (52 pt main action above the home indicator). */
export function BottomBar({ children }: { children: ReactNode }) {
  const insets = useSafeAreaInsets();
  return <View style={[styles.bottom, { paddingBottom: Math.max(insets.bottom, space[3]) }]}>{children}</View>;
}

/** An icon in a tinted circle (the route's stop markers, the shift's phases). */
export function IconBadge({ name, tone = 'brand', size = 40 }: { name: IconName; tone?: 'brand' | 'done' | 'muted' | 'dark'; size?: number }) {
  const bg = tone === 'done' ? semantic['success-bg'] : tone === 'muted' ? semantic['surface-muted'] : tone === 'dark' ? semantic['action-primary'] : semantic['surface-brand'];
  const fg = tone === 'done' ? semantic.success : tone === 'muted' ? colors.textMuted : tone === 'dark' ? semantic['action-primary-text'] : colors.actionPrimary;
  return (
    <View style={[styles.iconBadge, { width: size, height: size, borderRadius: size / 2, backgroundColor: bg }]}>
      <Icon name={name} size={Math.round(size * 0.5)} color={fg} />
    </View>
  );
}

export function Heading({ children, level = 1 }: { children: ReactNode; level?: 1 | 2 }) {
  return (
    <Text accessibilityRole="header" style={level === 1 ? styles.h1 : styles.h2}>
      {children}
    </Text>
  );
}

export function Body({ children, muted, strong, style }: { children: ReactNode; muted?: boolean; strong?: boolean; style?: StyleProp<TextStyle> }) {
  return <Text style={[styles.body, muted && styles.muted, strong && styles.strong, style]}>{children}</Text>;
}

type Tone = 'primary' | 'accent' | 'secondary' | 'ghost' | 'danger';

export function Button({
  label,
  onPress,
  tone = 'primary',
  disabled,
  busy,
  hint,
  icon,
  large,
  testID,
}: {
  label: string;
  onPress: () => void;
  tone?: Tone;
  disabled?: boolean;
  busy?: boolean;
  hint?: string;
  icon?: IconName;
  /** The screen's main action (52 pt). */
  large?: boolean;
  testID?: string;
}) {
  const off = disabled || busy;
  const fg = buttonText[tone];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!off, busy: !!busy }}
      disabled={off}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.button, large && styles.buttonLarge, buttonTone[tone], pressed && pressedTone[tone], off && styles.disabled]}
    >
      {busy ? <ActivityIndicator color={fg} /> : icon ? <Icon name={icon} size={20} color={fg} /> : null}
      <Text style={[styles.buttonText, large && styles.buttonTextLarge, { color: fg }]}>{label}</Text>
    </Pressable>
  );
}

export function Card({ children, onPress, label, highlight, flush, testID }: { children: ReactNode; onPress?: () => void; label?: string; highlight?: boolean; /** no inner padding: a card that opens on a picture */ flush?: boolean; testID?: string }) {
  const style = [styles.card, highlight && styles.cardHighlight, flush && styles.cardFlush];
  if (!onPress) {
    return (
      <View style={style} testID={testID}>
        {children}
      </View>
    );
  }
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} testID={testID} style={({ pressed }) => [...style, pressed && styles.cardPressed]}>
      {children}
    </Pressable>
  );
}

type ChipTone = 'neutral' | 'accent' | 'done' | 'warn';

export function Chip({ label, tone = 'neutral' }: { label: string; tone?: ChipTone }) {
  return (
    <View style={[styles.chip, chipTone[tone]]}>
      <Text style={[styles.chipText, tone === 'accent' && styles.onAccent]}>{label}</Text>
    </View>
  );
}

type BannerTone = 'info' | 'warn' | 'error';

export function Banner({ children, tone = 'info', action, role = 'alert' }: { children: ReactNode; tone?: BannerTone; action?: ReactNode; role?: AccessibilityRole }) {
  return (
    <View accessibilityRole={role} accessibilityLiveRegion="polite" style={[styles.banner, bannerTone[tone]]}>
      <Text style={[styles.body, tone === 'error' && styles.errorText]}>{children}</Text>
      {action}
    </View>
  );
}

export function Segmented<T extends string>({ options, value, onChange, label }: { options: Array<{ value: T; label: string }>; value: T; onChange: (v: T) => void; label: string }) {
  return (
    <View accessibilityRole="tablist" accessibilityLabel={label} style={styles.segmented}>
      {options.map((o) => {
        const selected = o.value === value;
        return (
          <Pressable
            key={o.value}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            accessibilityLabel={o.label}
            onPress={() => onChange(o.value)}
            style={[styles.segment, selected && styles.segmentOn]}
          >
            <Text style={[styles.buttonText, selected ? styles.onAccent : styles.segmentText]}>{o.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/** A checkbox (or one radio of a group) with a 48 dp target: the ID check's confirmations and the refusal's reasons. */
export function Check({ label, on, onPress, role = 'checkbox', testID }: { label: string; on: boolean; onPress: () => void; role?: 'checkbox' | 'radio'; testID?: string }) {
  return (
    <Pressable
      accessibilityRole={role}
      accessibilityLabel={label}
      accessibilityState={role === 'radio' ? { selected: on, checked: on } : { checked: on }}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [styles.check, (on || pressed) && styles.checkOn]}
    >
      <View style={[styles.box, role === 'radio' && styles.roundBox, on && styles.boxOn]}>{on ? <Icon name="check" size={16} color={colors.actionPrimaryText} /> : null}</View>
      <Text style={styles.body}>{label}</Text>
    </Pressable>
  );
}

export function Row({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[styles.row, style]}>{children}</View>;
}

export function Gap({ size = 4 }: { size?: keyof typeof space }) {
  return <View style={{ height: space[size] }} />;
}

export function Loading({ label }: { label: string }) {
  return (
    <View style={styles.loading} accessibilityLabel={label} accessibilityRole="progressbar">
      <ActivityIndicator color={colors.actionPrimary} size="large" />
    </View>
  );
}

const buttonTone: Record<Tone, ViewStyle> = {
  primary: { backgroundColor: semantic['action-primary'] },
  accent: { backgroundColor: semantic['action-accent'] },
  secondary: { backgroundColor: semantic['action-neutral'], borderColor: semantic['action-neutral-border'], borderWidth: 1 },
  ghost: { backgroundColor: 'transparent' },
  danger: { backgroundColor: colors.surface, borderColor: colors.borderControl, borderWidth: 1 },
};
const pressedTone: Record<Tone, ViewStyle> = {
  primary: { backgroundColor: semantic['action-primary-press'] },
  accent: { backgroundColor: semantic['action-accent-hover'] },
  secondary: { backgroundColor: semantic['action-neutral-hover'] },
  ghost: { backgroundColor: semantic['surface-hover'] },
  danger: { backgroundColor: semantic['danger-bg'] },
};
const buttonText: Record<Tone, string> = {
  primary: semantic['action-primary-text'],
  accent: semantic['action-accent-text'],
  secondary: semantic['action-neutral-text'],
  ghost: semantic.link,
  danger: semantic.danger,
};
const chipTone: Record<ChipTone, ViewStyle> = {
  neutral: { backgroundColor: semantic['badge-neutral-bg'] },
  accent: { backgroundColor: semantic['action-primary'] },
  done: { backgroundColor: semantic['success-bg'] },
  warn: { backgroundColor: semantic['warning-bg'] },
};
const bannerTone: Record<BannerTone, ViewStyle> = {
  info: { backgroundColor: semantic['info-bg'] },
  warn: { backgroundColor: semantic['warning-bg'] },
  error: { backgroundColor: semantic['danger-bg'] },
};

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surface },
  scroll: { flexGrow: 1 },
  screenBody: { flex: 1, padding: space[4], gap: space[3] },
  bar: { flexDirection: 'row', alignItems: 'center', gap: space[3], paddingHorizontal: space[4], paddingBottom: space[3], backgroundColor: colors.surface, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  barTitle: { flex: 1, fontFamily: fonts.display, fontSize: 22, lineHeight: 28, color: colors.text },
  round: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: MIN_TARGET / 2, borderWidth: 1, borderColor: colors.borderStrong, backgroundColor: colors.surface, alignItems: 'center', justifyContent: 'center' },
  bottom: { gap: space[2], paddingTop: space[3], paddingHorizontal: space[4], borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.borderStrong, backgroundColor: colors.surface },
  iconBadge: { alignItems: 'center', justifyContent: 'center' },
  h1: { fontFamily: fonts.display, fontSize: 30, lineHeight: 36, color: colors.text },
  h2: { fontFamily: fonts.display, fontSize: 20, lineHeight: 26, color: colors.text },
  body: { fontFamily: fonts.regular, fontSize: 16, lineHeight: 23, color: colors.text, flexShrink: 1 },
  muted: { color: colors.textMuted },
  strong: { fontFamily: fonts.bold },
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
  buttonLarge: { minHeight: 52 },
  buttonText: { fontFamily: fonts.bold, fontSize: 16 },
  buttonTextLarge: { fontSize: 17 },
  onAccent: { color: colors.actionPrimaryText },
  segmentText: { color: colors.text },
  errorText: { color: colors.danger },
  disabled: { opacity: 0.45 },
  card: { minHeight: MIN_TARGET, backgroundColor: colors.surface, borderRadius: radius.lg, borderWidth: 1, borderColor: semantic['border-card'], padding: space[4], gap: space[2], ...shadow.sm },
  cardHighlight: { borderColor: colors.actionPrimary, borderWidth: 2 },
  cardFlush: { padding: 0, overflow: 'hidden' },
  cardPressed: { backgroundColor: semantic['surface-hover'] },
  chip: { alignSelf: 'flex-start', borderRadius: radius.pill, paddingHorizontal: space[3], paddingVertical: space[1] },
  chipText: { fontFamily: fonts.bold, fontSize: 13, color: colors.text },
  banner: { borderRadius: radius.lg, padding: space[4], gap: space[2] },
  segmented: { flexDirection: 'row', backgroundColor: semantic['surface-muted'], borderRadius: radius.pill, padding: space[1], gap: space[1] },
  segment: { flex: 1, minHeight: MIN_TARGET, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center' },
  segmentOn: { backgroundColor: colors.actionPrimary },
  row: { flexDirection: 'row', alignItems: 'center', gap: space[2], flexWrap: 'wrap' },
  check: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: 52, paddingHorizontal: space[3], paddingVertical: space[2], borderRadius: radius.lg, borderWidth: 1, borderColor: semantic['border-card'], backgroundColor: colors.surface },
  checkOn: { borderColor: colors.actionPrimary, backgroundColor: semantic['surface-brand'] },
  box: { width: 24, height: 24, borderRadius: radius.xs, borderWidth: 2, borderColor: colors.borderControl, alignItems: 'center', justifyContent: 'center' },
  roundBox: { borderRadius: radius.pill },
  boxOn: { backgroundColor: colors.actionPrimary, borderColor: colors.actionPrimary },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space[8] },
});
