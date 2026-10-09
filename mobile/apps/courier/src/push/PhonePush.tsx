import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useState } from 'react';

import type { PushPermission } from '@northline/mobile-kit';

import { Banner, Body, Button, Card, Chip, Row } from '../components/ui';
import { useI18n } from '../i18n';
import { usePhonePush } from './install';

/** Set once the courier answered the shift screen's card: it never comes back by itself (Account keeps the row). */
export const PUSH_ASKED_KEY = 'nl.courier.pushAsked';

function usePermission() {
  const push = usePhonePush();
  const [permission, setPermission] = useState<PushPermission | null>(null);
  useEffect(() => {
    if (!push) return;
    void push.permission().then(setPermission).catch(() => setPermission(null));
  }, [push]);
  return { push, permission, setPermission };
}

/** Account › "This phone": whether run notifications reach this phone, and the way to turn them on. */
export function ThisPhone() {
  const { t } = useI18n();
  const { push, permission, setPermission } = usePermission();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  if (!push || !permission) return null;
  const on = permission === 'granted' || permission === 'provisional';
  const enable = async () => {
    setBusy(true);
    setFailed(false);
    try {
      setPermission(await push.registration.enable());
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card testID="this-phone">
      <Row>
        <Body strong>{t('push.phone')}</Body>
        <Chip tone={on ? 'accent' : 'neutral'} label={t(on ? 'push.on' : 'push.off')} />
      </Row>
      <Body muted>{t(on ? 'push.onBody' : permission === 'denied' ? 'push.denied' : 'push.offBody')}</Body>
      {!on && permission === 'undetermined' ? <Button label={t('push.turnOn')} busy={busy} onPress={() => void enable()} testID="push-enable" /> : null}
      {!on && permission === 'denied' ? <Button tone="secondary" label={t('push.openSettings')} onPress={() => void push.openSettings()} testID="push-settings" /> : null}
      {failed ? <Banner tone="error">{t('push.failed')}</Banner> : null}
    </Card>
  );
}

/**
 * The moment the courier app asks (mobile gaps part 1): on the shift screen once the courier is on shift — when a "New
 * run" notification is useful — never at launch. Only while the phone hasn't been asked and the card wasn't answered.
 */
export function PushPrompt() {
  const { t } = useI18n();
  const { push, permission } = usePermission();
  const [asked, setAsked] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    void AsyncStorage.getItem(PUSH_ASKED_KEY)
      .then((v) => setAsked(v === '1'))
      .catch(() => setAsked(false));
  }, []);
  if (!push || permission !== 'undetermined' || asked !== false) return null;
  const answered = () => {
    setAsked(true);
    void AsyncStorage.setItem(PUSH_ASKED_KEY, '1').catch(() => undefined);
  };
  const turnOn = async () => {
    setBusy(true);
    try {
      await push.registration.enable();
    } catch {
      // offline: the registry is told at the next start
    } finally {
      setBusy(false);
      answered();
    }
  };
  return (
    <Card testID="push-prompt">
      <Body>{t('push.prompt')}</Body>
      <Button label={t('push.turnOn')} busy={busy} onPress={() => void turnOn()} testID="push-prompt-on" />
      <Button tone="ghost" label={t('push.later')} onPress={answered} testID="push-prompt-later" />
    </Card>
  );
}
