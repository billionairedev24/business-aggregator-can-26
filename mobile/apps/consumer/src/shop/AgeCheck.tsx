import { useMutation, useQueryClient } from '@tanstack/react-query';
import * as WebBrowser from 'expo-web-browser';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import type { CheckoutAge } from '../api/shop';
import { config } from '../config';
import type { MessageKey } from '../i18n';
import { Body, Button, Notice } from '../ui/primitives';
import { shop, useShopFormat } from './common';
import { Kicker } from './parts';

/**
 * 2026-10-04 checkout's age step (no design drawing; copy is ours, en + fr-CA, the same as the consumer web): a cart
 * with age-restricted items needs a customer verified old enough for the delivery province (the api's `age` on the
 * checkout and the quote). Verify opens the identity provider's hosted check (Stripe Identity: photo ID + selfie) in
 * the in-app browser, which comes back to the app; then the checkout is re-read. Nothing about the ID stays on the
 * phone, and the api keeps only "verified over N, on date, by method".
 */
export function AgeCheck({ age }: { age: CheckoutAge }) {
  const { t } = useShopFormat();
  const qc = useQueryClient();
  const verify = useMutation({
    mutationFn: async () => {
      const started = await shop().startAgeCheck();
      if (!started) throw new Error('no session');
      await WebBrowser.openAuthSessionAsync(started.url, config.ageReturnUri);
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ['shop', 'checkout'] }),
  });
  const classes = age.classes.map((c) => t(`shop.age.cls.${c}` as MessageKey)).join(t('shop.age.and'));
  const done = age.state === 'verified';
  return (
    <View style={styles.box} testID="age-check">
      <Kicker>{done ? t('shop.age.titleDone') : t('shop.age.title')}</Kicker>
      {done ? (
        <Body tone="muted">{t('shop.age.verified', { age: age.minimumAge })}</Body>
      ) : age.state === 'under_age' ? (
        <Notice message={t('shop.age.underAge', { age: age.minimumAge })} />
      ) : (
        <>
          <Body>{t('shop.age.why', { classes, age: age.minimumAge })}</Body>
          <Body tone="small">{t('shop.age.how')}</Body>
          {age.state === 'pending' ? <Notice tone="info" message={t('shop.age.pending')} /> : null}
          {age.state === 'failed' ? <Notice message={t('shop.age.failed')} /> : null}
          {verify.isError ? <Notice message={t('shop.age.error')} /> : null}
          <Button
            label={age.state === 'pending' ? t('shop.age.checkAgain') : t('shop.age.verify')}
            tone={age.state === 'pending' ? 'secondary' : 'primary'}
            busy={verify.isPending}
            onPress={() => (age.state === 'pending' ? void qc.invalidateQueries({ queryKey: ['shop', 'checkout'] }) : verify.mutate())}
            testID="age-verify"
          />
        </>
      )}
      <Body tone="small">{t('shop.age.door')}</Body>
    </View>
  );
}

const styles = StyleSheet.create({ box: { gap: space[2] } });
