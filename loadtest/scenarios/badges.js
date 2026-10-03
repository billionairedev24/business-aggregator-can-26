// S-119 nav badges: the counts every signed-in screen keeps fresh, at the rate the apps refetch them.
//   Studio   GET /api/v1/merchants/{id}/nav-badges — on every page (React Query staleTime 30 s), on every live event and
//            every 60 s while a screen is open: one call per member every ~45 s on average; 30 % in French.
//   consumer GET /api/v1/cart — the header's cart count, once per page view (staleTime 30 s) of a signed-in customer.
//   console  GET /api/v1/console/me — the staff shell (staleTime 5 min): a trickle.
// The rates live in profiles.js; this picks who is asking.
import { check } from 'k6';
import { data, flow, get, lang, pick } from '../lib/api.js';

export function badgesStudio() {
  const m = pick(data.members);
  const l = lang(0.3);
  const res = get(`/api/v1/merchants/${m.merchantId}/nav-badges`, m.userId,
    { name: '/api/v1/merchants/{id}/nav-badges', flow: 'badges', lang: l, tags: { app: 'studio', role: m.role } });
  flow(check(res, { 'studio badges 200': r => r.status === 200 }), 'studio badges', res);
}

export function badgesConsumer() {
  const res = get('/api/v1/cart', pick(data.customers),
    { name: '/api/v1/cart', flow: 'badges', lang: lang(), tags: { app: 'consumer' } });
  flow(check(res, { 'cart count 200': r => r.status === 200 }), 'cart count', res);
}

export function badgesConsole() {
  if (!data.staff.length) return;
  const res = get('/api/v1/console/me', pick(data.staff), { name: '/api/v1/console/me', flow: 'badges', tags: { app: 'console' } });
  flow(check(res, { 'console me 200': r => r.status === 200 }), 'console me', res);
}
