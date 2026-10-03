// S-119: what a run targets and how hard it pushes. Everything comes from the environment (loadtest/run.sh sets it from
// loadtest/targets/<TARGET>.env and the command line); docs/runbooks/load-testing.md lists every variable.

/** The api (or the ingress in front of it). Local: the dedicated stack of loadtest/stack.sh. */
export const API_URL = (__ENV.API_URL || 'http://localhost:18080').replace(/\/$/, '');
/** smoke · load · stress · soak (profiles.js). */
export const PROFILE = __ENV.PROFILE || 'smoke';
/**
 * Multiplies every rate and VU count of the profile. 1 = the launch target with its 3× headroom (docs/perf/capacity.md);
 * a laptop or this repository's 4-core CI box runs a fraction of it (LOAD_SCALE=0.25 …) and says so in its results.
 */
export const SCALE = Number(__ENV.LOAD_SCALE || 1);
/** How long the soak holds the load (k6 duration). Staging: 2h; a laptop: 15m–20m is enough to see a leak. */
export const SOAK_DURATION = __ENV.SOAK_DURATION || '2h';
/** How long load holds its target rate; stress ramps over STRESS_DURATION. */
export const LOAD_DURATION = __ENV.LOAD_DURATION || '10m';
export const STRESS_DURATION = __ENV.STRESS_DURATION || '12m';
/**
 * How a request says who it is. `dev`: the api's dev auth header (X-Dev-User, `local` profile only). `bearer`: access
 * tokens of the load-test accounts from TOKENS_FILE ({"<userId>": "<token>"}; staging, see the runbook).
 */
export const AUTH_MODE = __ENV.AUTH_MODE || 'dev';
export const TOKENS_FILE = __ENV.TOKENS_FILE || '';
/** The ids the scenarios use: loadtest/seed/seed.sh writes it locally; staging has its own (runbook § Staging). */
export const MANIFEST = __ENV.MANIFEST || '../.data/manifest.json';
