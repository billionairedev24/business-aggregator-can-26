import { formatDate, type Locale } from '@northline/ui';
import type { MerchantType } from '../shell/api';
import type { Check } from './api';
import type { OnboardingT } from './messages';

export interface CheckText { name: string; desc: string; cta: string; done: string }

/**
 * Name, description, button and done label of one verification row (design 02 `foodChecks`, `sellerChecks`,
 * `genericChecks`). Done labels are built from the stored evidence instead of the design's sample values.
 */
export function checkText(c: Check, type: MerchantType, t: OnboardingT, locale: Locale): CheckText {
  const food = type === 'kitchen';
  const seller = type === 'seller';
  const ref = c.reference ?? '';
  const pending = c.status === 'submitted';
  const reg = c.registry ?? '';
  const onFile = pending ? `${t('done_on_file')} · ${t('inReview')}` : t('done_on_file');
  const key = c.key.startsWith('licence:') ? 'licence' : c.key;
  switch (key) {
    case 'kyc': return { name: t('ck_kyc'), desc: t(food || seller ? 'ckd_kyc_owners' : 'ckd_kyc'), cta: t('cta_kyc'), done: pending ? t('done_kyc_review') : t('done_kyc') };
    case 'registry': return {
      name: t(food ? 'ck_registry_kitchen' : 'ck_registry'), desc: t(food ? 'ckd_registry_kitchen' : seller ? 'ckd_registry_seller' : 'ckd_registry'), cta: t('cta_registry'),
      done: pending ? t('done_checking', { ref }) : ref === 'not_required' ? t('done_registry_not_required') : ref && ref !== 'matched' ? t('done_registry_ref', { ref }) : t('done_registry'),
    };
    case 'licence': return {
      name: t('ck_licence', { registry: reg }), desc: reg === 'AMVIC' ? t('ckd_licence_AMVIC') : t('ckd_licence', { registry: reg }), cta: t('cta_licence'),
      done: pending ? t('done_checking', { ref }) : t('done_licence', { ref }),
    };
    case 'insurance': return {
      name: t(food ? 'ck_insurance_kitchen' : 'ck_insurance'), desc: t(food ? 'ckd_insurance_kitchen' : 'ckd_insurance'), cta: t('cta_insurance'),
      done: c.expiresOn ? t('done_valid_to', { date: formatDate(`${c.expiresOn}T12:00:00Z`, locale, 'full') }) : onFile,
    };
    case 'bank': return { name: t('ck_bank'), desc: t(food || seller ? 'ckd_bank_stripe' : 'ckd_bank'), cta: t('cta_bank'), done: ref };
    case 'mfa': return { name: t('ck_mfa'), desc: t(food || seller ? 'ckd_mfa_all' : 'ckd_mfa'), cta: t('cta_mfa'), done: t('done_mfa') };
    case 'gst': return { name: t('ck_gst'), desc: t('ckd_gst'), cta: t('cta_gst'), done: t('done_gst', { rt: ref.split(' ')[1] ?? ref }) };
    case 'category_permits': return { name: t('ck_category_permits'), desc: t('ckd_category_permits'), cta: t('cta_category_permits'), done: ref === 'none' ? t('done_none_required') : t('done_declared', { ref }) };
    case 'product_safety': return { name: t('ck_product_safety'), desc: t('ckd_product_safety'), cta: t('cta_sign'), done: t('done_signed') };
    case 'returns_policy': return { name: t('ck_returns_policy'), desc: t('ckd_returns_policy'), cta: t('cta_choose'), done: t(ref === 'perishables' ? 'done_perishables' : 'done_standard') };
    case 'ahs_permit': return { name: t('ck_ahs_permit'), desc: t('ckd_ahs_permit'), cta: t('cta_ahs_permit'), done: pending ? t('done_checking', { ref }) : t('done_permit', { ref }) };
    case 'food_cert': return { name: t('ck_food_cert'), desc: t('ckd_food_cert'), cta: t('cta_food_cert'), done: onFile };
    case 'inspection': return { name: t('ck_inspection'), desc: t('ckd_inspection'), cta: t('cta_inspection'), done: onFile };
    case 'allergen_attestation': return { name: t('ck_allergen_attestation'), desc: t('ckd_allergen_attestation'), cta: t('cta_sign'), done: t('done_signed') };
    case 'aglc': return { name: t('ck_aglc'), desc: t('ckd_aglc'), cta: t('cta_aglc'), done: ref === 'not_applicable' ? t('done_no_alcohol') : pending ? t('done_checking', { ref }) : ref };
    case 'site_visit': return { name: t('ck_site_visit'), desc: t('ckd_site_visit'), cta: t('cta_site_visit'), done: t('done_booked', { when: ref ? slotLabel(ref, locale) : '' }) };
    default: return { name: c.key, desc: '', cta: t('cta_choose'), done: ref };
  }
}

/** "Thu 10 a.m." in Calgary time. */
export function slotLabel(iso: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'America/Edmonton' }).format(new Date(iso));
}

export const isComplete = (c: Check) => c.status === 'submitted' || c.status === 'verified';
