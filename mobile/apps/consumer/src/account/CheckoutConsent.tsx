import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { useI18n } from '../i18n';
import { Body, Checkbox } from '../ui/primitives';
import { account, KEYS } from './common';

/**
 * S-157: CASL express consent at checkout — an unticked "email me offers" box with the current wording (`/me/consents`),
 * only for someone who hasn't given that consent. A ticked box is recorded once the order or booking went through
 * (`PUT /me/consents/marketing_email`, source `checkout`, the wording version shown); recording it never blocks or
 * fails the purchase. The shop's Pay screen and the booking review use it, as the consumer site's checkouts do.
 */
export function useCheckoutConsent(enabled: boolean) {
  const { locale } = useI18n();
  const qc = useQueryClient();
  const consents = useQuery({ queryKey: KEYS.consents(locale), queryFn: () => account().consents(), enabled, retry: false, staleTime: 60_000 });
  const email = consents.data?.categories.find((c) => c.channel === 'email');
  const [ticked, setTicked] = useState(false);
  const offered = !!email && !email.granted;
  const record = async () => {
    if (!offered || !ticked || !email) return;
    try {
      await account().grantAtCheckout(email.category, email.wordingVersion);
      void qc.invalidateQueries({ queryKey: ['account', 'consents'] });
    } catch {
      // the purchase went through; Account › Notifications still offers it
    }
  };
  return { offered, ticked, setTicked, wording: email?.wording ?? '', requester: consents.data?.requester ?? '', record };
}

export function CheckoutConsent({ consent }: { consent: ReturnType<typeof useCheckoutConsent> }) {
  const { t } = useI18n();
  if (!consent.offered) return null;
  return (
    <View style={styles.box} testID="checkout-consent">
      <Body tone="item">{t('checkout.consentTitle')}</Body>
      <Checkbox checked={consent.ticked} onChange={consent.setTicked} label={consent.wording} testID="checkout-consent-email">
        <Body tone="small">{consent.wording}</Body>
      </Checkbox>
      <Body tone="small">{t('checkout.consentWho', { requester: consent.requester })}</Body>
    </View>
  );
}

const styles = StyleSheet.create({ box: { gap: space[2], paddingTop: space[3] } });
