import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { colors, radius, space } from '@northline/mobile-kit';

import { usePhonePush } from '../account/phonePush';
import { useI18n } from '../i18n';
import { services } from '../services';
import { Body, Button } from '../ui/primitives';

/** Set once the person answered the card (turned on or "Not now"): the card never comes back by itself. */
export const PUSH_ASKED_KEY = 'nl.app.pushAsked';

/**
 * The moment the app asks for notifications (mobile gaps part 1): after the person's order is placed or booking made —
 * when "your order is on its way" means something — never at first launch. Shown only while the phone hasn't been
 * asked (permission undetermined) and the person hasn't answered this card; Account › Notifications › "This phone"
 * stays the way to turn it on later.
 */
export function PushPrompt({ what }: { what: 'order' | 'booking' }) {
  const { t } = useI18n();
  const push = usePhonePush();
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!push) return;
    let alive = true;
    void Promise.all([push.permission(), services().store.getItem(PUSH_ASKED_KEY).catch(() => null)])
      .then(([permission, asked]) => alive && setShow(permission === 'undetermined' && asked !== '1'))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [push]);
  if (!push || !show) return null;
  const answered = () => {
    setShow(false);
    void services().store.setItem(PUSH_ASKED_KEY, '1').catch(() => undefined);
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
    <View style={styles.card} testID="push-prompt">
      <Body>{t(what === 'order' ? 'push.prompt.order' : 'push.prompt.booking')}</Body>
      <View style={styles.row}>
        <Button label={t('push.prompt.turnOn')} busy={busy} onPress={() => void turnOn()} testID="push-prompt-on" />
        <Button label={t('push.prompt.later')} tone="ghost" onPress={answered} testID="push-prompt-later" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { gap: space[2], padding: space[3], borderRadius: radius.md, borderWidth: 1, borderColor: colors.accent, backgroundColor: colors.accent100 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
});
