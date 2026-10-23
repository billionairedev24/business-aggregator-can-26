import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack, router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, type ReactNode } from 'react';
import { Platform } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { KitProvider, LOCALES, ToastProvider, colors, fontAssets, type Locale, configurePhotoBase } from '@northline/mobile-kit';

import { AuthProvider, useAuth } from '../src/auth';
import { Loading } from '../src/components/ui';
import { I18nProvider, LANGUAGE_KEY, useI18n } from '../src/i18n';
import { defineLocationTask } from '../src/location/tracker';
import { setUpPush } from '../src/push/install';
import { config } from '../src/config';
import { services } from '../src/services';

// real photos: the stock photos' variants come from the consumer web's /photos/ or a CDN (docs/runbooks/photos.md)
configurePhotoBase(config.photosBaseUrl);

// The background location task must exist before anything else runs (also when the OS starts the app for it).
defineLocationTask();


/**
 * `make mobile-shots` (scripts/mobile-shots.mjs): on the web build with the fixture backend only, the screenshot run
 * opens screens in place (a page load would start a new, signed-out session). Never present in a phone build.
 */
if (config.fixtures && Platform.OS === 'web') {
  (globalThis as { __nlGo?: (href: string) => void }).__nlGo = (href) => router.push(href as never);
}
/** The language the courier picked (Account), else the device's; the api answers in it too (Accept-Language). */
function LanguageSync({ children }: { children: ReactNode }) {
  const { locale, setLocale } = useI18n();
  useEffect(() => {
    void AsyncStorage.getItem(LANGUAGE_KEY).then((saved) => {
      if (saved && (LOCALES as readonly string[]).includes(saved) && saved !== locale) setLocale(saved as Locale);
    });
    // once, at start
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    services().setLanguage(locale);
  }, [locale]);
  // S-169: the shared kit's own words follow the app's language
  return (
    <KitProvider locale={locale}>
      <ToastProvider>{children}</ToastProvider>
    </KitProvider>
  );
}

/** Push (mobile gaps part 1): installed once the sign-in is known; a tap opens the run. */
function PushSetup() {
  const { status } = useAuth();
  const { t } = useI18n();
  const signedIn = useRef(status === 'signedIn');
  useEffect(() => {
    signedIn.current = status === 'signedIn';
  }, [status]);
  useEffect(
    () =>
      setUpPush({
        open: (route) => signedIn.current && router.push(route as never),
        signedIn: () => signedIn.current,
        channelName: t('push.channel'),
      }) ?? undefined,
    // once per app start; the channel name follows the language at that moment
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  return null;
}

function Routes() {
  const { status } = useAuth();
  const { t } = useI18n();
  if (status === 'loading') return <Loading label={t('common.loading')} />;
  return (
    <>
      <PushSetup />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.surface } }}>
      <Stack.Protected guard={status === 'signedIn'}>
        <Stack.Screen name="(app)" />
      </Stack.Protected>
      <Stack.Protected guard={status !== 'signedIn'}>
        <Stack.Screen name="sign-in" />
      </Stack.Protected>
      <Stack.Screen name="oauth2redirect" />
      <Stack.Screen name="dev/gallery" />
      </Stack>
    </>
  );
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts(fontAssets);
  const ready = fontsLoaded || !!fontError;
  return (
    <SafeAreaProvider>
      <QueryClientProvider client={services().queryClient}>
        <I18nProvider onChange={(l) => void AsyncStorage.setItem(LANGUAGE_KEY, l)}>
          <LanguageSync>
            <AuthProvider>
              <StatusBar style="dark" />
              {ready ? <Routes /> : null}
            </AuthProvider>
          </LanguageSync>
        </I18nProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  );
}
