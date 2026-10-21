import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Sparkle, X } from '@phosphor-icons/react';
import { ApiError } from '@northline/client';
import { useFormatters, useLocale } from '@northline/ui';
import { aiStatusQuery, applyProposals, interpretQuery, looksNatural, proposals, type Proposal, type SearchParams } from './api';
import { useSmartT, type SmartKey, type SmartT } from './smartMessages';

/** A chip's words ("Gluten-free", "Up to $25", "Open now"). */
export function chipText(c: Proposal, t: SmartT, money: (cents: number) => string): string {
  const key = (k: string) => k as SmartKey;
  switch (c.key) {
    case 'q': return t('c_q', { q: String(c.value) });
    case 'scope': return t(key(`c_${String(c.value)}`));
    case 'tier': return t(key(`c_${String(c.value)}`));
    case 'minPrice': return t('c_minPrice', { price: money(Number(c.value)) });
    case 'maxPrice': return t('c_maxPrice', { price: money(Number(c.value)) });
    case 'minRating': return t('c_minRating', { rating: String(c.value) });
    case 'openNow': return t('c_openNow');
    case 'instantBook': return t('c_instantBook');
    case 'delivery': return t('c_tonight');
    case 'radiusKm': return t('c_radius', { km: Number(c.value) });
    case 'sort': return t(key(`s_${String(c.value)}`));
    case 'dietary': { const k = key(`d_${String(c.value)}`); const s = t(k); return s === k ? String(c.value).replace(/_/g, ' ') : s; }
    case 'allergenFree': { const k = key(`a_${String(c.value)}`); const s = t(k); return t('c_free', { allergen: s === k ? String(c.value).replace(/_/g, ' ') : s }); }
    default: return String(c.value);
  }
}

/**
 * Natural-language search (S-158 on S-132's `POST /api/v1/search/interpret`): when the visitor typed a sentence
 * ("gluten-free bakery open now near me") and no filter is on yet, the model's reading is shown as removable chips
 * under "Searching for: …" — never applied on its own: the results stay those of the words until the visitor applies
 * the chips. Hidden when AI is off here (`/ai/status`); a 503 / 429 says so in one line and the words' results stay.
 */
export function SmartSearch({ params, located, onApply }: { params: SearchParams; located: boolean; onApply: (next: SearchParams) => void }) {
  const t = useSmartT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const [dismissed, setDismissed] = useState<string | null>(null);
  const q = params.q?.trim() ?? '';
  const untouched = params.scope === undefined && params.category === undefined && params.tier === undefined && params.minPrice === undefined
    && params.maxPrice === undefined && params.minRating === undefined && !params.openNow && !params.instantBook && !params.delivery
    && !params.dietary && !params.allergenFree && params.radiusKm === undefined && params.sort === undefined;
  const wanted = looksNatural(q) && untouched && dismissed !== q;
  const status = useQuery({ ...aiStatusQuery, enabled: wanted });
  const reading = useQuery({ ...interpretQuery(q, located, locale), enabled: wanted && status.data?.available === true });
  // removed chips, per reading: a new reading starts with all of its chips again
  const [removed, setRemoved] = useState<{ of: unknown; ids: string[] }>({ of: null, ids: [] });
  const gone = removed.of === reading.data ? removed.ids : [];
  const chips = reading.data ? proposals(reading.data).filter(c => !gone.includes(c.id)) : [];

  if (!wanted || status.data?.available !== true) return null;
  if (reading.isPending) return <p className="nl-smart nl-smart-quiet" role="status"><Sparkle size={16} aria-hidden /> {t('thinking')}</p>;
  if (reading.isError) {
    const code = reading.error instanceof ApiError ? reading.error.status : 0;
    // 422 (the api's own text rules) needs no word; unavailable, over budget or rate-limited: one line
    return code === 422 ? null : <p className="nl-smart nl-smart-quiet" role="status">{t('unavailable')}</p>;
  }
  if (proposals(reading.data).length === 0) {
    return <p className="nl-smart nl-smart-quiet" role="status">{t('none')}</p>;
  }
  return (
    <section className="nl-smart" aria-label={t('label')}>
      <p className="nl-smart-head"><Sparkle size={18} aria-hidden /> <span role="status">{t('understood', { explanation: reading.data.explanation })}</span></p>
      <ul className="nl-smart-chips">
        {chips.map(c => {
          const text = chipText(c, t, money);
          return (
            <li key={c.id} className="nl-smart-chip">
              <span>{text}</span>
              <button type="button" className="nl-smart-x" aria-label={t('remove', { chip: text })} onClick={() => setRemoved({ of: reading.data, ids: [...gone, c.id] })}><X size={14} aria-hidden /></button>
            </li>
          );
        })}
      </ul>
      <p className="nl-smart-hint">{t('hint')}</p>
      <div className="nl-smart-actions">
        <button type="button" className="btn btn-primary btn-sm" disabled={chips.length === 0} onClick={() => onApply(applyProposals(chips, q))}>{t('apply')}</button>
        <button type="button" className="btn btn-link btn-sm" onClick={() => setDismissed(q)}>{t('dismiss')}</button>
      </div>
    </section>
  );
}
