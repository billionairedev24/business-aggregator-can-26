/**
 * Every route of the three web apps that `make ui-shots` photographs (docs/LOCAL_DEVELOPMENT.md § "Look at the UI").
 * `name` is the file name under ui-shots/<app>/ (`-d` desktop 1440×900, `-m` phone 390×844); `signedIn` picks the
 * mock session; `ready` is what to wait for before the shot (default: the page's h1, or 8 s without one); `click` is
 * pressed once the page is ready (a Playwright selector: open a detail sheet or an editor step), then the shot is taken (the window only: a sheet is fixed to it);
 * `sheet` photographs the window only for a sheet the address opens (`?application=`); `down` makes the mock api answer
 * that status to everything (the BFF or the api down: the error states); `now` fixes the page's clock (Date.now) at that
 * instant, for what depends on the time of day (the delivery cut-off strip counts down to the fixtures' run, E-15);
 * `storage` sets localStorage entries first (S-173: the food order being built, saved-for-later lines).
 * A new route goes here too: shots.spec.ts photographs whatever this lists, in English and in French (S-180: every
 * route, `<route>-fr-<d|m>.png`), and `make ui-a11y` checks it (a11y.spec.ts).
 */
export interface ShotRoute { name: string; path: string; signedIn?: boolean; ready?: string; click?: string; sheet?: boolean; down?: number; now?: string; /** S-173: localStorage entries set before the page loads (a food order being built). */ storage?: Record<string, string> }

/** 2 h 48 min before the recorded Shop landing's run closes (fixtures/consumer.json `run.orderBy`), as design/revamp/a.html shows it. */
const BEFORE_CUTOFF = '2026-09-30T20:32:00Z';
/** S-173: the food order being built (features/food/foodCart.ts), for the food checkout shot. */
const FOOD_CART = JSON.stringify({ merchantId: 'K1', slug: 'pho-lucky', name: 'Pho Lucky', lines: [
  { key: 'D1:O2', kind: 'item', itemId: 'D1', title: 'Pho tai · Large', qty: 2, unitCents: 1900, optionIds: ['O2'], choices: ['Large'], itemIds: [] }] });
/**
 * S-180: the place chosen in the location dialog (features/location/sources.ts `nl.location`): the food checkout needs a
 * street with coordinates (saved addresses carry none), so without it the shot showed "Add the address" and Pay $0.00.
 */
const PLACE = JSON.stringify({ label: '1204 17 Ave SW, Calgary', city: 'Calgary', street: '1204 17 Ave SW', unit: 'Apt 804', province: 'AB', postalCode: 'T2T 0B8', lat: 51.0379, lng: -114.0894, marketId: 'mkt-calgary' });
/** S-173: a line saved for later (features/cart/saved.ts). */
const SAVED = JSON.stringify([{ offerId: 'o-saved', variantId: null, productId: 'P2', name: 'Free-run eggs, dozen', option: null, unit: 'dozen', imageUrl: null, unitCents: 650, qty: 1, shopName: 'Bow Valley Dairy' }]);
const PROVIDER = '01J9ZD3V00000000000000PWM1', SELLER = '01J9ZD3V00000000000000PWP1', KITCHEN = '01J9ZD3V00000000000000PDB1';

export const ROUTES: Record<'consumer' | 'studio' | 'console', ShotRoute[]> = {
  consumer: [
    { name: 'home', path: '/', now: BEFORE_CUTOFF },
    { name: 'home-signed-in', path: '/', signedIn: true, now: BEFORE_CUTOFF },
    { name: 'search', path: '/search?q=sourdough' },
    { name: 'search-empty', path: '/search?q=zzz' },
    { name: 'shop', path: '/shop', now: BEFORE_CUTOFF },
    { name: 'shop-department', path: '/shop/bakery', now: BEFORE_CUTOFF },
    { name: 'search-shop', path: '/search?q=bakery&scope=shop&delivery=tonight', now: BEFORE_CUTOFF },
    { name: 'search-filters', path: '/search?q=bakery&scope=shop', click: '.nl-results-filters-toggle' },
    { name: 'product', path: '/products/P1', now: BEFORE_CUTOFF },
    // S-172: a shop's page; the provider page with a day chosen in its booking box
    { name: 'store', path: '/shop/stores/M1?dept=bakery', now: BEFORE_CUTOFF },
    { name: 'services', path: '/services' },
    { name: 'services-category', path: '/services/mobile-mechanic' },
    { name: 'services-providers', path: '/services/mobile-mechanic/providers' },
    { name: 'services-quote', path: '/services/mobile-mechanic/quote', signedIn: true },
    { name: 'provider', path: '/providers/prairie-wrench', now: BEFORE_CUTOFF },
    { name: 'provider-day', path: '/providers/prairie-wrench', now: BEFORE_CUTOFF, click: '.nl-prov-days [role="radio"]:not([disabled]) >> nth=1' },
    { name: 'provider-book', path: '/providers/prairie-wrench/book', signedIn: true, now: BEFORE_CUTOFF },
    { name: 'cart', path: '/cart', signedIn: true, now: BEFORE_CUTOFF, storage: { 'nl.savedForLater': SAVED } },
    { name: 'checkout', path: '/checkout', signedIn: true },
    { name: 'checkout-complete', path: '/checkout/complete?order=ORD1&ref=NL-50001', signedIn: true },
    { name: 'order', path: '/orders/ORD1', signedIn: true },
    { name: 'booking', path: '/bookings/B1', signedIn: true },
    { name: 'quote', path: '/quotes/q1', signedIn: true },
    { name: 'quote-request', path: '/quotes/requests/r1', signedIn: true },
    { name: 'account', path: '/account', signedIn: true },
    ...(['addresses', 'payments', 'security', 'notifications', 'favourites', 'profile', 'help'] as const)
      .map(tab => ({ name: `account-${tab}`, path: `/account?tab=${tab}`, signedIn: true })),
    { name: 'account-orders', path: '/account/orders', signedIn: true },
    { name: 'account-bookings', path: '/account/orders?tab=bookings', signedIn: true },
    { name: 'account-problem', path: '/account/problem/order/O1', signedIn: true },
    { name: 'food', path: '/food' },
    { name: 'food-kitchen', path: '/food/pho-lucky' },
    { name: 'food-checkout', path: '/food/checkout', signedIn: true, storage: { 'nl.foodCart': FOOD_CART, 'nl.location': PLACE } },
    { name: 'food-group', path: '/food/group/PHO123', signedIn: true },
    { name: 'food-order', path: '/food/orders/F1', signedIn: true },
    { name: 'help', path: '/help' },
    { name: 'sell', path: '/sell' },
    { name: 'location', path: '/location' },
    { name: 'sign-in', path: '/sign-in' },
    { name: 'register', path: '/register' },
    { name: 'forgot-password', path: '/forgot-password' },
    { name: 'not-found', path: '/no-such-page' },
    // the error pages: the server's own (NL_ERROR_PREVIEW=1) and the shell's state with the consumer-bff answering 503
    { name: 'error-500', path: '/__nl/error?status=500' },
    { name: 'error-503', path: '/__nl/error?status=503' },
    { name: 'error-404', path: '/__nl/error?status=404' },
    { name: 'unreachable', path: '/services', down: 503 },
  ],
  studio: [
    { name: 'sign-in', path: '/sign-in', signedIn: false },
    { name: 'register', path: '/register', signedIn: false },
    { name: 'onboarding', path: '/onboarding', signedIn: true },
    { name: 'onboarding-business', path: '/onboarding/business?m=01J9ZD3V00000000000000NB01&type=provider', signedIn: true },
    // S-176: the three flows (provider above; seller and kitchen), and the Account step with its type picked
    { name: 'onboarding-seller', path: '/onboarding?type=seller', signedIn: true },
    { name: 'onboarding-kitchen', path: '/onboarding?type=kitchen', signedIn: true },
    { name: 'onboarding-business-seller', path: '/onboarding/business?m=01J9ZD3V00000000000000NB01&type=seller', signedIn: true },
    { name: 'onboarding-business-kitchen', path: '/onboarding/business?m=01J9ZD3V00000000000000NB01&type=kitchen', signedIn: true },
    { name: 'onboarding-categories', path: '/onboarding/categories?m=01J9ZD3V00000000000000NB01&type=provider', signedIn: true },
    // the legal details as four provinces serve them (region data; shots/fixtures.mjs ONB_PROVINCES)
    { name: 'onboarding-business-ab', path: '/onboarding/business?m=01J9ZD3V00000000000000NB02&type=provider', signedIn: true, ready: '.nl-ob-legal-head' },
    { name: 'onboarding-business-on', path: '/onboarding/business?m=01J9ZD3V00000000000000NB03&type=provider', signedIn: true, ready: '.nl-ob-legal-head' },
    { name: 'onboarding-business-bc', path: '/onboarding/business?m=01J9ZD3V00000000000000NB04&type=provider', signedIn: true, ready: '.nl-ob-legal-head' },
    { name: 'onboarding-business-qc', path: '/onboarding/business?m=01J9ZD3V00000000000000NB05&type=provider', signedIn: true, ready: '.nl-ob-legal-head' },
    { name: 'onboarding-verification', path: '/onboarding/verification?m=01J9ZD3V00000000000000GATE&type=provider', signedIn: true },
    { name: 'onboarding-gate', path: '/onboarding/review?m=01J9ZD3V00000000000000GATE&type=provider', signedIn: true },
    { name: 'identity-done', path: '/identity/done', signedIn: true },
    { name: 'invite', path: '/invite/tok', signedIn: true },
    { name: 'dashboard', path: `/b/${PROVIDER}` },
    { name: 'dashboard-seller', path: `/b/${SELLER}` },
    { name: 'dashboard-kitchen', path: `/b/${KITCHEN}` },
    // the phone's disclosure menu open (on a desktop there is no menu button: the window as it is)
    { name: 'dashboard-menu', path: `/b/${SELLER}`, click: '.nl-topbar-menu' },
    { name: 'appointments', path: `/b/${PROVIDER}/appointments` },
    { name: 'appointments-job', path: `/b/${PROVIDER}/appointments`, click: '.nl-appt-job[aria-pressed]' },
    { name: 'availability', path: `/b/${PROVIDER}/availability` },
    { name: 'listings', path: `/b/${SELLER}/listings` },
    { name: 'listings-services', path: `/b/${PROVIDER}/listings` },
    { name: 'listing-new', path: `/b/${SELLER}/listings/new` },
    { name: 'listing-editor', path: `/b/${SELLER}/listings/p1` },
    { name: 'listing-editor-images', path: `/b/${SELLER}/listings/p1`, click: '#tab-images' },
    { name: 'service-new', path: `/b/${PROVIDER}/listings/new` },
    { name: 'listings-bulk', path: `/b/${SELLER}/listings/bulk` },
    { name: 'orders', path: `/b/${SELLER}/orders` },
    { name: 'orders-detail', path: `/b/${SELLER}/orders`, click: '.is-clickable' },
    { name: 'messages', path: `/b/${PROVIDER}/messages` },
    { name: 'page', path: `/b/${PROVIDER}/page` },
    { name: 'earnings', path: `/b/${PROVIDER}/earnings` },
    { name: 'payouts', path: `/b/${PROVIDER}/payouts` },
    { name: 'refunds', path: `/b/${PROVIDER}/refunds` },
    { name: 'reports', path: `/b/${PROVIDER}/reports` },
    { name: 'reviews', path: `/b/${PROVIDER}/reviews` },
    { name: 'compliance', path: `/b/${PROVIDER}/compliance` },
    { name: 'settings', path: `/b/${PROVIDER}/settings` },
    { name: 'settings-team', path: `/b/${PROVIDER}/settings?tab=team` },
    { name: 'settings-security', path: `/b/${PROVIDER}/settings?tab=security` },
    { name: 'settings-notifications', path: `/b/${PROVIDER}/settings?tab=notifications` },
    { name: 'settings-api', path: `/b/${PROVIDER}/settings?tab=api` },
    { name: 'standing', path: `/b/${PROVIDER}/standing` },
    { name: 'messages-seller', path: `/b/${SELLER}/messages` },
    { name: 'earnings-seller', path: `/b/${SELLER}/earnings` },
    { name: 'help', path: `/b/${PROVIDER}/help` },
    { name: 'kitchen-live', path: `/b/${KITCHEN}/kitchen/live` },
    { name: 'kitchen-menu', path: `/b/${KITCHEN}/kitchen/menu` },
    { name: 'kitchen-combos', path: `/b/${KITCHEN}/kitchen/combos` },
    { name: 'kitchen-hours', path: `/b/${KITCHEN}/kitchen/hours` },
    { name: 'not-found', path: '/no-such-page' },
    { name: 'unreachable', path: `/b/${PROVIDER}`, down: 503, ready: '.nl-failure-title' },
  ],
  console: [
    { name: 'sign-in', path: '/sign-in', signedIn: false },
    { name: 'overview', path: '/' },
    { name: 'verification', path: '/verification' },
    // the two-person review: stage 1 (Review) and stage 2 (Decide) on the application's page (S-177)
    { name: 'verification-review', path: '/verification/M2' },
    { name: 'verification-decide', path: '/verification/M1' },
    { name: 'verification-registry', path: '/verification?view=registry' },
    { name: 'vetting', path: '/vetting' },
    { name: 'vetting-licences', path: '/vetting?view=licences' },
    { name: 'vetting-age', path: '/vetting?view=age' },
    { name: 'sellers', path: '/sellers' },
    // S-177: two rows picked — the ink bulk bar with the red Suspend…
    { name: 'sellers-selected', path: '/sellers', click: 'tbody tr:nth-child(2) td:first-child label, .nl-dt-card:nth-child(2) .nl-dt-card-check' },
    { name: 'seller', path: '/sellers/m2' },
    { name: 'orders', path: '/orders' },
    { name: 'order', path: '/orders/NL-48102' },
    { name: 'disputes', path: '/disputes' },
    { name: 'dispute', path: '/disputes/dispute/D1' },
    { name: 'disputes-card', path: '/disputes/card' },
    { name: 'disputes-empty', path: '/disputes?province=QC' },
    { name: 'trust', path: '/trust' },
    { name: 'trust-appeals', path: '/trust/appeals' },
    { name: 'catalogue', path: '/catalogue' },
    { name: 'delivery', path: '/delivery' },
    { name: 'finance', path: '/finance' },
    { name: 'provinces', path: '/provinces' },
    { name: 'team', path: '/team' },
    { name: 'support', path: '/support' },
    { name: 'support-case', path: '/support/T1' },
    { name: 'offers', path: '/offers' },
    { name: 'reports', path: '/reports' },
    { name: 'integrations', path: '/integrations' },
    { name: 'privacy', path: '/privacy' },
    { name: 'privacy-retention', path: '/privacy?view=retention' },
    { name: 'profile', path: '/profile' },
    { name: 'profile-audit', path: '/profile?tab=audit' },
    { name: 'reports-error', path: '/reports?province=QC', ready: '.nl-errorstate' },
    { name: 'not-found', path: '/no-such-screen' },
    { name: 'unreachable', path: '/', down: 503, ready: '.nl-failure-title' },
    { name: 'pilot', path: '/pilot' },
    { name: 'uat', path: '/uat' },
    { name: 'go-live', path: '/go-live' },
    { name: 'on-call', path: '/on-call' },
  ],
};
