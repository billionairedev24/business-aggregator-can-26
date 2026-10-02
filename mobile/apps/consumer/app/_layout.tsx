// one file per weight (the packages' roots would bundle every weight and italic)
import { InstrumentSans_400Regular } from '@expo-google-fonts/instrument-sans/400Regular';
import { InstrumentSans_600SemiBold } from '@expo-google-fonts/instrument-sans/600SemiBold';
import { Newsreader_500Medium } from '@expo-google-fonts/newsreader/500Medium';
import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, type ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { LOCALES, colors, type Locale } from '@northline/mobile-kit';

import { AccountFlowProvider } from '../src/auth/AccountFlow';
import { AuthProvider, useAuth } from '../src/auth/AuthProvider';
import { I18nProvider, useI18n } from '../src/i18n';
import { DeliveryLocationProvider } from '../src/location/DeliveryLocation';
import { services } from '../src/services';
import { Loading } from '../src/ui/states';

const LANGUAGE_KEY = 'nl.app.language';

/** The language the person picked (You › Language), else the device's; the api answers in it too (Accept-Language). */
function LanguageSync({ children }: { children: ReactNode }) {
  const { locale, setLocale } = useI18n();
  useEffect(() => {
    void services()
      .store.getItem(LANGUAGE_KEY)
      .then((saved) => {
        if (saved && (LOCALES as readonly string[]).includes(saved) && saved !== locale) setLocale(saved as Locale);
      })
      .catch(() => undefined);
    // once, at start
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  services().setLanguage(locale);
  return children;
}

function Routes() {
  const { status } = useAuth();
  if (status === 'loading') return <Loading />;
  return <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.bg } }} />;
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({ Newsreader_500Medium, InstrumentSans_400Regular, InstrumentSans_600SemiBold });
  const ready = fontsLoaded || !!fontError;
  return (
    <SafeAreaProvider>
      <QueryClientProvider client={services().queryClient}>
        <I18nProvider onChange={(l) => void services().store.setItem(LANGUAGE_KEY, l).catch(() => undefined)}>
          <LanguageSync>
            <AuthProvider>
              <DeliveryLocationProvider>
                <AccountFlowProvider>
                  <StatusBar style="dark" />
                  {ready ? <Routes /> : null}
                </AccountFlowProvider>
              </DeliveryLocationProvider>
            </AuthProvider>
          </LanguageSync>
        </I18nProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  );
}
