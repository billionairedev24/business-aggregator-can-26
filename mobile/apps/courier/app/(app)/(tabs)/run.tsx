import { useRouter } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { Icon, colors, fonts, radius, semantic, space } from '@northline/mobile-kit';

import type { Stop } from '../../../src/api/courier';
import { LocationCard, OutboxBar } from '../../../src/components/tracking';
import { Body, Card, Chip, IconBadge, Loading, Screen } from '../../../src/components/ui';
import { useMarketZone, useRun } from '../../../src/hooks';
import { useI18n, type MessageKey } from '../../../src/i18n';
import { FeedbackButton } from '../../../src/pilot/Feedback';
import { kindKey, nextStop, orderedStops, stopAddress, stopName } from '../../../src/stops';

/**
 * Route (redesign spec § 2.5 → AT DR:132 ROUTE): "{done} of {n} stops done" over a progress bar, location sharing while
 * the run is open, then the stops in the api's order — a marker per kind, "{seq}. {kind} · {name}", the address, when
 * it's due, its state; the next one outlined. A stop opens its screen above the tabs.
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

  return (
    <Screen testID="run-screen" title={title} action={<FeedbackButton />}>
      <OutboxBar />
      <View style={styles.summary} accessible accessibilityLabel={`${t('run.progressBar', { done, total: stops.length })}. ${t('run.stops', { n: stops.length })}`}>
        <View style={styles.summaryRow}>
          <Text style={styles.summaryTitle}>{t('run.progressBar', { done, total: stops.length })}</Text>
          {run.startsAt && run.endsAt ? <Body muted>{t('run.window', { start: time(run.startsAt, zone), end: time(run.endsAt, zone) })}</Body> : null}
        </View>
        <View style={styles.track}>
          <View style={[styles.fill, { width: `${stops.length ? Math.round((done / stops.length) * 100) : 0}%` }]} />
        </View>
      </View>
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
              <IconBadge name={isDone ? 'check' : s.kind === 'pickup' ? 'storefront' : s.kind === 'return' ? 'arrow-u-up-left' : 'map-pin'} tone={isDone ? 'done' : isNext ? 'dark' : 'brand'} />
              <View style={styles.stopText}>
                <Text style={styles.stopTitle}>
                  {s.seq}. {kind} · {stopName(s)}
                </Text>
                {stopAddress(s) ? (
                  <Body muted style={styles.small}>
                    {stopAddress(s)}
                  </Body>
                ) : null}
                <View style={styles.meta}>
                  <Chip tone={unsent.has(s.id) ? 'warn' : isDone ? 'done' : isNext ? 'accent' : 'neutral'} label={isNext && !isDone ? t('run.next') : stateLabel(s)} />
                  {s.eta && !isDone ? <Body muted style={styles.small}>{t('stop.eta', { time: time(s.eta, zone) })}</Body> : null}
                  {s.orderRef ? <Body muted style={styles.small}>{t('stop.order', { ref: s.orderRef })}</Body> : null}
                </View>
              </View>
              <Icon name="caret-right" size={16} color={colors.textMuted} />
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

const styles = StyleSheet.create({
  summary: { gap: space[2] },
  summaryRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', gap: space[2], flexWrap: 'wrap' },
  summaryTitle: { fontFamily: fonts.bold, fontSize: 17, color: colors.text },
  track: { height: 8, borderRadius: radius.pill, backgroundColor: semantic['surface-muted'], overflow: 'hidden' },
  fill: { height: 8, borderRadius: radius.pill, backgroundColor: colors.actionPrimary },
  stopRow: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  stopText: { flex: 1, minWidth: 0, gap: 4 },
  stopTitle: { fontFamily: fonts.bold, fontSize: 16, lineHeight: 21, color: colors.text },
  small: { fontSize: 14, lineHeight: 19 },
  meta: { flexDirection: 'row', alignItems: 'center', gap: space[2], flexWrap: 'wrap' },
});
