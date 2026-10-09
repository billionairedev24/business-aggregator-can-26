import { useState } from 'react';
import { Checkbox, Field, TextInput, useFormatters } from '@northline/ui';
import { usePromoT } from './messages';

/**
 * Checkout's promo code and points (mobile gaps part 2), shared by the cart, the food checkout and the booking wizard.
 * The code is applied by the next quote (the server checks it and answers 422 on `promoCode` with the reason, already
 * in the page's language); points are a switch — the api spends what the configured share of the order allows.
 */
export function PromoPoints({ code, onCode, error, discountCents, usePoints, onUsePoints, pointsAvailable, pointsCents, disabled }: {
  code: string | undefined;
  onCode: (code: string | undefined) => void;
  error?: string;
  discountCents?: number;
  usePoints: boolean;
  onUsePoints: (on: boolean) => void;
  pointsAvailable?: number;
  pointsCents?: number;
  disabled?: boolean;
}) {
  const t = usePromoT();
  const { money } = useFormatters();
  const [draft, setDraft] = useState(code ?? '');
  const appliedOk = !!code && !error && (discountCents ?? 0) > 0;
  const available = pointsAvailable ?? 0;
  return (
    <div className="nl-promo">
      {appliedOk ? (
        <div className="nl-promo-applied" role="status">
          <span>{t('applied', { code: code!, amount: money(discountCents ?? 0) })}</span>
          <button type="button" className="btn btn-ghost" disabled={disabled} onClick={() => { setDraft(''); onCode(undefined); }}>{t('remove')}</button>
        </div>
      ) : (
        <form className="nl-promo-form" onSubmit={e => { e.preventDefault(); onCode(draft.trim() ? draft.trim().toUpperCase() : undefined); }}>
          <Field label={t('promoLabel')} error={error}>
            <TextInput value={draft} onChange={e => setDraft(e.target.value)} autoCapitalize="characters" autoComplete="off" disabled={disabled} aria-invalid={!!error} />
          </Field>
          <button type="submit" className="btn btn-secondary" disabled={disabled || !draft.trim()}>{t('apply')}</button>
        </form>
      )}
      {available > 0 ? (
        <Checkbox checked={usePoints} disabled={disabled} onChange={on => onUsePoints(on)}
          label={t('pointsUse', { points: available })} />
      ) : null}
      {usePoints && (pointsCents ?? 0) > 0 ? <p className="nl-promo-note">{t('pointsUsed', { amount: money(pointsCents!) })}</p> : null}
    </div>
  );
}

export type TipChoice = { kind: 'none' | 'amount' | 'percent'; value: number };
export const TIPS: readonly TipChoice[] = [
  { kind: 'none', value: 0 }, { kind: 'amount', value: 200 }, { kind: 'amount', value: 400 }, { kind: 'percent', value: 15 }, { kind: 'amount', value: 600 },
];

/** The courier's tip at checkout (as food's, S-57): no tip · $2 · $4 · 15 % · $6; 100 % to the courier, never taxed. */
export function TipPicker({ value, onChange, disabled }: { value: TipChoice; onChange: (tip: TipChoice) => void; disabled?: boolean }) {
  const t = usePromoT();
  const { money } = useFormatters();
  const label = (tip: TipChoice) => (tip.kind === 'none' ? t('noTip') : tip.kind === 'percent' ? `${tip.value} %` : money(tip.value));
  return (
    <div role="radiogroup" aria-label={t('tipTitle')} className="nl-chips nl-tip-chips">
      {TIPS.map(tip => (
        <button key={`${tip.kind}${tip.value}`} type="button" role="radio" className="nl-chip" disabled={disabled}
          aria-checked={tip.kind === value.kind && tip.value === value.value} onClick={() => onChange(tip)}>{label(tip)}</button>
      ))}
    </div>
  );
}
