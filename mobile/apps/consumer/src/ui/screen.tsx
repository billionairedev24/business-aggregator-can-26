import { router } from 'expo-router';
import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View, type RefreshControlProps } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import { Icon, MIN_TARGET, colors, fonts, semantic, space } from '@northline/mobile-kit';

import { useI18n } from '../i18n';
import { OfflineBanner } from './states';

/** The side padding of every screen (MA: 16). */
export const GUTTER = space[4];

/**
 * A screen on the redesign's mobile layout (MA, docs/design/redesign-spec.md § 1.3 Mobile): the shared sub-page header
 * (a 44 pt round "Back" button and the title, MA:177) when `title` is given, otherwise a thin top band (a tab draws its
 * own top); 16 pt side padding; content that scrolls; an offline banner while the phone has no connection. `footer` is
 * the sticky bottom action bar (MA:529): it stays above the home indicator and rises with the keyboard. `action` puts
 * one more round button on the right of the header.
 */
export function Screen({
  title,
  back = true,
  onBack,
  action,
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
      {title !== undefined ? <Header title={title} back={back} onBack={onBack} action={action} /> : <View style={styles.band} />}
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

/** MA:529 — the sticky bottom action bar: a hairline on top, white, the home indicator's room below. */
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

/** A 44 pt round icon button (MA's header buttons: Back, Share, Save, the bell). */
export function RoundButton({ icon, label, onPress, hint, filled, testID }: { icon: Parameters<typeof Icon>[0]['name']; label: string; onPress: () => void; hint?: string; filled?: boolean; testID?: string }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      onPress={onPress}
      hitSlop={4}
      style={({ pressed }) => [styles.round, pressed && styles.roundPressed]}
      testID={testID}
    >
      <Icon name={icon} size={20} filled={filled} />
    </Pressable>
  );
}

/** The shared sub-page header (MA:177): the round Back button, the title (22 / 700), an optional action on the right. */
export function Header({ title, back = true, onBack, action }: { title: string; back?: boolean; onBack?: () => void; action?: ReactNode }) {
  const { t } = useI18n();
  const goBack = onBack ?? (() => (router.canGoBack() ? router.back() : router.replace('/home')));
  return (
    <View style={styles.header}>
      {back ? <RoundButton icon="caret-left" label={t('common.back')} onPress={goBack} testID="header-back" /> : null}
      <Text accessibilityRole="header" numberOfLines={2} maxFontSizeMultiplier={1.4} style={styles.headerTitle}>
        {title}
      </Text>
      {action}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surface },
  flex: { flex: 1 },
  band: { height: space[2] },
  scroll: { flexGrow: 1 },
  body: { flexGrow: 1, gap: space[4], paddingTop: space[2], paddingBottom: space[6] },
  padded: { paddingHorizontal: GUTTER },
  bar: { gap: space[2], paddingTop: 10, paddingHorizontal: GUTTER, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.border, backgroundColor: colors.surface },
  barRow: { flexDirection: 'row', alignItems: 'center' },
  header: { flexDirection: 'row', alignItems: 'center', gap: space[2], paddingHorizontal: GUTTER, paddingTop: space[2], paddingBottom: space[3], minHeight: MIN_TARGET + space[4] },
  headerTitle: { flex: 1, fontFamily: fonts.bold, fontSize: 22, lineHeight: 27, color: colors.text },
  round: { width: MIN_TARGET, height: MIN_TARGET, borderRadius: MIN_TARGET / 2, backgroundColor: semantic['surface-muted'], alignItems: 'center', justifyContent: 'center' },
  roundPressed: { backgroundColor: semantic['surface-hover'] },
});
