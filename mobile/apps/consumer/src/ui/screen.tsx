import { router, useFocusEffect } from 'expo-router';
import { setStatusBarStyle } from 'expo-status-bar';
import { useCallback, type ReactNode, type Ref } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View, type RefreshControlProps } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import { CAP, Icon, IconButton, LINE, LogoMark, MIN_TARGET, SIZE, SearchBar, TYPE, brand, colors, radius, semantic, space, textStyles, type IconName } from '@northline/mobile-kit';

import { useI18n } from '../i18n';
import { OfflineBanner } from './states';

/** The side padding of every screen (16 pt). */
export const GUTTER = space[4];

/**
 * A screen on the light direction: white, the compact sub-page header (a 44 pt round Back button and the title on one
 * line) when `title` is given, otherwise `top` (a tab's own top bar — {@link TopBar}) or a thin band; 16 pt side
 * padding; content that scrolls; an offline banner while the phone has no connection. `footer` is the sticky bottom
 * action bar: it stays above the home indicator and rises with the keyboard. `action` puts one more round button on
 * the right of the header. `chrome` (S-188): `top` is the violet consumer chrome ({@link TopBar}), so the status bar's
 * strip is violet too and its text turns light while the screen is in front.
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
  scrollRef,
  chrome,
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
  /** The content's ScrollView (S-178: a provider's tabs scroll to their section). */
  scrollRef?: Ref<ScrollView>;
  /** `top` is the violet chrome (Home, Browse). */
  chrome?: boolean;
  testID?: string;
}) {
  useFocusEffect(
    useCallback(() => {
      if (!chrome) return undefined;
      setStatusBarStyle('light');
      return () => setStatusBarStyle('dark');
    }, [chrome]),
  );
  const body = <View style={[styles.body, padded && styles.padded]}>{children}</View>;
  return (
    <SafeAreaView style={[styles.screen, chrome && styles.screenChrome]} edges={['top', 'left', 'right']} testID={testID}>
      {title !== undefined ? <Header title={title} back={back} onBack={onBack} action={action} /> : (top ?? <View style={styles.band} />)}
      <OfflineBanner />
      <KeyboardAvoidingView style={[styles.flex, styles.page]} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        {scroll ? (
          <ScrollView ref={scrollRef} contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled" refreshControl={refreshControl}>
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

/**
 * A 44 pt icon button (Back, Share, Save, the bell): the kit's square `IconButton` with the control edge (Plainsight:
 * 3 pt corners, never a disc), or a white square with a shadow over a picture (`onPhoto`).
 */
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
  /** Over a picture: a white square with a shadow so it reads on any photo. */
  onPhoto?: boolean;
  testID?: string;
}) {
  return <IconButton icon={icon} label={label} onPress={onPress} hint={hint} filled={filled} color={color} boxed={!onPhoto} onPhoto={onPhoto} testID={testID} />;
}

/** The compact sub-page header: the round Back button, the title (the display face, one line), an optional action. */
export function Header({ title, back = true, onBack, action }: { title: string; back?: boolean; onBack?: () => void; action?: ReactNode }) {
  const { t } = useI18n();
  const goBack = onBack ?? (() => (router.canGoBack() ? router.back() : router.replace('/home')));
  return (
    <View style={styles.header}>
      {back ? <RoundButton icon="caret-left" label={t('common.back')} onPress={goBack} testID="header-back" /> : null}
      <Text accessibilityRole="header" numberOfLines={1} maxFontSizeMultiplier={CAP.control} style={[styles.headerTitle, !back && styles.headerTitleAlone]}>
        {title}
      </Text>
      {action}
    </View>
  );
}

/**
 * A tab's top (S-188 Plainsight + Chinook, design/revamp/hybrid's "Consumer home · phone (390)"): the consumer's violet
 * header (`header-*`) — the brand (`lead`, the mark on violet, the white name) and the white icon actions on one row,
 * the white search field joined to the violet search button under it, then "Deliver to <place>" on one line (the
 * muted `header-muted`, the place in white) with an ellipsis — the person's place opens the location sheet — and
 * `below` (Home's violet department strip). No violet text on violet. Never asks for the phone's position (that
 * happens only in the sheet, on "Use my current location").
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
  below,
}: {
  /** Left of the first row (Home: the brand's mark and name). */
  lead?: ReactNode;
  kicker: string;
  /** The place's name; none yet = the "set your location" words in its stead. */
  place: string;
  onPlace: () => void;
  placeLabel: string;
  placeHint?: string;
  actions?: ReactNode;
  search?: ReactNode;
  /** Under the place line, edge to edge (Home: the department strip). */
  below?: ReactNode;
}) {
  return (
    <View style={styles.top}>
      <View style={styles.topIn}>
        {lead || actions ? (
          <View style={styles.topRow}>
            <View style={styles.topLead}>{lead}</View>
            {actions}
          </View>
        ) : null}
        {search}
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={placeLabel}
          accessibilityHint={placeHint}
          onPress={onPlace}
          style={({ pressed }) => [styles.place, pressed && styles.placePressed]}
          testID="home-place"
        >
          <Icon name="map-pin" size={TYPE.sub} color={semantic['header-text']} />
          <Text style={styles.placeLine} numberOfLines={1} maxFontSizeMultiplier={CAP.control}>
            <Text style={styles.placeKicker}>{kicker} </Text>
            <Text style={styles.placeName}>{place}</Text>
          </Text>
          <Icon name="caret-down" size={TYPE.meta} color={semantic['header-muted']} />
        </Pressable>
      </View>
      {below}
    </View>
  );
}

/** The one search bar of a tab's top (opens the search screen): the kit's SearchBar without a field, on the violet chrome. */
export function SearchPill({ label, onPress, testID }: { label: string; onPress: () => void; testID?: string }) {
  return <SearchBar placeholder={label} onPress={onPress} onChrome testID={testID} />;
}

/** The brand on the violet header: the mark (the same violet disc, white glyph, yellow dot as on white) and the white name. */
export function ChromeLogo() {
  return (
    <View accessible accessibilityRole="image" accessibilityLabel={brand.name} style={styles.logo} testID="logo">
      <LogoMark size={SIZE['header-mark']} onDark />
      <Text style={styles.wordmark} numberOfLines={1} maxFontSizeMultiplier={CAP.control} importantForAccessibility="no">
        {brand.name}
      </Text>
    </View>
  );
}

/** A 44 pt icon action on the violet header (the bell): a white icon, the darker violet when pressed. */
export function ChromeButton({ icon, label, onPress, testID }: { icon: IconName; label: string; onPress: () => void; testID?: string }) {
  return <IconButton icon={icon} label={label} onPress={onPress} onChrome testID={testID} />;
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surface },
  screenChrome: { backgroundColor: semantic['header-bg'] },
  flex: { flex: 1 },
  page: { backgroundColor: colors.surface },
  band: { height: space[2] },
  scroll: { flexGrow: 1 },
  body: { flexGrow: 1, gap: space[4], paddingTop: space[3], paddingBottom: space[8] },
  padded: { paddingHorizontal: GUTTER },
  bar: {
    gap: space[2],
    paddingTop: space[3],
    paddingHorizontal: GUTTER,
    borderTopWidth: LINE.hair,
    borderTopColor: colors.borderStrong,
    backgroundColor: colors.surface,
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
  headerTitle: { ...StyleSheet.flatten(textStyles.sub), flex: 1 },
  headerTitleAlone: { paddingVertical: space[2] },
  top: { backgroundColor: semantic['header-bg'] },
  topIn: { gap: space[2], paddingHorizontal: GUTTER, paddingTop: space[2], paddingBottom: space[1] },
  logo: { flexDirection: 'row', alignItems: 'center', gap: space[2], minHeight: MIN_TARGET },
  wordmark: { ...StyleSheet.flatten(textStyles.section), color: semantic['header-text'], flexShrink: 1 },
  topRow: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  topLead: { flex: 1, minWidth: 0 },
  place: { minHeight: MIN_TARGET, flexDirection: 'row', alignItems: 'center', gap: space[1], borderRadius: radius.control },
  placePressed: { backgroundColor: semantic['header-hover-bg'] },
  placeLine: { ...StyleSheet.flatten(textStyles.meta), flexShrink: 1 },
  placeKicker: { color: semantic['header-muted'] },
  placeName: { fontFamily: textStyles.metaStrong.fontFamily, color: semantic['header-text'] },
});
