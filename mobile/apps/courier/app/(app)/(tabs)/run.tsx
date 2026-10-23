import { useRouter } from 'expo-router';
import { Linking, Platform, StyleSheet, Text, View } from 'react-native';

import { Icon, TYPE, semantic, space, textStyles } from '@northline/mobile-kit';

import type { Stop } from '../../../src/api/courier';
import { LocationCard, OutboxBar } from '../../../src/components/tracking';
import { Body, Button, Card, Chip, Figure, Loading, Progress, Screen, StepNumber } from '../../../src/components/ui';
import { useMarketZone, useRun } from '../../../src/hooks';
import { useI18n, type MessageKey } from '../../../src/i18n';
import { FeedbackButton } from '../../../src/pilot/Feedback';
import { kindKey, navigationUrl, nextStop, orderedStops, stopAddress, stopName } from '../../../src/stops';

/**
 * Route (S-179, glanceable in sunlight; redesign spec § 2.5 → AT DR:132 ROUTE): the stops done and left as 28 pt
 * figures over an ink progress bar, the run's window, "Navigate to stop n" (the hand-off to the phone's maps app) for
 * the next stop, location sharing while the run is open, then the stops in the api's order — the number in a square
 * (solid ink for the next, ✓ once done), "{kind} · {name}", the address, when it's due, its state as a large tag; the
 * next one outlined in ink. A stop opens its screen above the tabs.
 */
export default function RunScreen() {
  const { t, time } = useI18n();
  const router = useRouter();
  const { run, unsent, isPending } = useRun();
  const zone = useMarketZone(run?.market);
  const title = run?.label ? t('run.title', { label: run.part > 1 ? `${run.label} · ${run.part}` : run.label }) : t('tab.route');

  if (isPending) {
    return (
      <Screen title={title} scroll={false}>
        <Loading label={t('common.loading')} />
      </Screen>
    );
  }
  if (!run) {
    return (
      <Screen testID="run-screen" title={title}>
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
  const done = stops.filter((s) => s.state === 'done').length;
  const stateLabel = (s: Stop) => (unsent.has(s.id) ? t('stop.unsent') : t(`stop.state.${s.state}` as MessageKey));

  const nav = next ? navigationUrl(next, Platform.OS) : null;

  return (
    <Screen testID="run-screen" title={title} action={<FeedbackButton />}>
      <OutboxBar />
      <View style={styles.summary} accessible accessibilityLabel={`${t('run.progressBar', { done, total: stops.length })}. ${t('run.stops', { n: stops.length })}`}>
        <View style={styles.figures}>
          <Figure label={t('run.doneCount')} value={t('today.doneOf', { done, total: stops.length })} />
          <Figure label={t('run.left')} value={String(stops.length - done)} sub={run.startsAt && run.endsAt ? t('run.window', { start: time(run.startsAt, zone), end: time(run.endsAt, zone) }) : undefined} />
        </View>
        <Progress done={done} total={stops.length} />
        <Text style={textStyles.label}>{t('run.progressBar', { done, total: stops.length })}</Text>
      </View>
      {next && nav ? (
        <Button tone="secondary" large icon="navigation-arrow" label={t('run.navigateNext', { seq: next.seq })} hint={t('stop.navigateHint')} onPress={() => void Linking.openURL(nav)} testID="navigate-next" />
      ) : null}
      <LocationCard />
      {stops.map((s) => {
        const kind = t(kindKey(s));
        const isNext = s.id === next?.id;
        const isDone = s.state === 'done';
        return (
          <Card
            key={s.id}
            highlight={isNext}
            onPress={() => router.push({ pathname: '/stops/[id]', params: { id: s.id } })}
            label={t('run.stopLabel', { seq: s.seq, total: stops.length, kind, name: stopName(s), state: stateLabel(s) })}
            testID={`stop-${s.id}`}
          >
            <View style={styles.stopRow}>
              <StepNumber n={s.seq} state={isDone ? 'done' : isNext ? 'next' : 'later'} />
              <View style={styles.stopText}>
                <Text style={textStyles.sub}>
                  {kind} · {stopName(s)}
                </Text>
                {stopAddress(s) ? <Body muted>{stopAddress(s)}</Body> : null}
                <View style={styles.meta}>
                  <Chip tone={unsent.has(s.id) ? 'warn' : isDone ? 'done' : isNext ? 'accent' : 'neutral'} label={isNext && !isDone ? t('run.next') : stateLabel(s)} />
                  {s.eta && !isDone ? <Body strong>{t('stop.eta', { time: time(s.eta, zone) })}</Body> : null}
                  {s.orderRef ? <Body muted>{t('stop.order', { ref: s.orderRef })}</Body> : null}
                </View>
              </View>
              <Icon name="caret-right" size={TYPE.price} color={semantic['text-muted']} />
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

const styles = StyleSheet.create({
  summary: { gap: space[2] },
  figures: { flexDirection: 'row', flexWrap: 'wrap', gap: space[4] },
  stopRow: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  stopText: { flex: 1, minWidth: 0, gap: space[1] },
  meta: { flexDirection: 'row', alignItems: 'center', gap: space[2], flexWrap: 'wrap' },
});
