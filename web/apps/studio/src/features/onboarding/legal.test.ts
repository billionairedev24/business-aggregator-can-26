import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { OnboardingT } from './messages';
import { LEGAL_FIELDS, OWNERS, toLegalDetails, validateLegal, validatePrincipals } from './legal';
import type { Structure } from './api';
import { GST, businessSchema, zodErrors } from './validation';

interface Def { properties: Record<string, { pattern?: string; $ref?: string; minLength?: number }>; required: string[]; 'x-principals': { min: number; roles: string[] } }
const schema = JSON.parse(readFileSync(resolve(__dirname, '../../../../../../docs/spec/legal-details.schema.json'), 'utf8')) as { $defs: Record<string, Def> };

/** Messages: the English catalogue is what the server uses too; a stub returns the key for readability. */
const t = ((key: string, v?: Record<string, unknown>) => (v ? `${key}:${JSON.stringify(v)}` : key)) as unknown as OnboardingT;
const STRUCTURES = Object.keys(LEGAL_FIELDS) as Structure[];

describe('legal.ts mirrors docs/spec/legal-details.schema.json', () => {
  it.each(STRUCTURES)('%s: same fields, same required set, same patterns', s => {
    const def = schema.$defs[s]!;
    const keys = LEGAL_FIELDS[s].map(f => f.key);
    expect(keys.sort()).toEqual(Object.keys(def.properties).filter(k => k !== 'structure').sort());
    const required = LEGAL_FIELDS[s].filter(f => f.req === 'required').map(f => f.key).sort();
    expect(required).toEqual(def.required.filter(k => k !== 'structure').sort());
    for (const f of LEGAL_FIELDS[s]) {
      const p = def.properties[f.key]!;
      if (p.pattern) expect(f.pattern?.source, `${s}.${f.key}`).toBe(p.pattern);
      if (p.$ref === '#/$defs/doc') expect(f.kind, `${s}.${f.key}`).toBe('doc');
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

describe('validateLegal', () => {
  it('reports required, pattern, date, address and document errors like the api', () => {
    const errors = validateLegal('corp_ab', { legal_corporate_name: 'N', alberta_corporate_access_number: '12345', business_number: '12 34', incorporation_date: '2019-13-40', registered_office: 'x' }, t);
    expect(errors).toEqual({
      'legalDetails.legal_corporate_name': 'lv_min2',
      'legalDetails.alberta_corporate_access_number': 'lv_can',
      'legalDetails.business_number': 'lv_bn',
      'legalDetails.incorporation_date': 'lv_date',
      'legalDetails.registered_office': 'lv_address',
      'legalDetails.certificate_of_incorporation_doc': 'lv_doc',
    });
  });

  it('asks for the trade-name registration only when a trade name is given', () => {
    const base = { owner_legal_name: 'Amara Okafor', address: '12 Glenmore Trail SW' };
    expect(validateLegal('sole', base, t)).toEqual({});
    expect(validateLegal('sole', { ...base, trade_name: 'Glenmore Bakery' }, t)).toEqual({ 'legalDetails.trade_name_registration': 'lv_required' });
  });

  it('builds the api object: compacted numbers, SIN flag (never the SIN), typed booleans', () => {
    expect(toLegalDetails('sole', { owner_legal_name: ' Amara ', address: '12 Main St' })).toEqual({ owner_legal_name: 'Amara', sin_collected_by_stripe: true, address: '12 Main St' });
    expect(toLegalDetails('nonprofit', { cra_charity_number: '123456789 rr0001', business_number: '123 456 789' })).toMatchObject({ cra_charity_number: '123456789RR0001', business_number: '123456789' });
    expect(toLegalDetails('corp_fed', { annual_return_current: 'false' })).toMatchObject({ annual_return_current: false });
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
    expect(errors('seller', 'corp_ab', {}).gstNumber).toBe('gstRequired');
  });

  it.each([['provider', 10], ['seller', 5], ['both', 10], ['kitchen', 3]] as const)('%s: at least one category, at most %i', (type, max) => {
    expect(errors(type, 'sole', { categories: 0 }).categories).toBe(type === 'seller' ? 'catRequired_seller' : 'catRequired');
    expect(errors(type, 'sole', { categories: max }).categories).toBeUndefined();
    expect(errors(type, 'sole', { categories: max + 1 }).categories).toBe(`catMax:${JSON.stringify({ max })}`);
  });
});
