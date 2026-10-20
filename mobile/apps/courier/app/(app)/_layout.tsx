import { Stack } from 'expo-router';

import { ApiError, colors } from '@northline/mobile-kit';

import { useAuth } from '../../src/auth';
import { TrackingContext } from '../../src/components/tracking';
import { Banner, Button, Screen } from '../../src/components/ui';
import { useFrenchFirst, useMe, useOnline, useRun } from '../../src/hooks';
import { useI18n } from '../../src/i18n';
import { useRunTracking } from '../../src/location/useRunTracking';

/** Signed in: the courier's screens, location sharing that follows the run, the outbox woken by connectivity. */
export default function AppLayout() {
  const { t } = useI18n();
  const { signOut } = useAuth();
  const me = useMe();
  useFrenchFirst(me.data?.market); // S-116: French-first markets open in French unless the courier picked a language
  const { run } = useRun();
  const online = useOnline();
  const tracking = useRunTracking(!!run && run.state !== 'done');

  if (me.error instanceof ApiError && me.error.status === 403) {
    return (
      <Screen title={t('app.name')}>
        <Banner tone="warn">{t('signIn.notCourier')}</Banner>
        <Button label={t('account.signOut')} tone="secondary" onPress={() => void signOut()} />
      </Screen>
    );
  }

  return (
    <TrackingContext.Provider value={{ ...tracking, online }}>
      {/* every screen draws the driver app's own top bar (src/components/ui.tsx); the tabs are Today · Route · Profile */}
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.surface } }}>
        <Stack.Screen name="(tabs)" />
        <Stack.Screen name="stops/[id]/index" />
        <Stack.Screen name="stops/[id]/pickup" />
        <Stack.Screen name="stops/[id]/dropoff" />
        <Stack.Screen name="feedback" />
        <Stack.Screen name="earnings" />
      </Stack>
    </TrackingContext.Provider>
  );
}
