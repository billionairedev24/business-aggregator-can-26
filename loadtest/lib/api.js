// S-119: requests as the apps send them — who is asking (dev auth or a bearer token), the language, JSON — and the
// bookkeeping every scenario shares: 5xx → server_errors, a broken flow → flow_failures.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { API_URL, AUTH_MODE, MANIFEST, PROFILE, TOKENS_FILE } from './config.js';
import { flowFailures, serverErrors } from './slo.js';

/**
 * The seeded ids (loadtest/seed/seed.sh), read once and shared by every VU; one SharedArray per list, since an element
 * is copied out on each access.
 */
const list = name => new SharedArray(name, () => JSON.parse(open(MANIFEST))[name] || []);
export const data = {
  kitchens: list('kitchens'),
  providers: list('providers'),
  sellers: list('sellers'),
  members: list('members'),
  customers: list('customers'),
  staff: list('staff'),
  meta: new SharedArray('meta', () => {
    const m = JSON.parse(open(MANIFEST));
    return [{ market: m.market, province: m.province, center: m.center }];
  })[0],
};

const tokens = new SharedArray('tokens', () => (TOKENS_FILE ? [JSON.parse(open(TOKENS_FILE))] : [{}]));
let tokenMap; // this VU's copy, taken once

export function who(userId) {
  if (AUTH_MODE === 'dev') {
    return { 'X-Dev-User': userId };
  }
  tokenMap = tokenMap || tokens[0];
  const token = tokenMap[userId];
  if (!token) {
    throw new Error(`AUTH_MODE=bearer: no token for ${userId} in TOKENS_FILE`);
  }
  return { Authorization: `Bearer ${token}` };
}

function params(userId, opts) {
  const headers = Object.assign(
    { 'Content-Type': 'application/json', 'Accept-Language': opts.lang === 'fr' ? 'fr-CA' : 'en-CA' },
    userId ? who(userId) : {},
    opts.headers || {},
  );
  const p = {
    headers,
    tags: Object.assign({ name: opts.name, flow: opts.flow }, opts.tags || {}),
    timeout: opts.timeout || '30s',
  };
  if (opts.expected) p.responseCallback = http.expectedStatuses(...opts.expected);
  return p;
}

function track(res, opts) {
  serverErrors.add(res.status >= 500 || res.status === 0, { flow: opts.flow });
  return res;
}

/** opts: { name (the URL template, groups the metrics), flow, lang, headers, expected: [statuses], tags } */
export function get(path, userId, opts) {
  return track(http.get(`${API_URL}${path}`, params(userId, opts)), opts);
}

export function post(path, body, userId, opts) {
  return track(http.post(`${API_URL}${path}`, body === null ? null : JSON.stringify(body), params(userId, opts)), opts);
}

export function json(res) {
  try {
    return res.json();
  } catch (_) {
    return null;
  }
}

/** Marks one end-to-end flow (a checkout, a booking …) as done or broken, logging why once in a while. */
export function flow(ok, what, res) {
  flowFailures.add(!ok);
  if (!ok && (PROFILE === 'smoke' || Math.random() < 0.02)) { // every failure of a smoke, a sample under load
    console.warn(`${what} failed: ${res ? `${res.status} ${String(res.body).slice(0, 200)}` : 'no response'}`);
  }
  return ok;
}

export const pick = list => list[Math.floor(Math.random() * list.length)];

/**
 * A customer for this VU's next checkout. VUs draw from disjoint slices of the customers, so one person never checks
 * out twice at the same moment (the test would measure its own races, not the api).
 */
export function customer() {
  const n = data.customers.length;
  const parts = Math.min(n, 500);
  const slot = (exec.vu.idInTest - 1) % parts;
  return data.customers[slot + parts * Math.floor(Math.random() * Math.max(1, Math.floor(n / parts)))];
}
export const lang = (frShare = 0.35) => (Math.random() < frShare ? 'fr' : 'en');
export const key = prefix => `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
