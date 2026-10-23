import { Redirect, router } from 'expo-router';
import { StyleSheet } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import { KitGallery, colors } from '@northline/mobile-kit';

import { devTools } from '../../src/config';
import { useI18n } from '../../src/i18n';

/**
 * S-169: the shared kit's gallery, in a dev build only (a development build, or the fixture build `make mobile-shots`
 * photographs); a store build sends the link home. Not a design screen, so not in src/screens.ts.
 */
export default function GalleryRoute() {
  const { locale } = useI18n();
  const insets = useSafeAreaInsets();
  if (!devTools()) return <Redirect href="/home" />;
  return (
    <SafeAreaView style={styles.root} edges={['top', 'left', 'right']}>
      <KitGallery initialLocale={locale} bottomInset={insets.bottom} onBack={() => (router.canGoBack() ? router.back() : router.replace('/home'))} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({ root: { flex: 1, backgroundColor: colors.surface } });
