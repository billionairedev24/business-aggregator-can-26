import { Stack, useRouter } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { colors, fonts, radius } from '@northline/mobile-kit';

import type { Stop } from '../../src/api/courier';
import { LocationCard, OutboxBar } from '../../src/components/tracking';
import { Body, Card, Chip, Heading, Loading, Row, Screen } from '../../src/components/ui';
import { useMarketZone, useRun } from '../../src/hooks';
import { useI18n, type MessageKey } from '../../src/i18n';
import { nextStop, orderedStops, stopName } from '../../src/stops';

/** The open run: its stops in the api's order, the next one first in mind, location sharing while it is open. */
export default function RunScreen() {
  const { t, time } = useI18n();
  const router = useRouter();
  const { run, unsent, isPending } = useRun();
  const zone = useMarketZone(run?.market);

  if (isPending) return <Loading label={t('common.loading')} />;
  if (!run) {
    return (
      <Screen testID="run-screen">
        <OutboxBar />
        <Card>
          <Body>{t('run.empty')}</Body>
          <Body muted>{t('shift.noRun')}</Body>
        </Card>
      </Screen>
    );
  }

  const stops = orderedStops(run);
  const next = nextStop(run);
  const label = run.label ? (run.part > 1 ? `${run.label} · ${run.part}` : run.label) : null;
  const stateLabel = (s: Stop) => (unsent.has(s.id) ? t('stop.unsent') : t(`stop.state.${s.state}` as MessageKey));

  return (
    <Screen testID="run-screen">
      <Stack.Screen options={{ title: label ? t('run.title', { label }) : t('run.titleNoLabel') }} />
      <OutboxBar />
      <Row>
        <Body muted>{t('run.stops', { n: stops.length })}</Body>
        {run.startsAt && run.endsAt ? <Body muted>· {t('run.window', { start: time(run.startsAt, zone), end: time(run.endsAt, zone) })}</Body> : null}
      </Row>
      <LocationCard />
      {stops.map((s) => {
        const kind = s.kind === 'pickup' ? t('stop.pickup') : t('stop.dropoff');
        const isNext = s.id === next?.id;
        return (
          <Card
            key={s.id}
            highlight={isNext}
            onPress={() => router.push({ pathname: '/stops/[id]', params: { id: s.id } })}
            label={t('run.stopLabel', { seq: s.seq, total: stops.length, kind, name: stopName(s), state: stateLabel(s) })}
            testID={`stop-${s.id}`}
          >
            <Row>
              <View style={[styles.seq, s.state === 'done' && styles.seqDone]}>
                <Text style={[styles.seqText, s.state === 'done' && styles.seqTextDone]}>{s.seq}</Text>
              </View>
              <Body strong>{kind}</Body>
              {isNext ? <Chip tone="accent" label={t('run.next')} /> : null}
            </Row>
            <Heading level={2}>{stopName(s)}</Heading>
            <Row>
              <Chip tone={unsent.has(s.id) ? 'warn' : s.state === 'done' ? 'done' : 'neutral'} label={stateLabel(s)} />
              {s.eta && s.state !== 'done' ? <Body muted>{t('stop.eta', { time: time(s.eta, zone) })}</Body> : null}
              {s.orderRef ? <Body muted>{t('stop.order', { ref: s.orderRef })}</Body> : null}
            </Row>
          </Card>
        );
      })}
    </Screen>
  );
}

const styles = StyleSheet.create({
  seq: { width: 32, height: 32, borderRadius: radius.pill, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center' },
  seqDone: { backgroundColor: colors.accent100 },
  seqText: { color: colors.onAccent, fontFamily: fonts.bodyStrong, fontSize: 15 },
  seqTextDone: { color: colors.accent },
});
