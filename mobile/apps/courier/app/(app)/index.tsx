import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';

import { ApiError, NetworkError } from '@northline/mobile-kit';

import type { Shift } from '../../src/api/courier';
import { OutboxBar } from '../../src/components/tracking';
import { Banner, Body, Button, Card, Chip, Heading, Loading, Row, Screen } from '../../src/components/ui';
import { useMarketZone, useMe, useRun, useShifts } from '../../src/hooks';
import { useI18n, type MessageKey } from '../../src/i18n';
import { FeedbackButton } from '../../src/pilot/Feedback';
import { PushPrompt } from '../../src/push/PhonePush';
import { ME_KEY, RUN_KEY, SHIFTS_KEY, services } from '../../src/services';

const EARLY_START_MS = 15 * 60_000;

/** The time, refreshed every 30 s: the start button opens 15 minutes before the shift without a reload. */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);
  return now;
}

/** Shift status: on or off shift, start / end, the next shift, and the way into the open run. */
export default function ShiftScreen() {
  const { t, time, day } = useI18n();
  const router = useRouter();
  const qc = useQueryClient();
  const me = useMe();
  const shifts = useShifts();
  const { run } = useRun();
  const zone = useMarketZone(me.data?.market);
  const [error, setError] = useState<string | null>(null);
  const now = useNow();

  const change = useMutation({
    mutationFn: ({ shift, to }: { shift: Shift; to: 'start' | 'end' }) =>
      to === 'start' ? services().courier.startShift(shift.id) : services().courier.endShift(shift.id),
    onMutate: () => setError(null),
    onError: (e) => {
      if (e instanceof NetworkError) setError(t('shift.needsNetwork'));
      else if (e instanceof ApiError && e.code === 'run_open') setError(t('shift.endBlocked'));
      else setError(e.message);
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ME_KEY });
      void qc.invalidateQueries({ queryKey: SHIFTS_KEY });
      void qc.invalidateQueries({ queryKey: RUN_KEY });
    },
  });

  const header = (
    <Stack.Screen
      options={{
        headerRight: () => (
          <Row>
            <FeedbackButton />
            <Button tone="ghost" label={t('account.title')} onPress={() => router.push('/account')} />
          </Row>
        ),
      }}
    />
  );
  if (me.isPending) {
    return (
      <>
        {header}
        <Loading label={t('common.loading')} />
      </>
    );
  }
  if (me.error && !me.data) {
    return (
      <Screen>
        {header}
        <Banner tone="error" action={<Button tone="ghost" label={t('common.retry')} onPress={() => void me.refetch()} />}>
          {me.error instanceof NetworkError ? t('common.offline') : t('common.error', { detail: me.error.message })}
        </Banner>
      </Screen>
    );
  }

  const courier = me.data!;
  const current = courier.shift?.state === 'on' ? courier.shift : null;
  const next = (shifts.data ?? []).filter((s) => s.state === 'scheduled').sort((a, b) => a.startsAt.localeCompare(b.startsAt))[0];
  const startable = next && now >= Date.parse(next.startsAt) - EARLY_START_MS && now < Date.parse(next.endsAt);
  const done = run?.stops.filter((s) => s.state === 'done').length ?? 0;

  return (
    <Screen testID="shift-screen">
      {header}
      <OutboxBar />
      <Row>
        <Chip tone={courier.status === 'offline' ? 'neutral' : 'accent'} label={t(`shift.status.${courier.status}` as MessageKey)} />
        {courier.vehicle ? <Body muted>{t('shift.vehicle', { vehicle: t(`vehicle.${courier.vehicle}` as MessageKey) })}</Body> : null}
      </Row>

      {current ? (
        <Card>
          <Heading level={2}>{t('shift.on', { end: time(current.endsAt, zone) })}</Heading>
          <Button
            tone="secondary"
            label={t('shift.end')}
            disabled={!!run}
            hint={run ? t('shift.endBlocked') : undefined}
            busy={change.isPending && change.variables?.to === 'end'}
            onPress={() => change.mutate({ shift: current, to: 'end' })}
            testID="end-shift"
          />
          {run ? <Body muted>{t('shift.endBlocked')}</Body> : null}
        </Card>
      ) : next ? (
        <Card>
          <Heading level={2}>{t('shift.next', { day: day(next.startsAt, zone), start: time(next.startsAt, zone), end: time(next.endsAt, zone) })}</Heading>
          <Button
            label={t('shift.start')}
            disabled={!startable}
            busy={change.isPending && change.variables?.to === 'start'}
            onPress={() => change.mutate({ shift: next, to: 'start' })}
            testID="start-shift"
          />
          {!startable ? <Body muted>{t('shift.startHint', { start: time(next.startsAt, zone) })}</Body> : null}
        </Card>
      ) : (
        <Card>
          <Body>{t('shift.none')}</Body>
        </Card>
      )}

      {current ? <PushPrompt /> : null}
      {error ? <Banner tone="error">{error}</Banner> : null}

      {run ? (
        <Card highlight onPress={() => router.push('/run')} label={t('shift.openRun')} testID="open-run">
          <Heading level={2}>{run.label ? t('shift.run', { label: run.part > 1 ? `${run.label} · ${run.part}` : run.label }) : t('run.titleNoLabel')}</Heading>
          <Body>{t('shift.runProgress', { done, total: run.stops.length })}</Body>
          <Body strong>{t('shift.openRun')} ›</Body>
        </Card>
      ) : current ? (
        <Body muted>{t('shift.noRun')}</Body>
      ) : null}
    </Screen>
  );
}
