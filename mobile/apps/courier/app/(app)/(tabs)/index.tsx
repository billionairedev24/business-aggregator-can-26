import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, Icon, LINE, NetworkError, TYPE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import type { Shift } from '../../../src/api/courier';
import { OutboxBar } from '../../../src/components/tracking';
import { Banner, Body, Button, Card, Chip, Figure, Heading, Loading, Progress, Screen, StepNumber } from '../../../src/components/ui';
import { useMarketZone, useMe, useRun, useShifts } from '../../../src/hooks';
import { useI18n, type MessageKey } from '../../../src/i18n';
import { FeedbackButton } from '../../../src/pilot/Feedback';
import { PushPrompt } from '../../../src/push/PhonePush';
import { ME_KEY, RUN_KEY, SHIFTS_KEY, services } from '../../../src/services';
import { kindKey, nextStop, stopAddress, stopName } from '../../../src/stops';

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

/**
 * Today (S-179, E-15 Plainsight, glanceable in sunlight): the courier's status as a large tag; the shift — when it ends
 * (or the next one), start 15 minutes early / end, full-width 46 pt buttons; the run they're on as the job card — the
 * stops done of the total and the run's window as 28 pt figures, an ink progress bar, the next stop with its number
 * in an ink square and its address — the way into Route; and the run notifications prompt. Runs are assigned by
 * dispatch (the api has no offer to accept or decline).
 */
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

  const title = t('tab.today');
  if (me.isPending) {
    return (
      <Screen title={title} scroll={false}>
        <Loading label={t('common.loading')} />
      </Screen>
    );
  }
  if (me.error && !me.data) {
    return (
      <Screen title={title}>
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
  const upNext = run ? nextStop(run) : undefined;

  return (
    <Screen testID="shift-screen" title={title} action={<FeedbackButton />}>
      <OutboxBar />
      <View style={styles.status}>
        <Chip tone={courier.status === 'offline' ? 'neutral' : 'accent'} label={t(`shift.status.${courier.status}` as MessageKey)} />
        {courier.vehicle ? <Body muted>{t('shift.vehicle', { vehicle: t(`vehicle.${courier.vehicle}` as MessageKey) })}</Body> : null}
      </View>

      {current ? (
        <Card>
          <Heading>{t('shift.on', { end: time(current.endsAt, zone) })}</Heading>
          <Button
            tone="secondary"
            large
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
          <Heading>{t('shift.next', { day: day(next.startsAt, zone), start: time(next.startsAt, zone), end: time(next.endsAt, zone) })}</Heading>
          <Button
            label={t('shift.start')}
            large
            icon="truck"
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
        <Card highlight onPress={() => router.navigate('/run')} label={t('shift.openRun')} testID="open-run">
          <Text style={textStyles.caps}>{t('today.yourRun')}</Text>
          <Heading level={2}>{run.label ? t('shift.run', { label: run.part > 1 ? `${run.label} · ${run.part}` : run.label }) : t('run.titleNoLabel')}</Heading>
          <View style={styles.figures}>
            <Figure label={t('today.stops')} value={t('today.doneOf', { done, total: run.stops.length })} testID="run-progress" />
            {run.startsAt && run.endsAt ? <Figure label={t('today.window')} value={time(run.startsAt, zone)} sub={t('today.until', { time: time(run.endsAt, zone) })} /> : null}
          </View>
          <Progress done={done} total={run.stops.length} />
          {upNext ? (
            <View style={styles.next}>
              <StepNumber n={upNext.seq} state="next" />
              <View style={styles.flex}>
                <Text style={textStyles.caps}>{t('today.next')}</Text>
                <Body strong>{`${t(kindKey(upNext))} · ${stopName(upNext)}`}</Body>
                {stopAddress(upNext) ? <Body muted>{stopAddress(upNext)}</Body> : null}
              </View>
            </View>
          ) : null}
          <View style={styles.open}>
            <Text style={[textStyles.bodyStrong, styles.link]}>{t('shift.openRun')}</Text>
            <Icon name="caret-right" size={TYPE.price} color={semantic.link} />
          </View>
        </Card>
      ) : current ? (
        <Body muted>{t('shift.noRun')}</Body>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0, gap: space[1] },
  status: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: space[3] },
  figures: { flexDirection: 'row', flexWrap: 'wrap', gap: space[4], paddingTop: space[1] },
  next: { flexDirection: 'row', alignItems: 'center', gap: space[3], padding: space[3], borderRadius: radius.panel, backgroundColor: semantic.well },
  open: { flexDirection: 'row', alignItems: 'center', gap: space[1], paddingTop: space[1], borderTopWidth: LINE.hair, borderTopColor: semantic.border, minHeight: space[10] },
  link: { color: semantic.link },
});
