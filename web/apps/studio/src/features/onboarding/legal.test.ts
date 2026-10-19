import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { OnboardingT } from './messages';
import { OWNERS, fieldLabel, fieldPlaceholder, legalDefaults, requirementNote, toLegalDetails, validateLegal, validatePrincipals } from './legal';
import type { LegalFormDef, Structure } from './api';
import { LEGAL_FORMS } from '../../test/legalForms';
import { GST, businessSchema, zodErrors } from './validation';

interface Def { properties: Record<string, { pattern?: string; $ref?: string; minLength?: number }>; required: string[]; 'x-principals': { min: number; roles: string[] } }
const schema = JSON.parse(readFileSync(resolve(__dirname, '../../../../../../docs/spec/legal-details.schema.json'), 'utf8')) as { $defs: Record<string, Def> };

/** Messages: the English catalogue is what the server uses too; a stub returns the key for readability. */
const t = ((key: string, v?: Record<string, unknown>) => (v ? `${key}:${JSON.stringify(v)}` : key)) as unknown as OnboardingT;
const STRUCTURES = Object.keys(OWNERS).concat('sole') as Structure[];
const form = (province: keyof typeof LEGAL_FORMS, s: Structure): LegalFormDef => LEGAL_FORMS[province].items.find(f => f.structure === s)!;
const field = (province: keyof typeof LEGAL_FORMS, s: Structure, key: string) => form(province, s).fields.find(f => f.key === key)!;

describe('the served legal forms follow docs/spec/legal-details.schema.json', () => {
  it.each(Object.keys(LEGAL_FORMS) as (keyof typeof LEGAL_FORMS)[])('%s: every structure, the schema\'s fields and federal patterns', province => {
    expect(LEGAL_FORMS[province].items.map(f => f.structure).sort()).toEqual([...STRUCTURES].sort());
    for (const s of STRUCTURES) {
      const def = schema.$defs[s]!;
      expect(form(province, s).fields.map(f => f.key).sort()).toEqual(Object.keys(def.properties).filter(k => k !== 'structure').sort());
      for (const f of form(province, s).fields) {
        const p = def.properties[f.key]!;
        if (p.$ref === '#/$defs/doc') expect(f.kind, `${s}.${f.key}`).toBe('document');
        if (['business_number', 'corporations_canada_number', 'cra_charity_number'].includes(f.key)) expect(f.pattern, `${s}.${f.key}`).toBe(p.pattern);
      }
    }
  });

  it('owners follow x-principals', () => {
    for (const s of STRUCTURES) {
      const rules = schema.$defs[s]!['x-principals'];
      const spec = OWNERS[s];
      if (!spec) { expect(s).toBe('sole'); continue; }
      expect([...spec.roles].sort()).toEqual([...rules.roles].sort());
      expect(spec.min).toBe(rules.min);
    }
  });
});

describe('labels and copy come from the province', () => {
  it.each([
    ['AB', 'Corporate access number', "Numéro d'accès de la société"],
    ['ON', 'Ontario Corporation Number (OCN)', "Numéro matricule de la personne morale de l'Ontario (NMPMO)"],
    ['BC', 'Incorporation number', 'Numéro de constitution'],
    ['QC', 'Québec enterprise number (NEQ)', "Numéro d'entreprise du Québec (NEQ)"],
  ] as const)('%s: the provincial registry number is labelled %s', (province, en, fr) => {
    const f = field(province, 'corp_prov', 'provincial_registry_number');
    expect(fieldLabel(f, 'corp_prov', t, 'en')).toBe(en);
    expect(fieldLabel(f, 'corp_prov', t, 'fr')).toBe(fr);
  });

  it('falls back to the generic copy for keys the province says nothing about', () => {
    expect(fieldLabel(field('AB', 'corp_prov', 'legal_corporate_name'), 'corp_prov', t, 'en')).toBe('lf_legal_corporate_name');
    expect(fieldLabel(field('AB', 'coop', 'registered_name'), 'coop', t, 'en')).toBe('lf_registered_name_coop');
    expect(fieldPlaceholder(field('AB', 'corp_prov', 'provincial_registry_number'), t)).toBe('2012345678');
    expect(requirementNote(field('AB', 'sole', 'trade_name_registration'), t)).toBe('req_if_trade');
    expect(requirementNote(field('ON', 'corp_ex', 'attorney_for_service'), t)).toBe('req_conditional');
  });
});

describe('validateLegal', () => {
  const corp = { legal_corporate_name: 'N', provincial_registry_number: '12345', business_number: '12 34', incorporation_date: '2019-13-40', registered_office: 'x' };

  it('reports required, pattern, date, address and document errors like the api (Alberta)', () => {
    expect(validateLegal(form('AB', 'corp_prov'), corp, t, 'en')).toEqual({
      'legalDetails.legal_corporate_name': 'lv_min2',
      'legalDetails.provincial_registry_number': 'Corporate access number is 9 or 10 digits.',
      'legalDetails.business_number': 'lv_bn',
      'legalDetails.incorporation_date': 'lv_date',
      'legalDetails.registered_office': 'lv_address',
      'legalDetails.certificate_of_incorporation_doc': 'lv_doc',
    });
  });

  it.each([
    ['AB', '12345', 'Corporate access number is 9 or 10 digits.', "Le numéro d'accès compte 9 ou 10 chiffres."],
    ['QC', '123', 'The NEQ is 10 digits.', 'Le NEQ compte 10 chiffres.'],
    ['BC', 'XY12', 'BC incorporation number is BC + 7 digits (e.g. BC0999999).', 'Le numéro de constitution de la C.-B. est BC + 7 chiffres (p. ex. BC0999999).'],
  ] as const)('%s checks its own format, with its message in both languages', (province, value, en, fr) => {
    const at = 'legalDetails.provincial_registry_number';
    expect(validateLegal(form(province, 'corp_prov'), { ...corp, provincial_registry_number: value }, t, 'en')[at]).toBe(en);
    expect(validateLegal(form(province, 'corp_prov'), { ...corp, provincial_registry_number: value }, t, 'fr')[at]).toBe(fr);
  });

  it('a province without a verified format takes any number (Ontario)', () => {
    expect(validateLegal(form('ON', 'corp_prov'), { ...corp, provincial_registry_number: '12345' }, t, 'en')['legalDetails.provincial_registry_number']).toBeUndefined();
  });

  it('asks for the trade-name registration only when a trade name is given, and only where the province registers them', () => {
    const base = { owner_legal_name: 'Amara Okafor', address: '12 Glenmore Trail SW' };
    expect(validateLegal(form('BC', 'sole'), base, t, 'en')).toEqual({});
    expect(validateLegal(form('BC', 'sole'), { ...base, trade_name: 'Glenmore Bakery' }, t, 'en')).toEqual({ 'legalDetails.trade_name_registration': 'lv_required' });
    expect(validateLegal(form('BC', 'sole'), { ...base, trade_name: 'Glenmore Bakery', trade_name_registration: 'FM12' }, t, 'en')).toEqual({ 'legalDetails.trade_name_registration': 'BC registration number is FM + 7 digits (e.g. FM1016176).' });
    const noRegistry = { ...form('BC', 'sole'), tradeNameRegistration: false, fields: form('BC', 'sole').fields.map(f => (f.key === 'trade_name_registration' ? { ...f, requiredWhen: null } : f)) };
    expect(validateLegal(noRegistry, { ...base, trade_name: 'Glenmore Bakery' }, t, 'en')).toEqual({});
  });

  it('the attorney for service is required as the province says (Ontario: corporations from outside Canada)', () => {
    const ex = { legal_corporate_name: 'Coast Co', home_registration_number: 'X1', extra_provincial_registration_number: '1000123', business_number: '123456789', certificate_of_status_doc: '01J9ZD3V0000000000000D0C01' };
    expect(validateLegal(form('ON', 'corp_ex'), { ...ex, home_jurisdiction: 'BC' }, t, 'en')).toEqual({});
    expect(validateLegal(form('ON', 'corp_ex'), { ...ex, home_jurisdiction: 'US' }, t, 'en')).toEqual({
      'legalDetails.attorney_for_service.name': 'lv_required', 'legalDetails.attorney_for_service.address_in_province': 'lv_required',
    });
    expect(validateLegal(form('AB', 'corp_ex'), { ...ex, home_jurisdiction: 'BC', attorney_for_service: { name: 'Lee', address_in_province: 'x' } }, t, 'en')).toEqual({
      'legalDetails.attorney_for_service.address_in_province': 'lv_address',
    });
  });

  it('builds the api object: compacted numbers, SIN flag (never the SIN), typed booleans, the attorney group', () => {
    expect(toLegalDetails(form('AB', 'sole'), { owner_legal_name: ' Amara ', address: '12 Main St' })).toEqual({ owner_legal_name: 'Amara', sin_collected_by_stripe: true, address: '12 Main St' });
    expect(toLegalDetails(form('AB', 'nonprofit'), { cra_charity_number: '123456789 rr0001', business_number: '123 456 789' })).toMatchObject({ cra_charity_number: '123456789RR0001', business_number: '123456789' });
    expect(toLegalDetails(form('AB', 'corp_fed'), { annual_return_current: 'false' })).toMatchObject({ annual_return_current: false });
    expect(toLegalDetails(form('QC', 'corp_prov'), { provincial_registry_number: '1171 234 567' })).toMatchObject({ provincial_registry_number: '1171234567' });
    expect(toLegalDetails(form('ON', 'corp_ex'), { attorney_for_service: { name: ' Lee ', address_in_province: '' } })).toMatchObject({ attorney_for_service: { name: 'Lee' } });
  });

  it('home jurisdiction offers every other province, never the business’s own', () => {
    expect(field('QC', 'corp_ex', 'home_jurisdiction').options).not.toContain('QC');
    expect(field('QC', 'corp_ex', 'home_jurisdiction').options).toContain('ON');
    expect(legalDefaults(form('QC', 'corp_ex')).home_jurisdiction).toBe(field('QC', 'corp_ex', 'home_jurisdiction').options[0]);
  });
});

describe('validatePrincipals', () => {
  it('partnership needs two partners, one signing, and at most 100 %', () => {
    expect(validatePrincipals('partnership', [{ legalName: 'A B', role: 'partner_signing', pct: '50' }], t).principals).toBe('pv_min_partnership');
    expect(validatePrincipals('partnership', [{ legalName: 'A', role: 'partner', pct: '50' }, { legalName: 'B', role: 'partner', pct: '50' }], t).principals).toBe('pv_role_partnership');
    expect(validatePrincipals('partnership', [{ legalName: 'A', role: 'partner_signing', pct: '70' }, { legalName: 'B', role: 'partner', pct: '40' }], t).principals).toBe('pv_sum');
    expect(validatePrincipals('partnership', [{ legalName: '', role: 'partner_signing', pct: '150' }, { legalName: 'B', role: 'partner', pct: '' }], t)).toMatchObject({ 'principals[0].legalName': 'pv_name', 'principals[0].ownershipPct': 'pv_pct' });
  });
});

describe('business step rules (validation-rules.md)', () => {
  const errors = (type: 'provider' | 'seller' | 'kitchen' | 'both', structure: Structure, v: Partial<{ displayName: string; legalName: string; gstNumber: string; categories: number }>) =>
    zodErrors(businessSchema(type, structure, t).safeParse({ displayName: 'Glenmore Bakery', legalName: 'Amara Okafor', gstNumber: '', categories: 1, ...v }));

  it('display name, legal name', () => {
    expect(errors('seller', 'sole', { displayName: ' ' }).displayName).toBe('nameRequired');
    expect(errors('seller', 'sole', { displayName: 'A' }).displayName).toBe('nameShort');
    expect(errors('seller', 'sole', { legalName: '' }).legalName).toBe('legalRequired');
  });

  it('GST: format always, required unless sole/partnership', () => {
    expect(GST.test('123456789 RT0001')).toBe(true);
    expect(GST.test('123456789rt0001')).toBe(true);
    expect(errors('seller', 'sole', { gstNumber: '12345' }).gstNumber).toBe('gstFormat');
    expect(errors('seller', 'sole', {}).gstNumber).toBeUndefined();
    expect(errors('seller', 'corp_prov', {}).gstNumber).toBe('gstRequired');
  });

  it.each([['provider', 10], ['seller', 5], ['both', 10], ['kitchen', 3]] as const)('%s: at least one category, at most %i', (type, max) => {
    expect(errors(type, 'sole', { categories: 0 }).categories).toBe(type === 'seller' ? 'catRequired_seller' : 'catRequired');
    expect(errors(type, 'sole', { categories: max }).categories).toBeUndefined();
    expect(errors(type, 'sole', { categories: max + 1 }).categories).toBe(`catMax:${JSON.stringify({ max })}`);
  });
});
