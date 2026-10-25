import { useLocalSearchParams, useRouter } from 'expo-router';
import { Linking, Platform, StyleSheet, Text, View } from 'react-native';

import { Icon, TYPE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import { MapStrip } from '../../../../src/components/MapStrip';
import { OutboxBar } from '../../../../src/components/tracking';
import { Banner, Body, Button, Card, Chip, Heading, Loading, Row, Screen } from '../../../../src/components/ui';
import { useMarketZone, useRun } from '../../../../src/hooks';
import { useI18n, type MessageKey } from '../../../../src/i18n';
import { services } from '../../../../src/services';
import { kindKey, navigationUrl, pickedUp, stopAddress, stopName } from '../../../../src/stops';

/**
 * One stop (S-179, glanceable in sunlight; redesign spec § 2.5): the schematic map strip with when it's due on an ink
 * tag, "Navigate" (the hand-off to the phone's maps app, a full-width 46 pt button), "Stop {n} · {kind}", where — the
 * address at 16 pt bold — and what, the customer's instructions on the well grey, then the next action in the sticky
 * bottom bar — I've arrived, Pick up, Hand over or the return. No customer name or phone number is shown (the
 * api gives the courier none); there is no in-app call or message yet (DECISIONS S-87). An age-restricted drop-off
 * says the ID check's age; a return stop (2026-10-04: a refused age-restricted order) takes the order back.
 */
export default function StopScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { t, time } = useI18n();
  const router = useRouter();
  const { run, unsent, isPending } = useRun();
  const zone = useMarketZone(run?.market);
  const stop = run?.stops.find((s) => s.id === id);
  const back = () => (router.canGoBack() ? router.back() : router.replace('/run'));

  if (isPending) {
    return (
      <Screen title="" back onBack={back} scroll={false}>
        <Loading label={t('common.loading')} />
      </Screen>
    );
  }
  if (!run || !stop) {
    return (
      <Screen title={t('tab.route')} back onBack={back}>
        <Banner tone="warn">{t('stop.notFound')}</Banner>
        <Button tone="secondary" label={t('common.back')} onPress={back} />
      </Screen>
    );
  }

  const kind = t(kindKey(stop));
  const nav = navigationUrl(stop, Platform.OS);
  const ready = stop.kind === 'pickup' || pickedUp(run, stop);
  const arrive = () => void services().outbox.enqueue({ kind: 'arrive', stopId: stop.id });
  const returned = () => void services().outbox.enqueue({ kind: 'returned', stopId: stop.id });
  const collected = () => void services().outbox.enqueue({ kind: 'collect', stopId: stop.id });
  const open = stop.state !== 'done';

  const footer = open ? (
    <>
      {stop.state === 'pending' ? <Button tone="secondary" label={t('stop.arrive')} onPress={arrive} testID="arrive" /> : null}
      {stop.kind === 'pickup' ? (
        <Button label={t('stop.confirmPickup')} large icon="package" onPress={() => router.push({ pathname: '/stops/[id]/pickup', params: { id: stop.id } })} testID="go-pickup" />
      ) : null}
      {stop.kind === 'dropoff' ? (
        <>
          <Button label={t('stop.handOver')} large icon="check-circle" disabled={!ready} onPress={() => router.push({ pathname: '/stops/[id]/dropoff', params: { id: stop.id } })} testID="go-dropoff" />
          {!ready ? <Body muted>{t('stop.waitPickup')}</Body> : null}
        </>
      ) : null}
      {stop.kind === 'return' ? <Button label={t('stop.returned')} large onPress={returned} testID="returned" /> : null}
      {stop.kind === 'collect' ? <Button label={t('stop.collected')} large icon="package" onPress={collected} testID="collected" /> : null}
    </>
  ) : undefined;

  return (
    <Screen testID="stop-screen" title={kind} back onBack={back} footer={footer}>
      <OutboxBar />
      <MapStrip
        kind={stop.kind}
        label={stop.eta && open ? t('stop.map', { time: time(stop.eta, zone) }) : t('stop.mapNoEta')}
        action={nav ? <Button tone="secondary" large icon="navigation-arrow" label={t('stop.navigate')} hint={t('stop.navigateHint')} onPress={() => void Linking.openURL(nav)} testID="navigate" /> : undefined}
      />
      <View style={styles.head}>
        <Text style={textStyles.caps}>{t('stop.seqKind', { seq: stop.seq, kind })}</Text>
        <Heading>{stopName(stop)}</Heading>
        <Row>
          <Chip tone={unsent.has(stop.id) ? 'warn' : stop.state === 'done' ? 'done' : 'neutral'} label={unsent.has(stop.id) ? t('stop.unsent') : t(`stop.state.${stop.state}` as MessageKey)} />
          {stop.orderRef ? <Body muted>{t('stop.order', { ref: stop.orderRef })}</Body> : null}
          {stop.returnRef ? <Body muted>{t('stop.returnRef', { ref: stop.returnRef })}</Body> : null}
          {stop.failReason ? <Chip tone="warn" label={t('stop.failed')} /> : null}
        </Row>
      </View>
      <Card>
        <Text style={textStyles.caps}>{t('stop.address')}</Text>
        <Row>
          <Icon name="map-pin" size={TYPE.price} />
          <Text style={[textStyles.sub, styles.flex]}>{stopAddress(stop)}</Text>
        </Row>
        {stop.dropoff?.unit ? <Body strong>{t('stop.unit', { unit: stop.dropoff.unit })}</Body> : null}
      </Card>
      {stop.dropoff?.note ? (
        <View style={styles.note}>
          <Row>
            <Icon name="chat-text" size={TYPE.price} />
            <Body strong>{t('stop.note')}</Body>
          </Row>
          <Body>{stop.dropoff.note}</Body>
        </View>
      ) : null}
      {stop.idCheck && open ? <Chip tone="warn" label={t('stop.idCheck', { age: stop.idCheck.age })} /> : null}
      {stop.kind === 'collect' && open ? (
        <Banner tone="info" role="text">
          {t('stop.collectHint', { ref: stop.returnRef ?? stop.orderRef ?? '' })}
        </Banner>
      ) : null}
      {stop.kind === 'return' && open ? (
        <Banner tone="warn" role="text">
          {t('stop.returnHint')}
        </Banner>
      ) : null}
      {stop.kind === 'pickup' ? (
        <Banner tone={stop.packed ? 'info' : 'warn'} role="text">
          {stop.packed ? t('stop.packed') : t('stop.notPacked')}
        </Banner>
      ) : null}
      <Body muted>{t('stop.contact')}</Body>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  head: { gap: space[1] },
  note: { gap: space[2], padding: space[4], borderRadius: radius.panel, backgroundColor: semantic.well },
});
