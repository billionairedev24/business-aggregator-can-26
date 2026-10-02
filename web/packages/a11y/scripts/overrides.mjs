// Answers the page sweep needs to be consistent across screens (S-109); everything else comes from the recorded
// fixtures. Each override is `(method, path) → { status?, body } | undefined`. Test personas only; region-neutral
// (the province and zone come from the recorded region model, as the apps' tests use it).

/** The three seeded Studio businesses (db/seed-dev), one per portal. */
export const MERCHANTS = {
  provider: { id: '01J9ZD3V00000000000000PWM1', displayName: 'Prairie Wrench', type: 'provider' },
  seller: { id: '01J9ZD3V00000000000000PWP1', displayName: 'Prairie Wrench Parts', type: 'seller' },
  kitchen: { id: '01J9ZD3V00000000000000PDB1', displayName: 'Pho Dau Bo', type: 'kitchen' },
};

const REGION = {
  province: 'AB', provinceName: { en: 'Alberta', fr: 'Alberta' }, provinceIn: { en: 'in Alberta', fr: 'en Alberta' },
  provinceOf: { en: 'Alberta', fr: "de l'Alberta" }, timeZone: 'America/Edmonton', privacyLaw: 'ab_pipa',
};
const merchant = m => ({ ...m, tier: 'trusted', city: 'Calgary', status: 'active', role: 'owner', teamCount: 3, region: REGION });

/** Who is signed in, per app: the sweep switches it (pages/support.ts; the mock api takes POST /__mock/state). */
export const state = { studio: true, console: true, consumer: false };
export const setSignedIn = (app, signedIn) => { state[app] = signedIn; };

const ADMIN = {
  role: 'admin',
  screens: ['overview', 'orders', 'disputes', 'delivery', 'sellers', 'verify', 'vetting', 'trust', 'taxonomy', 'support', 'regions', 'finance', 'reports', 'privacy', 'api', 'team', 'profile', 'oncall'],
  actions: ['suspend', 'decide', 'refund', 'province', 'payouts', 'keys', 'verify', 'vet', 'dispatch', 'support', 'macros', 'privacy'],
};
const AMARA = { id: '01J9ZD3V0000000000000C0001', firstName: 'Amara', lastName: 'Osei', email: 'amara@example.ca', phone: '+14035550201', initials: 'AO', locale: 'en-CA', memberSince: '2026-03-02' };

/** A new provider at the business step (web/apps/studio/src/test/fixtures.ts `onboarding()`). */
const at = '2026-09-29T16:00:00Z';
const check = (id, key, checkType, action, registry = null) => ({ id, key, checkType, action, registry, status: 'todo', reference: null, document: null, expiresOn: null, updatedAt: at });
const ONBOARDING = {
  merchantId: '01J9ZD3V00000000000000ONB1', type: 'provider', status: 'applicant', step: 'business', province: 'AB', workEmail: null, businessTermsAccepted: true,
  displayName: 'New business', city: null, business: null,
  checklist: [check('V1', 'kyc', 'kyc', 'identity'), check('V2', 'registry', 'registry', 'instant'), check('V3', 'insurance', 'insurance', 'upload'), check('V4', 'bank', 'bank', 'instant'), check('V5', 'mfa', 'mfa', 'instant')],
  checksComplete: 0, submittedAt: null, approvedAt: null,
};

const studioUser = { id: '01J9ZD3V00000000000000RAV1', firstName: 'Ravi', lastName: 'Sandhu', email: 'ravi@prairiewrench.example', phone: '+1 403 555 0101', initials: 'RS', locale: 'en-CA', memberSince: '2026-03-02' };

export const OVERRIDES = {
  studio: [
    (m, p) => (p === '/bff/session' && m === 'GET' ? (state.studio ? { body: { user: studioUser, acr: 'mfa' } } : { status: 401, body: {} }) : undefined),
    (m, p) => (p === '/api/v1/me' && m === 'GET' ? { body: { ...studioUser, mfaPrimary: 'passkey', mfa: true } } : undefined),
    (m, p) => (p === '/api/v1/me/businesses' && m === 'GET' ? { body: { items: Object.values(MERCHANTS).map(merchant) } } : undefined),
    (m, p) => {
      const hit = /^\/api\/v1\/merchants\/([^/?]+)$/.exec(p.split('?')[0]);
      const found = hit && Object.values(MERCHANTS).find(x => x.id === hit[1]);
      return m === 'GET' && found ? { body: merchant(found) } : undefined;
    },
    (m, p) => (m === 'GET' && /^\/api\/v1\/merchants\/[^/]+\/nav-badges/.test(p) ? { body: { orders: '3', messages: '2' } } : undefined),
    (m, p) => (m === 'GET' && /^\/api\/v1\/merchants\/[^/]+\/onboarding$/.test(p) ? { body: ONBOARDING } : undefined),
  ],
  console: [
    (m, p) => (p === '/bff/session' && m === 'GET' ? (state.console ? { body: { user: { id: '01J9ZD3V00000000000000PNA1', firstName: 'Priya', lastName: 'Natarajan', initials: 'PN', locale: 'en-CA' }, acr: 'mfa' } } : { status: 401, body: {} }) : undefined),
    // an admin holds every screen (the recorded answer is a support agent's)
    (m, p) => (p === '/api/v1/console/me' && m === 'GET' ? { body: { userId: '01J9ZD3V00000000000000PNA1', roles: [ADMIN] } } : undefined),
  ],
  consumer: [
    (m, p) => (p === '/bff/session' && m === 'GET' ? { body: state.consumer ? { user: AMARA, guestId: null } : { user: null, guestId: 'g_a11y_sweep_0000000000' } } : undefined),
  ],
};
