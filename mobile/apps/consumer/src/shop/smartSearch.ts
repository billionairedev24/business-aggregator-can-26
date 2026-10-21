import type { Interpretation, SearchQuery } from '../api/shop';
import type { MessageKey } from '../i18n';

/**
 * S-158 natural-language search on the phone: the model's reading as chips the person removes or applies. Pure
 * helpers (tested without a screen). Only what this screen's search can show becomes a chip.
 */
export interface Proposal { id: string; key: 'q' | 'kind' | 'tier' | 'minPrice' | 'maxPrice' | 'minRating' | 'openNow' | 'instantBook' | 'tonight' | 'dietary' | 'allergenFree' | 'radiusKm' | 'sort'; value: string | number | boolean }

/** A sentence ("gluten-free bread under $10 tonight"), not a product name: worth asking the model. */
export const looksNatural = (text: string) => text.trim().split(/\s+/).filter(Boolean).length >= 3;

export function proposals(i: Interpretation): Proposal[] {
  const out: Proposal[] = [];
  const add = (key: Proposal['key'], value: Proposal['value']) => out.push({ id: `${key}:${String(value)}`, key, value });
  if (i.q?.trim()) add('q', i.q.trim());
  const kinds = (i.kind ?? []).filter((k) => k === 'product' || k === 'service');
  if (kinds.length === 1) add('kind', kinds[0]!);
  const tier = (i.tier ?? []).find((x) => x === 'master' || x === 'trusted');
  if (tier) add('tier', tier);
  if (i.minPrice != null) add('minPrice', i.minPrice);
  if (i.maxPrice != null) add('maxPrice', i.maxPrice);
  if (i.minRating != null) add('minRating', i.minRating);
  if (i.openNow) add('openNow', true);
  if (i.instantBook) add('instantBook', true);
  if (i.delivery === 'tonight') add('tonight', true);
  (i.dietary ?? []).forEach((d) => add('dietary', d));
  (i.allergenFree ?? []).forEach((a) => add('allergenFree', a));
  if (i.radiusKm != null) add('radiusKm', Math.round(i.radiusKm));
  if (i.sort === 'price_asc' || i.sort === 'price_desc' || i.sort === 'rating') add('sort', i.sort);
  return out;
}

/** The applied chips on top of the screen's own query (the words chip replaces the typed words). */
export function withProposals(base: SearchQuery, chips: Proposal[]): SearchQuery {
  const q: SearchQuery = { ...base };
  for (const c of chips) {
    switch (c.key) {
      case 'q': q.q = String(c.value); break;
      case 'kind': q.kind = c.value as 'product' | 'service'; break;
      case 'tier': q.tier = c.value as 'master' | 'trusted'; break;
      case 'minPrice': q.minPrice = Number(c.value); break;
      case 'maxPrice': q.maxPrice = Number(c.value); break;
      case 'minRating': q.minRating = Number(c.value); break;
      case 'openNow': q.openNow = true; break;
      case 'instantBook': q.instantBook = true; break;
      case 'tonight': q.tonight = true; break;
      case 'dietary': q.dietary = [...(q.dietary ?? []), String(c.value)]; break;
      case 'allergenFree': q.allergenFree = [...(q.allergenFree ?? []), String(c.value)]; break;
      case 'radiusKm': q.radiusKm = Number(c.value); break;
      case 'sort': q.sort = c.value as SearchQuery['sort']; break;
    }
  }
  return q;
}

type T = (key: MessageKey, values?: Record<string, string | number>) => string;

/** A chip's words, in the app's language. */
export function chipLabel(c: Proposal, t: T, money: (cents: number) => string): string {
  const known = (key: string, fallback: string) => {
    const s = t(key as MessageKey);
    return s === key ? fallback : s;
  };
  switch (c.key) {
    case 'q': return t('ai.chip.q', { q: String(c.value) });
    case 'kind': return t(c.value === 'service' ? 'ai.chip.service' : 'ai.chip.product');
    case 'tier': return t(c.value === 'master' ? 'ai.chip.master' : 'ai.chip.trusted');
    case 'minPrice': return t('ai.chip.minPrice', { price: money(Number(c.value)) });
    case 'maxPrice': return t('ai.chip.maxPrice', { price: money(Number(c.value)) });
    case 'minRating': return t('ai.chip.minRating', { rating: String(c.value) });
    case 'openNow': return t('ai.chip.openNow');
    case 'instantBook': return t('ai.chip.instantBook');
    case 'tonight': return t('ai.chip.tonight');
    case 'radiusKm': return t('ai.chip.radius', { km: Number(c.value) });
    case 'sort': return known(`ai.chip.sort.${String(c.value)}`, String(c.value));
    case 'dietary': return known(`ai.diet.${String(c.value)}`, String(c.value).replace(/_/g, ' '));
    case 'allergenFree': return t('ai.chip.free', { allergen: String(c.value).replace(/_/g, ' ') });
  }
}
