// S-119: pass/fail of a run = the S-113 SLOs (deploy/observability/slo/*.yaml, docs/runbooks/alerting.md). A run whose
// numbers would burn an error budget faster than allowed fails — k6 exits 99 and run.sh reports it.
//
//   checkout availability  99.9 % not 5xx          → slo_checkout_errors rate < 0.001
//   checkout latency       99 % within 2.5 s       → slo_checkout_latency p(99) < 2500 (so p95 too)
//   KDS ticket delivery    99.5 % within 5 s       → kds_ticket_delivery p(99.5) < 5000 (placed → on the screen)
//   KDS freshness          99.5 % within 2 s       → kds_stream_ready p(99.5) < 2000 (stream open → first event)
// Search and the nav badges have no SLO of their own: they get the NorthlineSlowRequests alert's p95 < 2 s and the
// NorthlineHighErrorRate alert's error budget tightened to the checkout's 0.1 %; and a load test that only measured
// 429s would prove nothing, so a single rate-limited search fails the run.
import { Counter, Rate, Trend } from 'k6/metrics';

export const checkoutLatency = new Trend('slo_checkout_latency', true);
export const checkoutErrors = new Rate('slo_checkout_errors');
export const kdsTicketDelivery = new Trend('kds_ticket_delivery', true);
export const kdsStreamReady = new Trend('kds_stream_ready', true);
export const kdsEvents = new Counter('kds_events');
export const serverErrors = new Rate('server_errors');
export const searchRateLimited = new Counter('search_rate_limited');
export const flowFailures = new Rate('flow_failures');

/** Thresholds for the scenarios that run (a smoke of one scenario must not fail on another's missing metric). */
export function thresholds(scenarios) {
  const t = {
    server_errors: ['rate<0.001'],
    flow_failures: ['rate<0.01'],
    checks: ['rate>0.99'],
  };
  if (scenarios.some(s => s.startsWith('checkout'))) {
    t.slo_checkout_latency = ['p(95)<2500', 'p(99)<2500'];
    t.slo_checkout_errors = ['rate<0.001'];
  }
  if (scenarios.includes('kds')) {
    t.kds_ticket_delivery = ['p(95)<5000', 'p(99.5)<5000'];
    t.kds_stream_ready = ['p(95)<2000', 'p(99.5)<2000'];
  }
  if (scenarios.includes('search')) {
    t['http_req_duration{flow:search}'] = ['p(95)<2000', 'p(99)<2500'];
    t.search_rate_limited = ['count<1'];
  }
  if (scenarios.includes('badges')) {
    t['http_req_duration{flow:badges}'] = ['p(95)<2000', 'p(99)<2500'];
  }
  return t;
}
