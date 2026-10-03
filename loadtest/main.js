// S-119 load and soak tests (k6 + xk6-sse). Run through loadtest/run.sh or the make targets — docs/runbooks/load-testing.md.
//
//   SCENARIOS  which journeys (comma-separated; default all of them together = the mixed traffic):
//              search · checkout_food · checkout_goods · checkout_booking · kds · badges_studio · badges_consumer ·
//              badges_console; shorthands: checkout, badges, all
//   PROFILE    smoke · load · stress · soak (lib/profiles.js); LOAD_SCALE multiplies the target
import { ALL, plan } from './lib/profiles.js';
import { thresholds } from './lib/slo.js';
import { setKitchensWithScreens } from './lib/kitchens.js';

export { search } from './scenarios/search.js';
export { checkoutBooking, checkoutFood, checkoutGoods } from './scenarios/checkout.js';
export { kdsScreen } from './scenarios/kds.js';
export { badgesConsole, badgesConsumer, badgesStudio } from './scenarios/badges.js';

const GROUPS = {
  all: ALL,
  checkout: ['checkout_food', 'checkout_goods', 'checkout_booking'],
  badges: ['badges_studio', 'badges_consumer', 'badges_console'],
};

const selected = [...new Set((__ENV.SCENARIOS || 'all').split(',').map(s => s.trim()).filter(Boolean)
  .flatMap(s => GROUPS[s] || [s]))];
const unknown = selected.filter(s => !ALL.includes(s));
if (unknown.length) {
  throw new Error(`unknown scenario(s) ${unknown.join(', ')}: use ${ALL.join(', ')}, checkout, badges, all`);
}
const run = plan(selected);
setKitchensWithScreens(run.kitchens);

export const options = {
  scenarios: run.scenarios,
  thresholds: thresholds(selected.map(s => (s.startsWith('badges') ? 'badges' : s))),
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  discardResponseBodies: false,
  // a stuck connection must not hold a VU forever; the SSE streams set their own timeout
  setupTimeout: '60s',
  insecureSkipTLSVerify: false,
  userAgent: 'northline-loadtest/S-119 (k6)',
};
