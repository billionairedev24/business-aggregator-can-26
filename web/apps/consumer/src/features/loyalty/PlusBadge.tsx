import { useFormatters } from '@northline/ui';
import { memberPrice, usePlusPct } from './api';
import { useLoyaltyT } from './messages';

/** S-152: the "Plus $8.50" badge of a product card whose shop gives members a price (the label only; null without). */
export function usePlusBadge(offerId: string | null | undefined, priceCents: number | null | undefined) {
  const t = useLoyaltyT();
  const { money } = useFormatters();
  const pct = usePlusPct(priceCents != null ? offerId : null);
  if (pct == null || priceCents == null) return null;
  return t('plusPrice', { price: money(memberPrice(priceCents, pct)) });
}
