import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { ApiError, Icon, NetworkError, colors, radius, semantic, space, Scene } from '@northline/mobile-kit';

import type { Shift } from '../../../src/api/courier';
import { OutboxBar } from '../../../src/components/tracking';
import { Banner, Body, Button, Card, Chip, Heading, IconBadge, Loading, Row, Screen } from '../../../src/components/ui';
import { useMarketZone, useMe, useRun, useShifts } from '../../../src/hooks';
import { useI18n, type MessageKey } from '../../../src/i18n';
import { FeedbackButton } from '../../../src/pilot/Feedback';
import { PushPrompt } from '../../../src/push/PhonePush';
import { ME_KEY, RUN_KEY, SHIFTS_KEY, services } from '../../../src/services';
import { kindKey, nextStop, stopName } from '../../../src/stops';

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
 * Today (redesign spec § 2.5; the light direction): the courier's status, the shift (start 15 minutes early / end), the
 * run as a picture-first card with its progress and the next stop — the way into Route — and the run notifications
 * prompt.
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
        <IconBadge name={courier.status === 'offline' ? 'clock' : courier.status === 'on_run' ? 'truck' : 'check-circle'} tone={courier.status === 'offline' ? 'muted' : 'brand'} />
        <View style={styles.flex}>
          <Chip tone={courier.status === 'offline' ? 'neutral' : 'accent'} label={t(`shift.status.${courier.status}` as MessageKey)} />
          {courier.vehicle ? <Body muted>{t('shift.vehicle', { vehicle: t(`vehicle.${courier.vehicle}` as MessageKey) })}</Body> : null}
        </View>
      </View>

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
        <Card highlight flush onPress={() => router.navigate('/run')} label={t('shift.openRun')} testID="open-run">
          <Scene slot="hero/courier" subject="courier" tone="sage" fit="contain" style={styles.art} />
          <View style={styles.runBody}>
            <Row>
              <View style={styles.flex}>
                <Heading level={2}>{run.label ? t('shift.run', { label: run.part > 1 ? `${run.label} · ${run.part}` : run.label }) : t('run.titleNoLabel')}</Heading>
                <Body muted>{t('shift.runProgress', { done, total: run.stops.length })}</Body>
              </View>
              <Icon name="caret-right" size={18} />
            </Row>
            <View style={styles.track} accessible={false}>
              <View style={[styles.fill, { width: `${run.stops.length ? Math.round((done / run.stops.length) * 100) : 0}%` }]} />
            </View>
            {upNext ? (
              <View style={styles.next}>
                <IconBadge name={upNext.kind === 'dropoff' ? 'map-pin' : 'storefront'} size={36} />
                <View style={styles.flex}>
                  <Body muted style={styles.small}>
                    {t('today.next')}
                  </Body>
                  <Body strong>{`${t(kindKey(upNext))} · ${stopName(upNext)}`}</Body>
                </View>
              </View>
            ) : null}
            <Body strong style={styles.link}>
              {t('shift.openRun')}
            </Body>
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
  status: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  art: { height: 130 },
  runBody: { padding: space[4], gap: space[3] },
  next: { flexDirection: 'row', alignItems: 'center', gap: space[3], padding: space[3], borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'] },
  small: { fontSize: 13, lineHeight: 18 },
  link: { color: semantic.link },
  track: { height: 6, borderRadius: radius.status, backgroundColor: semantic['surface-muted'], overflow: 'hidden' },
  fill: { height: 6, borderRadius: radius.status, backgroundColor: colors.actionPrimary },
});
