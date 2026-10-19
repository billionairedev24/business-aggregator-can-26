import type { Locale } from '@northline/ui';
import type { OnboardingT } from './messages';
import type { Bilingual, Condition, LegalFieldDef, LegalFormDef, PrincipalInput, PrincipalRole, Structure } from './api';

type Key = Parameters<OnboardingT>[0];

/*
 * The legal details of the Business step come from the api (`GET …/onboarding/legal-forms`): the fields of
 * docs/spec/legal-details.schema.json for each structure with what the business's province asks — which provincial
 * identifiers are required, their labels, formats, help and the registry (region data, `region.legal_forms`). The
 * server validates against the same definition. This file only knows the generic copy of each schema key (used where
 * the province gives none) and turns the definition into the request body and the client-side errors; it names no
 * province.
 */

/** Generic label / placeholder of a schema key (`lf_<key>`, or `lf_<key>_<structure>` where the structure words it). */
const GENERIC: Record<string, { label: Key; ph?: Key; byStructure?: Partial<Record<Structure, Key>> }> = {
  owner_legal_name: { label: 'lf_owner_legal_name', ph: 'lf_owner_legal_name_ph' },
  trade_name: { label: 'lf_trade_name', ph: 'lf_trade_name_ph' },
  trade_name_registration: { label: 'lf_trade_name_registration' },
  sin_collected_by_stripe: { label: 'lf_sin', ph: 'lf_sin_ph' },
  address: { label: 'lf_address', ph: 'lf_address_ph' },
  partnership_name: { label: 'lf_partnership_name', ph: 'lf_partnership_name_ph' },
  partnership_registration: { label: 'lf_partnership_registration' },
  partnership_agreement_doc: { label: 'lf_partnership_agreement_doc', ph: 'lf_pdf' },
  business_number: { label: 'lf_business_number', ph: 'lf_business_number_ph' },
  legal_corporate_name: { label: 'lf_legal_corporate_name', ph: 'lf_legal_corporate_name_ph' },
  provincial_registry_number: { label: 'lf_provincial_registry_number' },
  incorporation_date: { label: 'lf_incorporation_date', ph: 'lf_incorporation_date_ph' },
  registered_office: { label: 'lf_registered_office', ph: 'lf_registered_office_ph' },
  certificate_of_incorporation_doc: { label: 'lf_certificate_of_incorporation_doc', ph: 'lf_pdf' },
  operating_name: { label: 'lf_operating_name', ph: 'lf_operating_name_ph' },
  corporations_canada_number: { label: 'lf_corporations_canada_number', ph: 'lf_corporations_canada_number_ph' },
  extra_provincial_registration_number: { label: 'lf_extra_provincial_registration_number' },
  annual_return_current: { label: 'lf_annual_return_current' },
  home_jurisdiction: { label: 'lf_home_jurisdiction' },
  home_registration_number: { label: 'lf_home_registration_number' },
  attorney_for_service: { label: 'lf_attorney_for_service' },
  name: { label: 'lf_attorney_name' },
  address_in_province: { label: 'lf_attorney_address' },
  certificate_of_status_doc: { label: 'lf_certificate_of_status_doc', ph: 'lf_certificate_of_status_doc_ph' },
  registered_name: { label: 'lf_registered_name', byStructure: { coop: 'lf_registered_name_coop' } },
  cooperative_registration: { label: 'lf_cooperative_registration' },
  board_resolution_doc: { label: 'lf_board_resolution_doc', ph: 'lf_pdf' },
  society_registration: { label: 'lf_society_registration' },
  cra_charity_number: { label: 'lf_cra_charity_number', ph: 'lf_cra_charity_number_ph' },
};

/** The federal identifiers' format messages (the same everywhere); a province's own format brings its message. */
const FEDERAL_FORMAT: Record<string, Key> = { business_number: 'lv_bn', corporations_canada_number: 'lv_ccn', cra_charity_number: 'lv_charity' };

const text = (b: Bilingual | null | undefined, locale: Locale) => (b ? (locale === 'fr' ? b.fr : b.en) : undefined);

/** The field's label: the province's, else the generic copy for the key. */
export function fieldLabel(f: LegalFieldDef, structure: Structure, t: OnboardingT, locale: Locale): string {
  const g = GENERIC[f.key];
  return text(f.label, locale) ?? (g ? t(g.byStructure?.[structure] ?? g.label) : f.key);
}

/** Placeholder: the province's example, else the generic one (documents: what to upload). */
export function fieldPlaceholder(f: LegalFieldDef, t: OnboardingT): string | undefined {
  if (f.example) return f.example;
  const ph = GENERIC[f.key]?.ph;
  return ph ? t(ph) : undefined;
}

export const fieldHelp = (f: LegalFieldDef, locale: Locale) => text(f.help, locale);

/** "· required / · optional / · if trade name / · if it applies". */
export function requirementNote(f: LegalFieldDef, t: OnboardingT): string {
  if (f.required) return t('req_required');
  if (f.requiredWhen) return t(f.requiredWhen.field === 'trade_name' ? 'req_if_trade' : 'req_conditional');
  return t(f.key === 'cra_charity_number' ? 'req_if_charity' : 'req_optional');
}

export type LegalValue = string | boolean | Record<string, string> | undefined;
export type LegalValues = Record<string, LegalValue>;

const holds = (c: Condition, value: unknown) => {
  const v = value == null ? undefined : String(value);
  if (c.in.length) return v !== undefined && c.in.includes(v);
  if (c.notIn.length) return v === undefined || !c.notIn.includes(v);
  return v !== undefined && v !== '';
};

/** Required now: always, or because the field it depends on has (or has not) one of the listed values. */
export const isRequired = (f: LegalFieldDef, details: Record<string, unknown>) => f.required || (!!f.requiredWhen && holds(f.requiredWhen, details[f.requiredWhen.field]));

const clean = (f: LegalFieldDef, v: string) => (f.compact ? v.replace(/\s/g, '').toUpperCase() : v.trim());

/** The `legal_details` object sent to the api: trimmed, blanks dropped, compact numbers compacted, SIN flag set, booleans typed. */
export function toLegalDetails(form: LegalFormDef, values: LegalValues): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of form.fields) {
    const v = values[f.key];
    if (f.kind === 'stripe_confirmation') { out[f.key] = true; continue; }
    if (f.kind === 'yes_no') { if (v === true || v === 'true') out[f.key] = true; else if (v === false || v === 'false') out[f.key] = false; continue; }
    if (f.kind === 'group') {
      const parts = (v && typeof v === 'object' ? v : {}) as Record<string, string>;
      const group: Record<string, string> = {};
      for (const p of f.parts) { const s = clean(p, parts[p.key] ?? ''); if (s) group[p.key] = s; }
      if (Object.keys(group).length) out[f.key] = group;
      continue;
    }
    if (typeof v !== 'string') continue;
    const s = clean(f, v);
    if (s) out[f.key] = s;
  }
  return out;
}

const isIsoDate = (s: string) => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`)) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);

function textError(f: LegalFieldDef, v: string, t: OnboardingT, locale: Locale): string | undefined {
  if (f.minLength && v.length < f.minLength) return t(f.minLength >= 5 ? 'lv_address' : 'lv_min2');
  if (f.maxLength && v.length > f.maxLength) return t('lv_max', { max: f.maxLength });
  if (f.pattern && f.kind !== 'document' && !new RegExp(f.pattern).test(v)) return text(f.patternMessage, locale) ?? t(FEDERAL_FORMAT[f.key] ?? 'lv_format');
  if (f.kind === 'date' && !isIsoDate(v)) return t('lv_date');
  return undefined;
}

/** Errors keyed like the server's 422 fields (`legalDetails.<key>`, a group's parts `legalDetails.<key>.<part>`). */
export function validateLegal(form: LegalFormDef, values: LegalValues, t: OnboardingT, locale: Locale): Record<string, string> {
  const details = toLegalDetails(form, values);
  const errors: Record<string, string> = {};
  const at = (k: string) => `legalDetails.${k}`;
  for (const f of form.fields) {
    const v = details[f.key];
    const required = isRequired(f, details);
    if (f.kind === 'group') {
      if (v === undefined && !required) continue;
      const parts = (v ?? {}) as Record<string, string>;
      for (const p of f.parts) {
        const pv = parts[p.key];
        if (pv === undefined) { if (p.required) errors[at(`${f.key}.${p.key}`)] = t('lv_required'); continue; }
        const e = textError(p, pv, t, locale);
        if (e) errors[at(`${f.key}.${p.key}`)] = e;
      }
      continue;
    }
    if (v === undefined) { if (required) errors[at(f.key)] = t(f.kind === 'document' ? 'lv_doc' : 'lv_required'); continue; }
    if (typeof v !== 'string') continue;
    const e = textError(f, v, t, locale);
    if (e) errors[at(f.key)] = e;
  }
  return errors;
}

/** Select and yes/no fields start on their first option (as the design shows them). */
export function legalDefaults(form: LegalFormDef | undefined, values: LegalValues = {}): LegalValues {
  const out: LegalValues = { ...values };
  for (const f of form?.fields ?? []) {
    if (out[f.key] !== undefined) continue;
    if (f.kind === 'choice' && f.options[0]) out[f.key] = f.options[0];
    if (f.kind === 'yes_no') out[f.key] = 'true';
  }
  return out;
}

/** Owners table per structure (design 02 `bsDefs.*.hasOwners/roles/shareLabel`); roles are the schema's x-principals. */
export interface OwnersSpec { label: Key; note: Key; roles: readonly PrincipalRole[]; share: Key | null; min: number; requiredRole: PrincipalRole | null; minMsg: Key; roleMsg: Key }
const CORP: OwnersSpec = { label: 'owners_corp', note: 'ownersNote_corp', roles: ['director', 'officer', 'shareholder'], share: 'ownershipPct', min: 1, requiredRole: 'director', minMsg: 'pv_min_corp', roleMsg: 'pv_role_corp' };
export const OWNERS: Partial<Record<Structure, OwnersSpec>> = {
  partnership: { label: 'owners_partnership', note: 'ownersNote_partnership', roles: ['partner_signing', 'partner'], share: 'sharePct', min: 2, requiredRole: 'partner_signing', minMsg: 'pv_min_partnership', roleMsg: 'pv_role_partnership' },
  corp_prov: CORP,
  corp_fed: CORP,
  corp_ex: CORP,
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
