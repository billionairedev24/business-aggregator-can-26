// S-119: how hard each profile pushes. TARGET is the launch peak with 3× headroom (docs/perf/capacity.md § Assumptions):
// one market, 300 businesses (100 kitchens), 20,000 customers, the dinner peak hour; LOAD_SCALE multiplies it.
//
//   smoke   30 s, a trickle of every journey: does each one work end to end against this target?
//   load    1 min ramp, then LOAD_DURATION (10 min) at the target rate; thresholds = the SLOs
//   stress  ramps 0.5× → 5× the target over STRESS_DURATION (12 min) to find the knee (thresholds still evaluated;
//           expected to fail — the summary and the per-minute timeline say where)
//   soak    the load profile for SOAK_DURATION (2 h on staging; 15–20 min on a laptop) to see leaks and creep
import { LOAD_DURATION, PROFILE, SCALE, SOAK_DURATION, STRESS_DURATION } from './config.js';

/** Iterations per second (screens for kds) at SCALE = 1. One search iteration ≈ 1.1 requests, a checkout 5–8. */
export const TARGET = {
  search: 150, //            50/s at the peak: 1,200 sessions × one search or suggestion per 24 s
  checkout_food: 0.84, //    0.28/s: 100 kitchens × 10 orders an hour
  checkout_goods: 0.2, //    0.07/s: 240 shop orders an hour
  checkout_booking: 0.075, // 0.025/s: 90 bookings an hour
  kds: 450, //               screens: 150 kitchens × 3 (line, expo, the owner's phone)
  badges_studio: 17, //      5.6/s: 250 Studio users, one refetch every ~45 s
  badges_consumer: 60, //    20/s: 1,200 customer sessions, one page view a minute
  badges_console: 0.1, //    a dozen staff, every 5 minutes
};
export const SCREENS_PER_KITCHEN = 3;
export const ALL = Object.keys(TARGET);

const SMOKE = {
  search: 2, checkout_food: 0.2, checkout_goods: 0.1, checkout_booking: 0.1, kds: 3,
  badges_studio: 1, badges_consumer: 1, badges_console: 0.2,
};

const EXEC = {
  search: 'search', checkout_food: 'checkoutFood', checkout_goods: 'checkoutGoods',
  checkout_booking: 'checkoutBooking', kds: 'kdsScreen', badges_studio: 'badgesStudio',
  badges_consumer: 'badgesConsumer', badges_console: 'badgesConsole',
};

/** Rough seconds one iteration takes, to pre-allocate VUs for an arrival rate. */
const ITERATION_S = { search: 0.3, checkout_food: 1.5, checkout_goods: 2.5, checkout_booking: 2, badges_studio: 0.3,
  badges_consumer: 0.3, badges_console: 0.3 };

function seconds(duration) {
  const m = /^(\d+)(s|m|h)$/.exec(duration);
  if (!m) throw new Error(`durations are like 30s, 15m, 2h — not ${duration}`);
  return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}

/** k6's arrival rate is an integer per timeUnit: express small rates per minute. */
function rate(perSecond) {
  return perSecond >= 10 ? { rate: Math.round(perSecond), timeUnit: '1s' } : { rate: Math.max(1, Math.round(perSecond * 60)), timeUnit: '1m' };
}

function arrival(name, perSecond, stages) {
  const r = rate(perSecond);
  const peak = Math.max(...stages.map(s => s.factor));
  const vus = Math.ceil(perSecond * peak * ITERATION_S[name] * 3) + 2;
  return {
    executor: 'ramping-arrival-rate', exec: EXEC[name], timeUnit: r.timeUnit, startRate: 0,
    preAllocatedVUs: vus, maxVUs: vus * 4,
    stages: stages.map(s => ({ duration: s.duration, target: Math.max(1, Math.round(r.rate * s.factor)) })),
    tags: { scenario_group: name },
  };
}

function screens(count, stages) {
  return {
    executor: 'ramping-vus', exec: EXEC.kds, startVUs: 0, gracefulRampDown: '5s', gracefulStop: '10s',
    stages: stages.map(s => ({ duration: s.duration, target: Math.max(1, Math.round(count * s.factor)) })),
    tags: { scenario_group: 'kds' },
  };
}

/** The k6 scenarios of the run, and how many kitchens have screens open (food orders go to those). */
export function plan(selected) {
  const names = selected;
  const scenarios = {};
  let stages;
  const base = {};
  if (PROFILE === 'smoke') {
    names.forEach(n => (base[n] = SMOKE[n]));
    stages = [{ duration: '30s', factor: 1 }];
  } else {
    names.forEach(n => (base[n] = TARGET[n] * SCALE));
    if (PROFILE === 'load' || PROFILE === 'soak') {
      stages = [{ duration: '1m', factor: 1 }, { duration: PROFILE === 'soak' ? SOAK_DURATION : LOAD_DURATION, factor: 1 }];
    } else if (PROFILE === 'stress') {
      const step = `${Math.max(30, Math.round(seconds(STRESS_DURATION) / 12))}s`;
      stages = [0.5, 1, 2, 3, 4, 5].flatMap(f => [{ duration: step, factor: f }, { duration: step, factor: f }]);
    } else {
      throw new Error(`PROFILE must be smoke, load, stress or soak — not ${PROFILE}`);
    }
  }
  for (const n of names) {
    if (n === 'kds') {
      // screens open first: tickets placed before a kitchen's screen is listening are not deliveries
      scenarios.kds = screens(base.kds, stages);
    } else {
      scenarios[n] = arrival(n, base[n], stages);
      if (n.startsWith('checkout') && names.includes('kds')) scenarios[n].startTime = '10s';
    }
  }
  const kitchens = names.includes('kds') ? Math.ceil(base.kds / (PROFILE === 'smoke' ? 1 : SCREENS_PER_KITCHEN)) : 0;
  return { scenarios, kitchens };
}
