// one file per weight (the packages' roots would bundle every weight and italic)
import { InstrumentSans_400Regular } from '@expo-google-fonts/instrument-sans/400Regular';
import { InstrumentSans_600SemiBold } from '@expo-google-fonts/instrument-sans/600SemiBold';
import { Newsreader_500Medium } from '@expo-google-fonts/newsreader/500Medium';
import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack, router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, type ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { LOCALES, colors, type Locale } from '@northline/mobile-kit';

import { AccountFlowProvider } from '../src/auth/AccountFlow';
import { AuthProvider, useAuth } from '../src/auth/AuthProvider';
import { I18nProvider, useI18n } from '../src/i18n';
import { DeliveryLocationProvider } from '../src/location/DeliveryLocation';
import { FrenchFirst, LANGUAGE_KEY } from '../src/location/FrenchFirst';
import { PilotButton } from '../src/pilot/Pilot';
import { setUpPush } from '../src/push/install';
import { services } from '../src/services';
import { Loading } from '../src/ui/states';

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

/** Push (mobile gaps part 1): installed once the sign-in is known; a tap opens the deep link's screen. */
function PushSetup() {
  const { status } = useAuth();
  const { t } = useI18n();
  const signedIn = useRef(status === 'signedIn');
  useEffect(() => {
    signedIn.current = status === 'signedIn';
  }, [status]);
  useEffect(
    () => setUpPush({ open: (route) => router.push(route as never), signedIn: () => signedIn.current, channelName: t('push.channel') }) ?? undefined,
    // once per app start; the channel name follows the language at that moment
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  return null;
}

function Routes() {
  const { status } = useAuth();
  if (status === 'loading') return <Loading />;
  return (
    <>
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.bg } }} />
      <PushSetup />
      <PilotButton />
    </>
  );
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
                <FrenchFirst />
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
