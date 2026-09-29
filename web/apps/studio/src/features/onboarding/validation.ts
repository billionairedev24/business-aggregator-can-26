import { z } from 'zod';
import type { MerchantType } from '../shell/api';
import type { Structure } from './api';
import type { OnboardingT } from './messages';

/** validation-rules.md › Business step: GST/HST `^\d{9}\s?RT\s?\d{4}$` (case-insensitive like the design). */
export const GST = /^\d{9}\s?RT\s?\d{4}$/i;
export const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
export const CATEGORY_LIMIT: Record<MerchantType, number> = { provider: 10, seller: 5, both: 10, kitchen: 3 };
export const gstOptional = (s: Structure) => s === 'sole' || s === 'partnership';

/** The Business step's own fields, with the exact messages of validation-rules.md. */
export function businessSchema(type: MerchantType, structure: Structure, t: OnboardingT) {
  const max = CATEGORY_LIMIT[type];
  return z.object({
    displayName: z.string().trim().min(1, t('nameRequired')).min(2, t('nameShort')).max(80, t('nameLong')),
    legalName: z.string().trim().min(1, t('legalRequired')),
    gstNumber: z.string().trim()
      .refine(v => !v || GST.test(v), t('gstFormat'))
      .refine(v => gstOptional(structure) || v.length > 0, t('gstRequired')),
    categories: z.number()
      .min(1, type === 'seller' ? t('catRequired_seller') : t('catRequired'))
      .max(max, t('catMax', { max })),
  });
}

/** First message per field of a zod result (field = the api's 422 field name). */
export function zodErrors(result: z.ZodSafeParseResult<unknown>): Record<string, string> {
  if (result.success) return {};
  const out: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const key = issue.path.join('.');
    out[key] ??= issue.message;
  }
  return out;
}

/** Account step: work email format; Business Terms for brand-new accounts (07d). */
export function accountErrors(v: { type?: MerchantType; workEmail: string; terms: boolean; needsTerms: boolean }, t: OnboardingT): Record<string, string> {
  const e: Record<string, string> = {};
  if (!v.type) e.type = t('typeRequired');
  if (v.workEmail.trim() && !EMAIL.test(v.workEmail.trim())) e.workEmail = t('emailFormat');
  if (v.needsTerms && !v.terms) e.businessTermsAccepted = t('termsRequired');
  return e;
}
