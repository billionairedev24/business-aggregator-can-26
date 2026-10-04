import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { Linking, Platform } from 'react-native';

import { OutboxBar } from '../../../../src/components/tracking';
import { Banner, Body, Button, Card, Chip, Heading, Loading, Row, Screen } from '../../../../src/components/ui';
import { useMarketZone, useRun } from '../../../../src/hooks';
import { useI18n, type MessageKey } from '../../../../src/i18n';
import { services } from '../../../../src/services';
import { kindKey, mapsUrl, pickedUp, stopAddress, stopName } from '../../../../src/stops';

/**
 * One stop: where, what, and the next action. No customer name or phone number is shown (the api gives the courier
 * none); there is no in-app call or message yet (DECISIONS S-87). An age-restricted drop-off says the ID check's age;
 * a return stop (2026-10-04: a refused age-restricted order) takes the order back to the business.
 */
export default function StopScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { t, time } = useI18n();
  const router = useRouter();
  const { run, unsent, isPending } = useRun();
  const zone = useMarketZone(run?.market);
  const stop = run?.stops.find((s) => s.id === id);

  if (isPending) return <Loading label={t('common.loading')} />;
  if (!run || !stop) {
    return (
      <Screen>
        <Banner tone="warn">{t('stop.notFound')}</Banner>
        <Button tone="secondary" label={t('common.back')} onPress={() => router.back()} />
      </Screen>
    );
  }

  const kind = t(kindKey(stop));
  const maps = mapsUrl(stop, Platform.OS);
  const ready = stop.kind === 'pickup' || pickedUp(run, stop);
  const arrive = () => void services().outbox.enqueue({ kind: 'arrive', stopId: stop.id });
  const returned = () => void services().outbox.enqueue({ kind: 'returned', stopId: stop.id });

  return (
    <Screen testID="stop-screen">
      <Stack.Screen options={{ title: kind }} />
      <OutboxBar />
      <Row>
        <Chip tone={unsent.has(stop.id) ? 'warn' : stop.state === 'done' ? 'done' : 'neutral'} label={unsent.has(stop.id) ? t('stop.unsent') : t(`stop.state.${stop.state}` as MessageKey)} />
        {stop.eta && stop.state !== 'done' ? <Body muted>{t('stop.eta', { time: time(stop.eta, zone) })}</Body> : null}
      </Row>
      <Heading>{stopName(stop)}</Heading>
      <Card>
        <Body>{stopAddress(stop)}</Body>
        {stop.dropoff?.unit ? <Body strong>{t('stop.unit', { unit: stop.dropoff.unit })}</Body> : null}
        {stop.orderRef ? <Body muted>{t('stop.order', { ref: stop.orderRef })}</Body> : null}
        {maps ? <Button tone="ghost" label={t('stop.openMaps')} onPress={() => void Linking.openURL(maps)} /> : null}
      </Card>
      {stop.dropoff?.note ? (
        <Card>
          <Body strong>{t('stop.note')}</Body>
          <Body>{stop.dropoff.note}</Body>
        </Card>
      ) : null}
      {stop.idCheck && stop.state !== 'done' ? <Chip tone="warn" label={t('stop.idCheck', { age: stop.idCheck.age })} /> : null}
      {stop.kind === 'return' && stop.state !== 'done' ? (
        <Banner tone="warn" role="text">
          {t('stop.returnHint')}
        </Banner>
      ) : null}
      {stop.kind === 'pickup' ? (
        <Banner tone={stop.packed ? 'info' : 'warn'} role="text">
          {stop.packed ? t('stop.packed') : t('stop.notPacked')}
        </Banner>
      ) : null}

      {stop.state === 'pending' ? <Button tone="secondary" label={t('stop.arrive')} onPress={arrive} testID="arrive" /> : null}
      {stop.state !== 'done' && stop.kind === 'pickup' ? (
        <Button label={t('stop.confirmPickup')} onPress={() => router.push({ pathname: '/stops/[id]/pickup', params: { id: stop.id } })} testID="go-pickup" />
      ) : null}
      {stop.state !== 'done' && stop.kind === 'dropoff' ? (
        <>
          <Button label={t('stop.handOver')} disabled={!ready} onPress={() => router.push({ pathname: '/stops/[id]/dropoff', params: { id: stop.id } })} testID="go-dropoff" />
          {!ready ? <Body muted>{t('stop.waitPickup')}</Body> : null}
        </>
      ) : null}
      {stop.state !== 'done' && stop.kind === 'return' ? <Button label={t('stop.returned')} onPress={returned} testID="returned" /> : null}
      <Body muted>{t('stop.contact')}</Body>
    </Screen>
  );
}
