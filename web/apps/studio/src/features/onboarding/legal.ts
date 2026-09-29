import type { OnboardingT } from './messages';
import type { PrincipalInput, PrincipalRole, Structure } from './api';

type Key = Parameters<OnboardingT>[0];

/**
 * Client mirror of docs/spec/legal-details.schema.json (one branch per business structure) with the design's labels
 * (design 02 `bsDefs`). `legal.test.ts` checks keys, required-ness and patterns against the schema file; the server
 * validates against the file itself.
 */
export type LegalFieldKind = 'input' | 'date' | 'doc' | 'select' | 'bool' | 'attorney' | 'sin';
export interface LegalField {
  key: string;
  kind: LegalFieldKind;
  label: Key;
  ph?: Key;
  req: 'required' | 'optional' | 'if_trade' | 'if_charity';
  span?: boolean;
  options?: readonly { value: string; label: Key }[];
  min?: 2 | 5;
  max?: number;
  pattern?: RegExp;
  patternMsg?: Key;
}

const NAME = { min: 2 } as const;
const ADDRESS = { min: 5, max: 200, span: true } as const;
const BN: Partial<LegalField> = { pattern: /^\d{9}$/, patternMsg: 'lv_bn', ph: 'lf_business_number_ph' };
const f = (key: string, kind: LegalFieldKind, label: Key, req: LegalField['req'], extra: Partial<LegalField> = {}): LegalField => ({ key, kind, label, req, ...extra });

export const LEGAL_FIELDS: Record<Structure, readonly LegalField[]> = {
  sole: [
    f('owner_legal_name', 'input', 'lf_owner_legal_name', 'required', { ...NAME, ph: 'lf_owner_legal_name_ph' }),
    f('trade_name', 'input', 'lf_trade_name', 'optional', { max: 120, ph: 'lf_trade_name_ph' }),
    f('trade_name_registration', 'input', 'lf_trade_name_registration', 'if_trade', { ph: 'lf_trade_name_registration_ph' }),
    f('sin_collected_by_stripe', 'sin', 'lf_sin', 'required', { ph: 'lf_sin_ph' }),
    f('address', 'input', 'lf_address', 'required', { ...ADDRESS, ph: 'lf_address_ph' }),
  ],
  partnership: [
    f('partnership_name', 'input', 'lf_partnership_name', 'required', { ...NAME, ph: 'lf_partnership_name_ph' }),
    f('partnership_registration', 'input', 'lf_partnership_registration', 'required', { ph: 'lf_partnership_registration_ph' }),
    f('partnership_agreement_doc', 'doc', 'lf_partnership_agreement_doc', 'optional', { ph: 'lf_pdf' }),
    f('business_number', 'input', 'lf_business_number', 'required', BN),
  ],
  corp_ab: [
    f('legal_corporate_name', 'input', 'lf_legal_corporate_name', 'required', { ...NAME, ph: 'lf_legal_corporate_name_ph_ab' }),
    f('alberta_corporate_access_number', 'input', 'lf_alberta_corporate_access_number', 'required', { pattern: /^\d{10}$/, patternMsg: 'lv_can', ph: 'lf_alberta_corporate_access_number_ph' }),
    f('business_number', 'input', 'lf_business_number', 'required', BN),
    f('incorporation_date', 'date', 'lf_incorporation_date', 'required', { ph: 'lf_incorporation_date_ph' }),
    f('registered_office', 'input', 'lf_registered_office', 'required', { ...ADDRESS, ph: 'lf_registered_office_ph' }),
    f('certificate_of_incorporation_doc', 'doc', 'lf_certificate_of_incorporation_doc', 'required', { ph: 'lf_pdf' }),
    f('operating_name', 'input', 'lf_operating_name', 'optional', { max: 120, ph: 'lf_operating_name_ph' }),
  ],
  corp_fed: [
    f('legal_corporate_name', 'input', 'lf_legal_corporate_name', 'required', { ...NAME, ph: 'lf_legal_corporate_name_ph_fed' }),
    f('corporations_canada_number', 'input', 'lf_corporations_canada_number', 'required', { pattern: /^\d{7}$/, patternMsg: 'lv_ccn', ph: 'lf_corporations_canada_number_ph' }),
    f('alberta_extra_provincial_registration', 'input', 'lf_alberta_extra_provincial_registration', 'required', { ph: 'lf_alberta_extra_provincial_registration_ph' }),
    f('business_number', 'input', 'lf_business_number', 'required', BN),
    f('registered_office', 'input', 'lf_registered_office', 'required', ADDRESS),
    f('certificate_of_incorporation_doc', 'doc', 'lf_certificate_of_incorporation_doc', 'required', { ph: 'lf_pdf' }),
    f('annual_return_current', 'bool', 'lf_annual_return_current', 'required', { options: [{ value: 'true', label: 'lf_annual_yes' }, { value: 'false', label: 'lf_annual_no' }] }),
  ],
  corp_ex: [
    f('legal_corporate_name', 'input', 'lf_legal_corporate_name', 'required', NAME),
    f('home_jurisdiction', 'select', 'lf_home_jurisdiction', 'required', { options: (['BC', 'SK', 'MB', 'ON', 'QC', 'OTHER_CA', 'US', 'OTHER'] as const).map(v => ({ value: v, label: `jur_${v}` as Key })) }),
    f('home_registration_number', 'input', 'lf_home_registration_number', 'required'),
    f('alberta_extra_provincial_registration', 'input', 'lf_alberta_extra_provincial_registration', 'required'),
    f('attorney_for_service', 'attorney', 'lf_attorney_for_service', 'required', { span: true, ph: 'lf_attorney_ph' }),
    f('business_number', 'input', 'lf_business_number', 'required', BN),
    f('certificate_of_status_doc', 'doc', 'lf_certificate_of_status_doc', 'required', { ph: 'lf_certificate_of_status_doc_ph' }),
  ],
  coop: [
    f('registered_name', 'input', 'lf_registered_name_coop', 'required', NAME),
    f('cooperative_registration', 'input', 'lf_cooperative_registration', 'required', { ph: 'lf_partnership_registration_ph' }),
    f('business_number', 'input', 'lf_business_number', 'required', BN),
    f('board_resolution_doc', 'doc', 'lf_board_resolution_doc', 'required', { ph: 'lf_pdf' }),
    f('registered_office', 'input', 'lf_registered_office', 'required', ADDRESS),
  ],
  nonprofit: [
    f('registered_name', 'input', 'lf_registered_name', 'required', NAME),
    f('society_registration', 'input', 'lf_society_registration', 'required', { ph: 'lf_partnership_registration_ph' }),
    f('cra_charity_number', 'input', 'lf_cra_charity_number', 'if_charity', { pattern: /^\d{9}RR\d{4}$/, patternMsg: 'lv_charity', ph: 'lf_cra_charity_number_ph' }),
    f('business_number', 'input', 'lf_business_number', 'required', BN),
    f('board_resolution_doc', 'doc', 'lf_board_resolution_doc', 'required', { ph: 'lf_pdf' }),
    f('registered_office', 'input', 'lf_registered_office', 'required', ADDRESS),
  ],
};

/** Keys whose spaces are typing aids (compacted and upper-cased like the server). */
const COMPACT = new Set(['business_number', 'alberta_corporate_access_number', 'corporations_canada_number', 'cra_charity_number']);

export interface Attorney { name?: string; alberta_address?: string }
export type LegalValues = Record<string, string | boolean | Attorney | undefined>;

/** The `legal_details` object sent to the api: trimmed, blanks dropped, SIN flag set, booleans typed. */
export function toLegalDetails(structure: Structure, values: LegalValues): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const field of LEGAL_FIELDS[structure]) {
    const v = values[field.key];
    if (field.kind === 'sin') { out[field.key] = true; continue; }
    if (field.kind === 'bool') { if (v === true || v === 'true') out[field.key] = true; else if (v === false || v === 'false') out[field.key] = false; continue; }
    if (field.kind === 'attorney') {
      const a = (v ?? {}) as Attorney;
      const name = a.name?.trim(), addr = a.alberta_address?.trim();
      if (name || addr) out[field.key] = { ...(name ? { name } : {}), ...(addr ? { alberta_address: addr } : {}) };
      continue;
    }
    if (typeof v !== 'string') continue;
    const s = COMPACT.has(field.key) ? v.replace(/\s/g, '').toUpperCase() : v.trim();
    if (s) out[field.key] = s;
  }
  return out;
}

/** Errors keyed like the server's 422 fields (`legalDetails.<key>`); documents: ids already uploaded. */
export function validateLegal(structure: Structure, values: LegalValues, t: OnboardingT): Record<string, string> {
  const details = toLegalDetails(structure, values);
  const errors: Record<string, string> = {};
  const at = (k: string) => `legalDetails.${k}`;
  for (const field of LEGAL_FIELDS[structure]) {
    const v = details[field.key];
    const required = field.req === 'required' || (field.req === 'if_trade' && details.trade_name !== undefined);
    if (field.kind === 'attorney') {
      const a = (v ?? {}) as Attorney;
      if (!a.name) errors[at(`${field.key}.name`)] = t('lv_required');
      if (!a.alberta_address) errors[at(`${field.key}.alberta_address`)] = t('lv_required');
      else if (a.alberta_address.length < 5) errors[at(`${field.key}.alberta_address`)] = t('lv_address');
      continue;
    }
    if (v === undefined) { if (required) errors[at(field.key)] = t(field.kind === 'doc' ? 'lv_doc' : 'lv_required'); continue; }
    if (typeof v !== 'string') continue;
    if (field.min && v.length < field.min) errors[at(field.key)] = t(field.min === 5 ? 'lv_address' : 'lv_min2');
    else if (field.max && v.length > field.max) errors[at(field.key)] = t('lv_max', { max: field.max });
    else if (field.pattern && !field.pattern.test(v)) errors[at(field.key)] = t(field.patternMsg ?? 'lv_required');
    else if (field.kind === 'date' && !isIsoDate(v)) errors[at(field.key)] = t('lv_date');
  }
  return errors;
}

const isIsoDate = (s: string) => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`)) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);

/** Owners table per structure (design 02 `bsDefs.*.hasOwners/roles/shareLabel`); roles are the schema's x-principals. */
export interface OwnersSpec { label: Key; note: Key; roles: readonly PrincipalRole[]; share: Key | null; min: number; requiredRole: PrincipalRole | null; minMsg: Key; roleMsg: Key }
export const OWNERS: Partial<Record<Structure, OwnersSpec>> = {
  partnership: { label: 'owners_partnership', note: 'ownersNote_partnership', roles: ['partner_signing', 'partner'], share: 'sharePct', min: 2, requiredRole: 'partner_signing', minMsg: 'pv_min_partnership', roleMsg: 'pv_role_partnership' },
  corp_ab: { label: 'owners_corp', note: 'ownersNote_corp', roles: ['director', 'officer', 'shareholder'], share: 'ownershipPct', min: 1, requiredRole: 'director', minMsg: 'pv_min_corp', roleMsg: 'pv_role_corp' },
  corp_fed: { label: 'owners_corp', note: 'ownersNote_corp', roles: ['director', 'officer', 'shareholder'], share: 'ownershipPct', min: 1, requiredRole: 'director', minMsg: 'pv_min_corp', roleMsg: 'pv_role_corp' },
  corp_ex: { label: 'owners_corp', note: 'ownersNote_corp', roles: ['director', 'officer', 'shareholder'], share: 'ownershipPct', min: 1, requiredRole: 'director', minMsg: 'pv_min_corp', roleMsg: 'pv_role_corp' },
  coop: { label: 'owners_coop', note: 'ownersNote_coop', roles: ['chair', 'director', 'secretary', 'treasurer'], share: null, min: 3, requiredRole: 'chair', minMsg: 'pv_min_board', roleMsg: 'pv_role_coop' },
  nonprofit: { label: 'owners_nonprofit', note: 'ownersNote_nonprofit', roles: ['president', 'director', 'treasurer', 'secretary'], share: null, min: 3, requiredRole: 'president', minMsg: 'pv_min_board', roleMsg: 'pv_role_nonprofit' },
};

export interface OwnerRow { legalName: string; role: PrincipalRole; pct: string }

/** Principals → api body, and the same checks as the server's BusinessStructure.check. */
export function toPrincipals(structure: Structure, rows: readonly OwnerRow[]): PrincipalInput[] {
  const spec = OWNERS[structure];
  if (!spec) return [];
  return rows.map(r => ({ legalName: r.legalName.trim(), role: r.role, ownershipPct: spec.share && r.pct.trim() !== '' ? Number(r.pct) : null }));
}

export function validatePrincipals(structure: Structure, rows: readonly OwnerRow[], t: OnboardingT): Record<string, string> {
  const spec = OWNERS[structure];
  if (!spec) return {};
  const errors: Record<string, string> = {};
  let sum = 0;
  rows.forEach((r, i) => {
    if (!r.legalName.trim()) errors[`principals[${i}].legalName`] = t('pv_name');
    if (spec.share && r.pct.trim() !== '') {
      const n = Number(r.pct);
      if (!Number.isFinite(n) || n < 0 || n > 100) errors[`principals[${i}].ownershipPct`] = t('pv_pct');
      else sum += n;
    }
  });
  if (rows.length < spec.min) errors.principals = t(spec.minMsg);
  else if (spec.requiredRole && !rows.some(r => r.role === spec.requiredRole)) errors.principals = t(spec.roleMsg);
  else if (spec.share && sum > 100) errors.principals = t('pv_sum');
  return errors;
}
