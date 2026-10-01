import { createContext, useContext } from 'react';
import { Linking } from 'react-native';

import { useOutbox } from '../hooks';
import { useI18n } from '../i18n';
import type { LocationAccess } from '../location/tracker';
import { services } from '../services';
import { Banner, Body, Button, Card, Heading } from './ui';

export interface Tracking {
  access: LocationAccess;
  allow(): Promise<void>;
  online: boolean;
}

export const TrackingContext = createContext<Tracking>({ access: 'unavailable', allow: async () => undefined, online: true });

export const useTracking = () => useContext(TrackingContext);

/** The run's location card: why, what is kept, and the state of the permission. */
export function LocationCard() {
  const { t } = useI18n();
  const { access, allow } = useTracking();
  if (access === 'background') return <Banner role="text">{t('location.on')}</Banner>;
  if (access === 'unavailable') return <Banner role="text">{t('location.unavailable')}</Banner>;
  if (access === 'foreground') {
    return (
      <Banner tone="warn" action={<Button tone="ghost" label={t('location.openSettings')} onPress={() => void Linking.openSettings()} />}>
        {t('location.foregroundOnly')}
      </Banner>
    );
  }
  if (access === 'denied') {
    return (
      <Banner tone="warn" action={<Button tone="ghost" label={t('location.openSettings')} onPress={() => void Linking.openSettings()} />}>
        {t('location.denied')}
      </Banner>
    );
  }
  return (
    <Card testID="location-card">
      <Heading level={2}>{t('location.title')}</Heading>
      <Body>{t('location.body')}</Body>
      <Button label={t('location.allow')} onPress={() => void allow()} />
    </Card>
  );
}

/** What is still on the phone, and what the api refused. */
export function OutboxBar() {
  const { t } = useI18n();
  const { online } = useTracking();
  const { pending, failures, retryAt } = useOutbox();
  return (
    <>
      {!online ? <Banner tone="warn">{t('common.offline')}</Banner> : null}
      {pending.length > 0 ? (
        <Banner
          tone={retryAt ? 'warn' : 'info'}
          action={retryAt ? <Button tone="ghost" label={t('outbox.retryNow')} onPress={() => services().outbox.wake()} /> : undefined}
        >
          {retryAt ? t('outbox.waiting', { n: pending.length }) : t('outbox.sending', { n: pending.length })}
        </Banner>
      ) : null}
      {failures.map((f) => (
        <Banner
          key={f.action.id}
          tone="error"
          action={<Button tone="ghost" label={t('common.dismiss')} onPress={() => services().outbox.dismissFailure(f.action.id)} />}
        >
          {t('outbox.failed', { message: f.message })}
        </Banner>
      ))}
    </>
  );
}
