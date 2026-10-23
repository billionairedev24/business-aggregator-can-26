import { Redirect, router } from 'expo-router';
import { StyleSheet } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import { KitGallery, colors } from '@northline/mobile-kit';

import { devTools } from '../../src/config';
import { useI18n } from '../../src/i18n';

/** S-169: the shared kit's gallery (the consumer app shows the same one), in a dev build only; a store build goes home. */
export default function GalleryRoute() {
  const { locale } = useI18n();
  const insets = useSafeAreaInsets();
  if (!devTools()) return <Redirect href="/" />;
  return (
    <SafeAreaView style={styles.root} edges={['top', 'left', 'right']}>
      <KitGallery initialLocale={locale} bottomInset={insets.bottom} onBack={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({ root: { flex: 1, backgroundColor: colors.surface } });
