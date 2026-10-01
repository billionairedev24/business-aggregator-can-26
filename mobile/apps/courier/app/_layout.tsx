// one file per weight (the packages' roots would bundle every weight and italic)
import { InstrumentSans_400Regular } from '@expo-google-fonts/instrument-sans/400Regular';
import { InstrumentSans_600SemiBold } from '@expo-google-fonts/instrument-sans/600SemiBold';
import { Newsreader_500Medium } from '@expo-google-fonts/newsreader/500Medium';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, type ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { LOCALES, colors, type Locale } from '@northline/mobile-kit';

import { AuthProvider, useAuth } from '../src/auth';
import { Loading } from '../src/components/ui';
import { I18nProvider, useI18n } from '../src/i18n';
import { defineLocationTask } from '../src/location/tracker';
import { services } from '../src/services';

// The background location task must exist before anything else runs (also when the OS starts the app for it).
defineLocationTask();

const LANGUAGE_KEY = 'nl.courier.language';

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
  return children;
}

function Routes() {
  const { status } = useAuth();
  const { t } = useI18n();
  if (status === 'loading') return <Loading label={t('common.loading')} />;
  return (
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.bg } }}>
      <Stack.Protected guard={status === 'signedIn'}>
        <Stack.Screen name="(app)" />
      </Stack.Protected>
      <Stack.Protected guard={status !== 'signedIn'}>
        <Stack.Screen name="sign-in" />
      </Stack.Protected>
      <Stack.Screen name="oauth2redirect" />
    </Stack>
  );
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({ Newsreader_500Medium, InstrumentSans_400Regular, InstrumentSans_600SemiBold });
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
