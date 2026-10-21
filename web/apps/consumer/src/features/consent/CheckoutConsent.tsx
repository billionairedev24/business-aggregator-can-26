import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@northline/client';
import { Checkbox, defineMessages, useLocale } from '@northline/ui';
import { consentsQuery } from '../account/settingsApi';
import './consent.css';

const useT = defineMessages({
  en: { title: 'Offers by email (optional)', who: 'Sent by {requester}. Change your mind any time in Account › Notifications.' },
  fr: { title: 'Offres par courriel (facultatif)', who: 'Envoyé par {requester}. Changez d’avis en tout temps dans Compte › Notifications.' },
});

/**
 * S-157: CASL express consent at checkout — an unticked "email me offers" box with the current wording (from
 * `GET /api/v1/me/consents`), shown only to someone who hasn't given that consent. A ticked box is recorded once the
 * order or booking went through (`PUT /me/consents/marketing_email`, source `checkout`, the wording version shown); the
 * recording never blocks or fails the purchase. Shop, food and bookings use it.
 */
export function useCheckoutConsent(enabled = true) {
  const { locale } = useLocale();
  const qc = useQueryClient();
  const consents = useQuery({ ...consentsQuery(locale), enabled, retry: false, staleTime: 60_000 });
  const email = consents.data?.categories.find(c => c.channel === 'email');
  const [ticked, setTicked] = useState(false);
  const offered = !!email && !email.granted;
  const record = async () => {
    if (!offered || !ticked || !email) return;
    try {
      await http(`/api/v1/me/consents/${encodeURIComponent(email.category)}`, { method: 'PUT', body: { granted: true, source: 'checkout', wordingVersion: email.wordingVersion } });
      void qc.invalidateQueries({ queryKey: ['me', 'consents'] });
    } catch {
      // the purchase went through; the person can still say yes in Account › Notifications
    }
  };
  return { offered, ticked, setTicked, wording: email?.wording ?? '', requester: consents.data?.requester ?? '', record };
}

export function CheckoutConsent({ consent }: { consent: ReturnType<typeof useCheckoutConsent> }) {
  const t = useT();
  if (!consent.offered) return null;
  return (
    <div className="nl-co-consent">
      <span className="nl-co-consent-title">{t('title')}</span>
      <Checkbox name="marketing_email" checked={consent.ticked} onChange={consent.setTicked} label={consent.wording} />
      <p className="nl-co-consent-who">{t('who', { requester: consent.requester })}</p>
    </div>
  );
}
