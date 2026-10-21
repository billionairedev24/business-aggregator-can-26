import { useEffect, useState } from 'react';
import { Checkbox, useLocale } from '@northline/ui';
import { authApi, type AccountApp, type ConsentWordings, type MarketingConsent } from './api';
import { useAuthKitT } from './messages';
import './account.css';

/**
 * S-157: the sign-up forms' marketing checkboxes (CASL express consent) — unticked, optional, one per channel the app
 * offers (consumer: email and texts; Studio: email), each labelled with the server's versioned wording and followed by
 * who asks. The words come from northline-auth (`consent-wordings`); if they can't be loaded the boxes aren't shown (no
 * wording, no consent). `chosen` is what the form sends: the ticked boxes with the version shown.
 */
export function useMarketingConsents(app: AccountApp) {
  const { locale } = useLocale();
  const [wordings, setWordings] = useState<ConsentWordings | null>(null);
  const [ticked, setTicked] = useState<Record<string, boolean>>({});
  useEffect(() => {
    let live = true;
    authApi.consentWordings(app, locale).then(w => { if (live) setWordings(w); }).catch(() => { if (live) setWordings(null); });
    return () => { live = false; };
  }, [app, locale]);
  const chosen = (): MarketingConsent[] =>
    (wordings?.items ?? []).filter(i => ticked[i.category]).map(i => ({ category: i.category, wordingVersion: i.wordingVersion }));
  return { wordings, ticked, toggle: (category: string, on: boolean) => setTicked(t => ({ ...t, [category]: on })), chosen };
}

export function MarketingConsents({ app, consents, hasPhone = true, error }: {
  app: AccountApp; consents: ReturnType<typeof useMarketingConsents>; hasPhone?: boolean; error?: string;
}) {
  const t = useAuthKitT();
  const w = consents.wordings;
  if (!w || w.items.length === 0) return null;
  return (
    <fieldset className="nl-acct-marketing">
      <legend className="nl-acct-marketing-title">{t('marketingTitle')}</legend>
      {w.items.map(item => {
        const needsPhone = item.category === 'marketing_sms' && !hasPhone;
        return (
          <div key={item.category}>
            <Checkbox name={item.category} checked={!needsPhone && !!consents.ticked[item.category]} disabled={needsPhone}
              onChange={on => consents.toggle(item.category, on)} label={item.wording} />
            {needsPhone ? <p className="nl-acct-small">{t('marketingSmsNeedsPhone')}</p> : null}
          </div>
        );
      })}
      <p className="nl-acct-small">{t(app === 'studio' ? 'marketingNoteStudio' : 'marketingNoteConsumer')} {t('marketingWho', { requester: w.requester })}</p>
      {error ? <div role="alert" className="nl-error">{error}</div> : null}
    </fieldset>
  );
}
