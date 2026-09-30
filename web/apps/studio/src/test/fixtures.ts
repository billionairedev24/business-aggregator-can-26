import type { Onboarding } from '../features/onboarding/api';
import type { Storefront } from '../features/storefront/api';
import { DEFAULT_ORDER } from '../features/storefront/sections';

export const MERCHANT = '01J9ZD3V00000000000000TST1';

export function storefront(over: Partial<Storefront> = {}): Storefront {
  return {
    id: '01J9ZD3V0000000000000SFTS1', merchantId: MERCHANT, slug: 'aspen-wrench', url: 'northline.ca/aspen-wrench', pageKind: 'business_page',
    brandColor: '#2f5d3a', brandContrast: 7.6, logo: null, tagline: 'Mobile mechanic · Calgary', ctaLabel: 'book_visit', announcement: null,
    customDomain: null, customDomainStatus: null, publishedAt: null,
    sections: DEFAULT_ORDER.provider.map((kind, i) => ({ id: `S${i}`, kind, position: i, enabled: true, required: kind === 'hero' || kind === 'cta', settings: {} })),
    business: { displayName: 'Aspen Wrench', type: 'provider', tier: 'registered', status: 'applicant', city: 'Calgary', about: 'Mobile mechanic since 2019.', serviceArea: 'Calgary + 40 km', sameDayCutoff: null, fulfilment: [], cuisines: [], verifiedFacts: ['licence:AMVIC'] },
    ...over,
  };
}

export function onboarding(over: Partial<Onboarding> = {}): Onboarding {
  return {
    merchantId: MERCHANT, type: 'provider', status: 'applicant', step: 'business', province: 'AB', workEmail: null, businessTermsAccepted: false,
    displayName: 'New business', city: null, business: null,
    checklist: [
      { id: 'V1', key: 'kyc', checkType: 'kyc', action: 'identity', registry: null, status: 'todo', reference: null, document: null, expiresOn: null, updatedAt: '2026-09-29T16:00:00Z' },
      { id: 'V2', key: 'registry', checkType: 'registry', action: 'instant', registry: null, status: 'todo', reference: null, document: null, expiresOn: null, updatedAt: '2026-09-29T16:00:00Z' },
      { id: 'V3', key: 'licence:AMVIC', checkType: 'licence', action: 'number', registry: 'AMVIC', status: 'todo', reference: null, document: null, expiresOn: null, updatedAt: '2026-09-29T16:00:00Z' },
      { id: 'V4', key: 'insurance', checkType: 'insurance', action: 'upload', registry: null, status: 'todo', reference: null, document: null, expiresOn: null, updatedAt: '2026-09-29T16:00:00Z' },
      { id: 'V5', key: 'bank', checkType: 'bank', action: 'instant', registry: null, status: 'todo', reference: null, document: null, expiresOn: null, updatedAt: '2026-09-29T16:00:00Z' },
      { id: 'V6', key: 'mfa', checkType: 'mfa', action: 'instant', registry: null, status: 'todo', reference: null, document: null, expiresOn: null, updatedAt: '2026-09-29T16:00:00Z' },
    ],
    checksComplete: 0, submittedAt: null, approvedAt: null,
    ...over,
  };
}

export const TAXONOMY = {
  type: 'provider', limit: 10,
  groups: [
    { id: 'service.automotive', name: 'Automotive', note: 'AMVIC licence checked', items: [{ id: 'service.automotive.mobile-mechanic', name: 'Mobile mechanic', regulator: 'AMVIC' }, { id: 'service.automotive.detailing', name: 'Detailing', regulator: null }] },
    { id: 'service.pets', name: 'Pets', note: '', items: [{ id: 'service.pets.dog-walker', name: 'Dog walker', regulator: null }] },
  ],
};
