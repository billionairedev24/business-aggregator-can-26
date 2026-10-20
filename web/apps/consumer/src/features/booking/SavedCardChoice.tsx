import { useQuery } from '@tanstack/react-query';
import { defineMessages } from '@northline/ui';
import { cardsQuery } from '../account/settingsApi';

const useCardT = defineMessages({
  en: { payWith: 'Pay with', saved: '{brand} ··{last4} · expires {month}/{year}', newCard: 'A new card', savedNote: 'Your bank may still ask you to confirm it.' },
  fr: { payWith: 'Payer avec', saved: '{brand} ··{last4} · exp. {month}/{year}', newCard: 'Une nouvelle carte', savedNote: 'Votre banque pourrait quand même vous demander de la confirmer.' },
});

/**
 * S-153: one of the customer's saved cards (Account › Payment methods, S-59) or a new one, for a booking, a quote's
 * deposit or a balance. Shown only when the person has saved cards; `undefined` = a new card in Stripe's form.
 */
export function SavedCardChoice({ value, onChange, disabled }: { value: string | undefined; onChange: (id: string | undefined) => void; disabled?: boolean }) {
  const t = useCardT();
  const cards = useQuery(cardsQuery);
  const items = cards.data?.items ?? [];
  if (items.length === 0) return null;
  const brand = (b: string) => b.charAt(0).toUpperCase() + b.slice(1);
  return (
    <fieldset className="nl-cardchoice" disabled={disabled}>
      <legend className="nl-cardchoice-legend">{t('payWith')}</legend>
      {items.map(c => (
        <label key={c.id} className="nl-cardchoice-row">
          <input type="radio" name="nl-card" checked={value === c.id} onChange={() => onChange(c.id)} />
          <span>{t('saved', { brand: brand(c.brand), last4: c.last4, month: String(c.expMonth).padStart(2, '0'), year: String(c.expYear % 100).padStart(2, '0') })}</span>
        </label>
      ))}
      <label className="nl-cardchoice-row">
        <input type="radio" name="nl-card" checked={value === undefined} onChange={() => onChange(undefined)} />
        <span>{t('newCard')}</span>
      </label>
      {value ? <p className="nl-cardchoice-note">{t('savedNote')}</p> : null}
    </fieldset>
  );
}
