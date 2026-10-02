import { router } from 'expo-router';
import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View, type RefreshControlProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { MIN_TARGET, colors, fonts, space } from '@northline/mobile-kit';

import { useI18n } from '../i18n';
import { OfflineBanner } from './states';

/**
 * A screen of design 01: the sub-screen header ("← Back" + centred title) when `title` is given, otherwise the
 * design's empty top band; 24 dp side padding; content that scrolls; an offline banner while the phone has no
 * connection. `footer` stays at the bottom (the design's `margin-top:auto` actions) and rises with the keyboard.
 */
export function Screen({
  title,
  back = true,
  onBack,
  children,
  footer,
  scroll = true,
  padded = true,
  refreshControl,
  testID,
}: {
  /** The header's title (design `titles[screen]`); no title = no header. */
  title?: string;
  /** Show "← Back" (default when there is a title). */
  back?: boolean;
  onBack?: () => void;
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
      {title !== undefined ? <Header title={title} back={back} onBack={onBack} /> : <View style={styles.band} />}
      <OfflineBanner />
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        {scroll ? (
          <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled" refreshControl={refreshControl}>
            {body}
            {footer ? <View style={[styles.footer, padded && styles.padded]}>{footer}</View> : null}
          </ScrollView>
        ) : (
          <>
            {body}
            {footer ? <View style={[styles.footer, padded && styles.padded]}>{footer}</View> : null}
          </>
        )}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

/** The design's sub-screen header: "← Back" on the left (accent-700), the title centred. */
export function Header({ title, back = true, onBack }: { title: string; back?: boolean; onBack?: () => void }) {
  const { t } = useI18n();
  const goBack = onBack ?? (() => (router.canGoBack() ? router.back() : router.replace('/home')));
  return (
    <View style={styles.header}>
      {back ? (
        <Pressable accessibilityRole="button" accessibilityLabel={t('common.back')} onPress={goBack} hitSlop={8} style={styles.back} testID="header-back">
          <Text style={styles.backText}>← {t('common.back')}</Text>
        </Pressable>
      ) : (
        <View style={styles.back} />
      )}
      <Text accessibilityRole="header" numberOfLines={1} style={styles.headerTitle}>
        {title}
      </Text>
      <View style={styles.back} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  flex: { flex: 1 },
  band: { height: space[3] },
  scroll: { flexGrow: 1 },
  body: { flexGrow: 1, gap: space[3], paddingTop: space[4] },
  padded: { paddingHorizontal: 24 },
  footer: { gap: 10, paddingTop: space[4], paddingBottom: space[8] },
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: space[4], minHeight: MIN_TARGET, gap: 6 },
  back: { minWidth: 72, minHeight: MIN_TARGET, justifyContent: 'center' },
  backText: { fontFamily: fonts.body, fontSize: 15, color: colors.accent700 },
  headerTitle: { flex: 1, textAlign: 'center', fontFamily: fonts.bodyStrong, fontSize: 16, color: colors.text },
});
