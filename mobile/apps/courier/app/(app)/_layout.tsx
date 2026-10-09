import { Stack } from 'expo-router';

import { ApiError, colors, fonts } from '@northline/mobile-kit';

import { useAuth } from '../../src/auth';
import { TrackingContext } from '../../src/components/tracking';
import { Banner, Button, Heading, Screen } from '../../src/components/ui';
import { useFrenchFirst, useMe, useOnline, useRun } from '../../src/hooks';
import { useI18n } from '../../src/i18n';
import { useRunTracking } from '../../src/location/useRunTracking';
import { FeedbackButton } from '../../src/pilot/Feedback';

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
      <Screen>
        <Heading>{t('app.name')}</Heading>
        <Banner tone="warn">{t('signIn.notCourier')}</Banner>
        <Button label={t('account.signOut')} tone="secondary" onPress={() => void signOut()} />
      </Screen>
    );
  }

  return (
    <TrackingContext.Provider value={{ ...tracking, online }}>
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.bg },
          headerTintColor: colors.accent,
          headerTitleStyle: { fontFamily: fonts.heading, color: colors.text },
          headerShadowVisible: false,
          contentStyle: { backgroundColor: colors.bg },
          headerBackTitle: t('common.back'),
          // S-121 for pilot couriers (mobile gaps part 1): "Feedback" on every screen; nothing for other couriers
          headerRight: () => <FeedbackButton />,
        }}
      >
        <Stack.Screen name="index" options={{ title: t('shift.title') }} />
        <Stack.Screen name="run" options={{ title: t('run.titleNoLabel') }} />
        <Stack.Screen name="stops/[id]/index" options={{ title: '' }} />
        <Stack.Screen name="stops/[id]/pickup" options={{ title: t('pickup.title') }} />
        <Stack.Screen name="stops/[id]/dropoff" options={{ title: t('dropoff.title') }} />
        <Stack.Screen name="account" options={{ title: t('account.title') }} />
        <Stack.Screen name="feedback" options={{ title: t('pilot.title') }} />
      </Stack>
    </TrackingContext.Provider>
  );
}
