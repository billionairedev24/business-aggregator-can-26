import type { MerchantType } from '../shell/api';
import type { OnboardingT } from './messages';

/** `merchants.merchants.profile` (server BusinessProfile). Option fields hold codes. */
export interface BusinessProfile {
  yearsOperating?: string | null; teamSize?: string | null; serviceArea?: string | null; workLocations?: string[]; languages?: string[];
  licenceNumbers?: string | null; description?: string | null; productCount?: string | null; pickupAddress?: string | null; sameDayCutoff?: string | null;
  inventorySources?: string[]; perishables?: string[]; cuisines?: string[]; kitchenAddress?: string | null; ahsPermitNumber?: string | null;
  cityLicenceNumber?: string | null; seats?: string | null; certifiedHandlers?: string | null; fulfilment?: string[]; dietary?: string[]; alcohol?: string | null;
}
export type ProfileKey = keyof BusinessProfile;
type Key = Parameters<OnboardingT>[0];

export type ProfileField =
  | { key: ProfileKey; kind: 'select'; label: Key; options: readonly { value: string; label: Key }[]; span?: boolean }
  | { key: ProfileKey; kind: 'chips'; label: Key; options: readonly { value: string; label: Key }[]; span?: boolean }
  | { key: ProfileKey; kind: 'input' | 'area'; label: Key; ph: Key; span?: boolean; max: number };

const opt = (value: string, label: Key) => ({ value, label });
const YEARS = [opt('just_starting', 'o_just_starting'), opt('1-2', 'o_1_2'), opt('3-5', 'o_3_5'), opt('6-10', 'o_6_10'), opt('10+', 'o_10p')];
const TEAM = [opt('just_me', 'o_just_me'), opt('2-4', 'o_2_4'), opt('5-10', 'o_5_10'), opt('10+', 'o_10p')];
const lang = (codes: string[]) => codes.map(c => opt(c, `l_${c}` as Key));

/** Per-type Business step fields (design 02 `bfSets`), in design order. */
export const PROFILE_FIELDS: Record<MerchantType, readonly ProfileField[]> = {
  provider: [
    { key: 'yearsOperating', kind: 'select', label: 'bf_yearsOperating', options: YEARS },
    { key: 'teamSize', kind: 'select', label: 'bf_teamSize', options: TEAM },
    { key: 'serviceArea', kind: 'input', label: 'bf_serviceArea', ph: 'bf_serviceArea_ph', max: 200 },
    { key: 'workLocations', kind: 'chips', label: 'bf_workLocations', options: [opt('customer', 'o_customer'), opt('my_location', 'o_my_location'), opt('remote', 'o_remote')] },
    { key: 'languages', kind: 'chips', label: 'bf_languages', options: lang(['en', 'fr', 'pa', 'tl', 'zh', 'ar', 'es']), span: true },
    { key: 'licenceNumbers', kind: 'input', label: 'bf_licenceNumbers', ph: 'bf_licenceNumbers_ph', span: true, max: 200 },
    { key: 'description', kind: 'area', label: 'bf_description', ph: 'bf_description_ph_provider', span: true, max: 1000 },
  ],
  seller: [
    { key: 'yearsOperating', kind: 'select', label: 'bf_yearsOperating', options: YEARS },
    { key: 'productCount', kind: 'select', label: 'bf_productCount', options: [opt('1-20', 'o_p1_20'), opt('21-100', 'o_p21_100'), opt('101-1000', 'o_p101_1000'), opt('1000+', 'o_p1000p')] },
    { key: 'pickupAddress', kind: 'input', label: 'bf_pickupAddress', ph: 'bf_pickupAddress_ph', max: 200 },
    { key: 'sameDayCutoff', kind: 'select', label: 'bf_sameDayCutoff', options: [opt('17:45', 'o_cut_1745'), opt('16:00', 'o_cut_1600'), opt('14:00', 'o_cut_1400'), opt('next_day', 'o_cut_next')] },
    { key: 'inventorySources', kind: 'chips', label: 'bf_inventorySources', options: [opt('manual', 'o_manual'), opt('shopify', 'o_shopify'), opt('square', 'o_square'), opt('lightspeed', 'o_lightspeed'), opt('csv_api', 'o_csv_api')] },
    { key: 'perishables', kind: 'chips', label: 'bf_perishables', options: [opt('none', 'o_per_none'), opt('chilled', 'o_chilled'), opt('frozen', 'o_frozen'), opt('fresh', 'o_fresh')] },
    { key: 'languages', kind: 'chips', label: 'bf_languages', options: lang(['en', 'fr', 'pa', 'tl', 'zh', 'ar', 'es']), span: true },
    { key: 'description', kind: 'area', label: 'bf_description', ph: 'bf_description_ph_seller', span: true, max: 1000 },
  ],
  both: [
    { key: 'yearsOperating', kind: 'select', label: 'bf_yearsOperating', options: YEARS },
    { key: 'teamSize', kind: 'select', label: 'bf_teamSize', options: TEAM },
    { key: 'serviceArea', kind: 'input', label: 'bf_serviceArea', ph: 'bf_serviceArea_ph_both', max: 200 },
    { key: 'pickupAddress', kind: 'input', label: 'bf_pickupAddress_both', ph: 'bf_pickupAddress_ph_both', max: 200 },
    { key: 'licenceNumbers', kind: 'input', label: 'bf_licenceNumbers', ph: 'bf_licenceNumbers_ph_both', span: true, max: 200 },
    { key: 'languages', kind: 'chips', label: 'bf_languages', options: lang(['en', 'fr', 'pa', 'tl', 'zh']), span: true },
    { key: 'description', kind: 'area', label: 'bf_description', ph: 'bf_description_ph_both', span: true, max: 1000 },
  ],
  kitchen: [
    { key: 'cuisines', kind: 'chips', label: 'bf_cuisines', options: ['vietnamese', 'pizza', 'indian', 'ramen', 'canadian', 'ethiopian', 'italian', 'mexican', 'middle_eastern', 'dessert', 'other'].map(c => opt(c, `c_${c}` as Key)), span: true },
    { key: 'kitchenAddress', kind: 'input', label: 'bf_kitchenAddress', ph: 'bf_kitchenAddress_ph', span: true, max: 200 },
    { key: 'ahsPermitNumber', kind: 'input', label: 'bf_ahsPermitNumber', ph: 'bf_ahsPermitNumber_ph', max: 60 },
    { key: 'cityLicenceNumber', kind: 'input', label: 'bf_cityLicenceNumber', ph: 'bf_cityLicenceNumber_ph', max: 60 },
    { key: 'seats', kind: 'select', label: 'bf_seats', options: [opt('takeout', 'o_takeout'), opt('1-20', 'o_s1_20'), opt('21-60', 'o_s21_60'), opt('60+', 'o_s60p'), opt('catering', 'o_catering_mobile')] },
    { key: 'certifiedHandlers', kind: 'select', label: 'bf_certifiedHandlers', options: [opt('1', 'o_h1'), opt('2', 'o_h2'), opt('3-5', 'o_h3_5'), opt('6+', 'o_h6p')] },
    { key: 'fulfilment', kind: 'chips', label: 'bf_fulfilment', options: ['hot_courier', 'pickup', 'meal_kits', 'catering', 'scheduled', 'group'].map(c => opt(c, `f_${c}` as Key)), span: true },
    { key: 'dietary', kind: 'chips', label: 'bf_dietary', options: ['halal', 'kosher', 'vegan', 'vegetarian', 'gluten_free', 'nut_free'].map(c => opt(c, `d_${c}` as Key)), span: true },
    { key: 'alcohol', kind: 'select', label: 'bf_alcohol', options: [opt('none', 'a_none'), opt('aglc', 'a_aglc')] },
    { key: 'languages', kind: 'chips', label: 'bf_languages', options: lang(['en', 'fr', 'vi', 'pa', 'zh']) },
    { key: 'description', kind: 'area', label: 'bf_description', ph: 'bf_description_ph_kitchen', span: true, max: 1000 },
  ],
};

/** Selects start on their first option (as the design shows them). */
export function defaultProfile(type: MerchantType): BusinessProfile {
  const out: Record<string, unknown> = {};
  for (const f of PROFILE_FIELDS[type]) if (f.kind === 'select') out[f.key] = f.options[0]!.value;
  return out as BusinessProfile;
}
