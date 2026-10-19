import { router } from 'expo-router';
import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View, type RefreshControlProps } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, MIN_TARGET, colors, fonts, radius, semantic, shadow, space, type IconName } from '@northline/mobile-kit';

import { useI18n } from '../i18n';
import { OfflineBanner } from './states';

/** The side padding of every screen (16 pt). */
export const GUTTER = space[4];

/**
 * A screen on the light direction: white, the compact sub-page header (a 44 pt round Back button and the title on one
 * line) when `title` is given, otherwise `top` (a tab's own top bar — {@link TopBar}) or a thin band; 16 pt side
 * padding; content that scrolls; an offline banner while the phone has no connection. `footer` is the sticky bottom
 * action bar: it stays above the home indicator and rises with the keyboard. `action` puts one more round button on
 * the right of the header.
 */
export function Screen({
  title,
  back = true,
  onBack,
  action,
  top,
  children,
  footer,
  scroll = true,
  padded = true,
  refreshControl,
  testID,
}: {
  /** The header's title; no title = no header. */
  title?: string;
  /** Show the Back button (default when there is a title). */
  back?: boolean;
  onBack?: () => void;
  action?: ReactNode;
  /** A tab's own top (the place and the search): stays put while the content scrolls. */
  top?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  scroll?: boolean;
  padded?: boolean;
  refreshControl?: React.ReactElement<RefreshControlProps>;
  testID?: string;
}) {
  const body = <View style={[styles.body, padded && styles.padded]}>{children}</View>;
  return (
    <SafeAreaView style={styles.screen} edges={['top', 'left', 'right']} testID={testID}>
      {title !== undefined ? <Header title={title} back={back} onBack={onBack} action={action} /> : (top ?? <View style={styles.band} />)}
      <OfflineBanner />
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        {scroll ? (
          <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled" refreshControl={refreshControl}>
            {body}
          </ScrollView>
        ) : (
          body
        )}
        {footer ? <BottomBar>{footer}</BottomBar> : <SafeBottom />}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

/** The sticky bottom action bar: white with a hairline and a soft lift, the home indicator's room below. */
export function BottomBar({ children, row }: { children: ReactNode; row?: boolean }) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.bar, row && styles.barRow, { paddingBottom: Math.max(insets.bottom, space[3]) }]} testID="bottom-bar">
      {children}
    </View>
  );
}

function SafeBottom() {
  const insets = useSafeAreaInsets();
  return <View style={{ height: insets.bottom }} />;
}

/** A 44 pt round icon button (Back, Share, Save, the bell): white with the card edge, or plain on a picture (`onPhoto`). */
export function RoundButton({
  icon,
  label,
  onPress,
  hint,
  filled,
  color,
  onPhoto,
  testID,
}: {
  icon: IconName;
  label: string;
  onPress: () => void;
  hint?: string;
  filled?: boolean;
  color?: string;
  /** Over a picture: a white disc with a shadow so it reads on any photo. */
  onPhoto?: boolean;
  testID?: string;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      onPress={onPress}
      hitSlop={4}
      style={({ pressed }) => [styles.round, onPhoto && styles.roundOnPhoto, pressed && styles.roundPressed]}
      testID={testID}
    >
      <Icon name={icon} size={20} filled={filled} color={color} />
    </Pressable>
  );
}

/** The compact sub-page header: the round Back button, the title (the display face, one line), an optional action. */
export function Header({ title, back = true, onBack, action }: { title: string; back?: boolean; onBack?: () => void; action?: ReactNode }) {
  const { t } = useI18n();
  const goBack = onBack ?? (() => (router.canGoBack() ? router.back() : router.replace('/home')));
  return (
    <View style={styles.header}>
      {back ? <RoundButton icon="caret-left" label={t('common.back')} onPress={goBack} testID="header-back" /> : null}
      <Text accessibilityRole="header" numberOfLines={1} maxFontSizeMultiplier={1.4} style={[styles.headerTitle, !back && styles.headerTitleAlone]}>
        {title}
      </Text>
      {action}
    </View>
  );
}

/**
 * A tab's top bar (light direction; the web header's phone row): the place — "Deliver to" over the place's name on
 * one line with an ellipsis, opening the location sheet — and up to two round buttons on the right, then the one
 * search pill. Never asks for the phone's position (that happens only in the sheet, on "Use my current location").
 */
export function TopBar({
  lead,
  kicker,
  place,
  onPlace,
  placeLabel,
  placeHint,
  actions,
  search,
}: {
  /** Before the place (Home: the brand's mark). */
  lead?: ReactNode;
  kicker: string;
  /** The place's name; none yet = the "set your location" words in its stead. */
  place: string;
  onPlace: () => void;
  placeLabel: string;
  placeHint?: string;
  actions?: ReactNode;
  search?: ReactNode;
}) {
  return (
    <View style={styles.top}>
      <View style={styles.topRow}>
        {lead}
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={placeLabel}
          accessibilityHint={placeHint}
          onPress={onPlace}
          style={({ pressed }) => [styles.place, pressed && styles.placePressed]}
          testID="home-place"
        >
          <Icon name="map-pin" size={22} filled color={semantic['action-primary']} />
          <View style={styles.placeText}>
            <Text style={styles.placeKicker} numberOfLines={1} maxFontSizeMultiplier={1.4}>
              {kicker}
            </Text>
            <View style={styles.placeLine}>
              <Text style={styles.placeName} numberOfLines={1} maxFontSizeMultiplier={1.4}>
                {place}
              </Text>
              <Icon name="caret-down" size={14} />
            </View>
          </View>
        </Pressable>
        {actions}
      </View>
      {search}
    </View>
  );
}

/** The one search pill (every tab that searches): a magnifier, the hint, and the round brand-coloured search button. */
export function SearchPill({ label, onPress, testID }: { label: string; onPress: () => void; testID?: string }) {
  return (
    <Pressable accessibilityRole="search" accessibilityLabel={label} onPress={onPress} style={({ pressed }) => [styles.search, pressed && styles.searchPressed]} testID={testID}>
      <Text style={styles.searchText} numberOfLines={1}>
        {label}
      </Text>
      <View style={styles.searchGo}>
        <Icon name="magnifying-glass" size={18} color={semantic['action-primary-text']} />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surface },
  flex: { flex: 1 },
  band: { height: space[2] },
  scroll: { flexGrow: 1 },
  body: { flexGrow: 1, gap: space[4], paddingTop: space[3], paddingBottom: space[8] },
  padded: { paddingHorizontal: GUTTER },
  bar: {
    gap: space[2],
    paddingTop: space[3],
    paddingHorizontal: GUTTER,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.borderStrong,
    backgroundColor: colors.surface,
    ...shadow.md,
    shadowOffset: { width: 0, height: -2 },
    shadowOpacity: 0.06,
  },
  barRow: { flexDirection: 'row', alignItems: 'center' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[3],
    paddingHorizontal: GUTTER,
    paddingVertical: space[2],
    minHeight: MIN_TARGET + space[4],
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
    backgroundColor: colors.surface,
  },
  headerTitle: { flex: 1, fontFamily: fonts.display, fontSize: 20, lineHeight: 26, color: colors.text },
  headerTitleAlone: { paddingVertical: space[2] },
  round: {
    width: MIN_TARGET,
    height: MIN_TARGET,
    borderRadius: MIN_TARGET / 2,
    borderWidth: 1,
    borderColor: colors.borderStrong,
    backgroundColor: colors.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  roundOnPhoto: { borderWidth: 0, ...shadow.md },
  roundPressed: { backgroundColor: semantic['surface-hover'] },
  top: {
    gap: space[3],
    paddingHorizontal: GUTTER,
    paddingTop: space[2],
    paddingBottom: space[3],
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
    backgroundColor: colors.surface,
  },
  topRow: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  place: { flex: 1, minWidth: 0, minHeight: MIN_TARGET, flexDirection: 'row', alignItems: 'center', gap: space[2], borderRadius: radius.md, paddingRight: space[2] },
  placePressed: { backgroundColor: semantic['surface-hover'] },
  placeText: { flex: 1, minWidth: 0 },
  placeKicker: { fontFamily: fonts.regular, fontSize: 12, lineHeight: 15, color: colors.textMuted },
  placeLine: { flexDirection: 'row', alignItems: 'center', gap: 4, minWidth: 0 },
  placeName: { flexShrink: 1, fontFamily: fonts.bold, fontSize: 16, lineHeight: 21, color: colors.text },
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[2],
    minHeight: 52,
    paddingLeft: space[5],
    paddingRight: 5,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.borderControl,
    backgroundColor: colors.surface,
    ...shadow.sm,
  },
  searchPressed: { backgroundColor: semantic['surface-hover'] },
  searchText: { flex: 1, fontFamily: fonts.regular, fontSize: 15, color: colors.textMuted },
  searchGo: { width: 42, height: 42, borderRadius: 21, backgroundColor: semantic['action-primary'], alignItems: 'center', justifyContent: 'center' },
});
