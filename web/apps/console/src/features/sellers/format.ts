import { formatNumber, type Locale } from '@northline/ui';
import type { Regions } from '../shell/api';
import type { Flag, Seller } from './api';
import type { SellersKey, SellersT } from './messages';

/** "$19.4k", "$1.9k", "$640": the design's compact GMV. */
export function compactMoney(cents: number, locale: Locale): string {
  const dollars = cents / 100;
  return new Intl.NumberFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { style: 'currency', currency: 'CAD', currencyDisplay: 'narrowSymbol',
    notation: Math.abs(dollars) >= 1000 ? 'compact' : 'standard', maximumFractionDigits: Math.abs(dollars) >= 1000 ? 1 : 0 }).format(dollars).replace('K', 'k');
}

export const percent = (ratio: number, locale: Locale) => formatNumber(ratio, locale, { style: 'percent', maximumFractionDigits: 1 });

/** "AB", "BC pilot": the province as the console names it, from the region model. */
export function regionName(code: string | null | undefined, regions: Regions | undefined, t: SellersT): string {
  if (!code) return t('none');
  const p = regions?.provinces.find(x => x.code === code);
  return p?.status === 'pilot' ? t('regionPilot', { code }) : code;
}

export const checkName = (type: string, t: SellersT) => t(`check_${type}` as SellersKey) || type;

/** One flag as the Flags column reads ("Quality < floor", "Insurance 21 d", "Licence pending"). */
export function flagText(f: Flag, t: SellersT): string {
  const check = f.checkType ? (f.registry && f.checkType === 'licence' ? `${checkName(f.checkType, t)} ${f.registry}` : checkName(f.checkType, t)) : '';
  switch (f.kind) {
    case 'quality_below': return t('f_quality_below');
    case 'disputes_above': return t('f_disputes_above');
    case 'trust_flag': {
      const known = ['off_platform_payment', 'floor_breach', 'no_show', 'regulated_without_permit'];
      return known.includes(f.rule ?? '') ? t(`f_rule_${f.rule}` as SellersKey) : t('f_rule_other', { rule: f.rule ?? '' });
    }
    case 'check_expiring': return t('f_check_expiring', { check, days: f.days ?? 0 });
    case 'check_due': return t('f_check_due', { check, status: t(`due_${f.status}` as SellersKey) });
    case 'check_pending': return t('f_check_pending', { check });
  }
}

export const flagsText = (s: Seller, t: SellersT) => (s.flags.length ? s.flags.map(f => flagText(f, t)).join(' · ') : t('none'));
