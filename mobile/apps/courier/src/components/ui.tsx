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

import {
  Button as KitButton,
  CAP,
  Icon,
  IconButton,
  LINE,
  MIN_TARGET,
  Mark,
  SIZE,
  Segmented as KitSegmented,
  StatusTag,
  TYPE,
  colors,
  radius,
  semantic,
  space,
  textStyles,
  type ButtonVariant,
  type IconName,
} from '@northline/mobile-kit';

import { useI18n } from '../i18n';

/**
 * The courier app's building blocks, on the shared kit (S-169, tokens v2 · Plainsight; the consumer app uses the same
 * kit): a white top bar with the title in the sans and a 44 pt square Back on a sub-screen, the kit's buttons (3 pt
 * corners, never pills), flat white panels with the hairline edge, joined-box segmented controls, a sticky bottom
 * action bar. These keep the props the screens were written against. Colours, radii, spacing and type come from
 * @northline/mobile-kit, never literals. ≥ 44 pt targets (46 pt for the main action): couriers use the app outdoors,
 * often one-handed. S-179 (glanceable in sunlight): ink on white, 16 pt body, the key numbers at the 28 pt price role
 * ({@link Figure}), status tags drawn large, stop numbers in solid ink squares ({@link StepNumber}), progress as an ink
 * bar on the well ({@link Progress}) — no pictures that only decorate.
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
        <IconButton icon="caret-left" label={t('common.back')} onPress={onBack ?? (() => undefined)} boxed testID="header-back" />
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
  const bg = tone === 'done' ? semantic['status-success-bg'] : tone === 'muted' ? semantic.well : tone === 'dark' ? semantic['selected'] : semantic['status-info-bg'];
  const fg = tone === 'done' ? semantic['status-success-fg'] : tone === 'muted' ? colors.textMuted : tone === 'dark' ? semantic['selected-text'] : semantic['status-info-fg'];
  return (
    <View style={[styles.iconBadge, { width: size, height: size, backgroundColor: bg }]}>
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
/**
 * The older tone names on the kit's actions: accent = primary (the one violet action), ghost = the kit's ink tertiary
 * (`plain`: S-188 keeps the courier's sunlight rules — ink on white, violet only for the primary action), danger =
 * destructive (red).
 */
const VARIANT: Record<Tone, ButtonVariant> = { primary: 'primary', accent: 'primary', secondary: 'secondary', ghost: 'plain', danger: 'destructive' };

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
  /** The screen's main action (46 pt). */
  large?: boolean;
  testID?: string;
}) {
  return <KitButton label={label} onPress={onPress} variant={VARIANT[tone]} size={large ? 'lg' : 'md'} icon={icon} disabled={disabled} busy={busy} hint={hint} testID={testID} />;
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
const STATUS: Record<ChipTone, 'neutral' | 'info' | 'success' | 'warning'> = { neutral: 'neutral', accent: 'info', done: 'success', warn: 'warning' };

/** A status (a stop's state, the courier's status, the ID check): the kit's StatusTag, drawn large for a glance. */
export function Chip({ label, tone = 'neutral' }: { label: string; tone?: ChipTone }) {
  return <StatusTag label={label} tone={STATUS[tone]} large />;
}

/** A key number at a glance: the caps label over the figure at 28 pt (tabular), an optional line under it. */
export function Figure({ label, value, sub, testID }: { label: string; value: string; sub?: string; testID?: string }) {
  return (
    <View style={styles.figure} accessible accessibilityLabel={[label, value, sub].filter(Boolean).join(', ')} testID={testID}>
      <Text style={textStyles.caps} maxFontSizeMultiplier={CAP.control}>
        {label}
      </Text>
      <Text style={styles.figureValue}>{value}</Text>
      {sub ? <Text style={styles.figureSub}>{sub}</Text> : null}
    </View>
  );
}

/** A stop's number in a square: solid ink for the next one, the success tint with ✓ once done, outlined otherwise. */
export function StepNumber({ n, state }: { n: number; state: 'next' | 'done' | 'later' }) {
  return (
    <View style={[styles.step, state === 'next' && styles.stepNext, state === 'done' && styles.stepDone]} accessible={false}>
      {state === 'done' ? (
        <Icon name="check" size={TYPE.price} color={semantic['status-success-fg']} />
      ) : (
        <Text style={[styles.stepText, state === 'next' && styles.stepTextNext]} maxFontSizeMultiplier={CAP.badge}>
          {n}
        </Text>
      )}
    </View>
  );
}

/** Done of total as an ink bar on the well grey (decorative: the words beside it say it). */
export function Progress({ done, total }: { done: number; total: number }) {
  return (
    <View style={styles.track} accessible={false}>
      <View style={[styles.fill, { width: `${total ? Math.round((done / total) * 100) : 0}%` }]} />
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

/** Joined boxes switching what the screen shows (PIN · Signature · Photo, the language): the kit's Segmented as tabs. */
export function Segmented<T extends string>({ options, value, onChange, label }: { options: Array<{ value: T; label: string }>; value: T; onChange: (v: T) => void; label: string }) {
  return <KitSegmented options={options} value={value} onChange={onChange} label={label} role="tab" fill />;
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
      <Mark on={on} shape={role === 'radio' ? 'round' : 'box'} />
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
      <ActivityIndicator color={semantic.text} size="large" />
    </View>
  );
}

const bannerTone: Record<BannerTone, ViewStyle> = {
  info: { backgroundColor: semantic['status-info-bg'], borderLeftColor: semantic['status-info-fg'] },
  warn: { backgroundColor: semantic['status-warning-bg'], borderLeftColor: semantic['status-warning-fg'] },
  error: { backgroundColor: semantic['status-danger-bg'], borderLeftColor: semantic['status-danger-fg'] },
};

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surface },
  scroll: { flexGrow: 1 },
  screenBody: { flex: 1, padding: space[4], gap: space[3] },
  bar: { flexDirection: 'row', alignItems: 'center', gap: space[2], paddingHorizontal: space[3], paddingBottom: space[2], backgroundColor: colors.surface, borderBottomWidth: LINE.hair, borderBottomColor: colors.border },
  barTitle: { ...StyleSheet.flatten(textStyles.section), flex: 1, paddingLeft: space[1] },
  bottom: { gap: space[2], paddingTop: space[3], paddingHorizontal: space[4], borderTopWidth: LINE.hair, borderTopColor: colors.borderStrong, backgroundColor: colors.surface },
  iconBadge: { alignItems: 'center', justifyContent: 'center', borderRadius: radius.round },
  h1: textStyles.pageTitle,
  h2: textStyles.section,
  body: { ...StyleSheet.flatten(textStyles.body), fontSize: TYPE.sub, lineHeight: Math.round(TYPE.sub * 1.45), flexShrink: 1 },
  muted: { color: colors.textMuted },
  strong: { fontFamily: textStyles.section.fontFamily },
  errorText: { color: colors.danger },
  card: { minHeight: MIN_TARGET, backgroundColor: colors.surface, borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], padding: space[4], gap: space[2] },
  cardHighlight: { borderColor: semantic['selected'], borderWidth: LINE.bold, padding: space[4] - LINE.hair },
  cardFlush: { padding: 0, overflow: 'hidden' },
  cardPressed: { backgroundColor: semantic['surface-hover'] },
  figure: { flex: 1, minWidth: SIZE.rail * 2, gap: space[1] },
  figureValue: { ...StyleSheet.flatten(textStyles.priceLg), color: semantic.text },
  figureSub: { ...StyleSheet.flatten(textStyles.body), color: semantic['text-muted'] },
  step: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: radius.control, borderWidth: LINE.bold, borderColor: semantic.selected, alignItems: 'center', justifyContent: 'center', backgroundColor: semantic.surface },
  stepNext: { backgroundColor: semantic.selected },
  stepDone: { borderColor: semantic['status-success-bg'], backgroundColor: semantic['status-success-bg'] },
  stepText: { ...StyleSheet.flatten(textStyles.section), color: semantic.selected },
  stepTextNext: { color: semantic['selected-text'] },
  track: { height: space[2], borderRadius: radius.status, backgroundColor: semantic.well, overflow: 'hidden' },
  fill: { height: space[2], backgroundColor: semantic.selected },
  banner: { borderRadius: radius.panel, padding: space[4], gap: space[2], borderLeftWidth: LINE.bold + LINE.hair },
  row: { flexDirection: 'row', alignItems: 'center', gap: space[2], flexWrap: 'wrap' },
  check: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET + space[2], paddingHorizontal: space[3], paddingVertical: space[2], borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: colors.surface },
  checkOn: { borderColor: semantic['selected'], borderWidth: LINE.bold, paddingHorizontal: space[3] - LINE.hair, backgroundColor: semantic['selected-tint'] },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space[8] },
});
