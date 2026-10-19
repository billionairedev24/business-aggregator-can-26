import { QueryClientProvider } from '@tanstack/react-query';
import { useFonts } from 'expo-font';
import { Stack, router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, type ReactNode } from 'react';
import { Platform } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { LOCALES, colors, fontAssets, type Locale } from '@northline/mobile-kit';

import { AccountFlowProvider } from '../src/auth/AccountFlow';
import { AuthProvider, useAuth } from '../src/auth/AuthProvider';
import { I18nProvider, useI18n } from '../src/i18n';
import { DeliveryLocationProvider } from '../src/location/DeliveryLocation';
import { FrenchFirst, LANGUAGE_KEY } from '../src/location/FrenchFirst';
import { PilotButton } from '../src/pilot/Pilot';
import { setUpPush } from '../src/push/install';
import { config } from '../src/config';
import { seedOrder } from '../src/fixtures/shop';
import { services } from '../src/services';
import { Loading } from '../src/ui/states';


/**
 * `make mobile-shots` (scripts/mobile-shots.mjs): on the web build with the fixture backend only, the screenshot run
 * opens screens in place (a page load would start a new, signed-out session). Never present in a phone build.
 */
if (config.fixtures && Platform.OS === 'web') {
  const g = globalThis as { __nlGo?: (href: string) => void; __nlSeedOrder?: (state: Parameters<typeof seedOrder>[2], id: string) => void };
  g.__nlGo = (href) => router.push(href as never);
  g.__nlSeedOrder = (state, id) => {
    const shop = services().fixtures?.shop;
    if (shop) seedOrder(shop, Date.now(), state, id);
    void services().queryClient.invalidateQueries();
  };
}
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
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.bg } }}>
        {/* the location sheet slides up over the screen it was opened from */}
        <Stack.Screen name="location" options={{ presentation: 'transparentModal', animation: 'slide_from_bottom', contentStyle: { backgroundColor: 'transparent' } }} />
      </Stack>
      <PushSetup />
      <PilotButton />
    </>
  );
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts(fontAssets);
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
