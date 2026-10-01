/** Test data: the console overview as the api answers it (S-91), with design 03's figures. */
export const AS_OF = '2026-09-08T18:00:00Z'; // a Tuesday afternoon in the test zone

/** The api's answer, as design 03's overview shows it. */
export function overview(overrides: Record<string, unknown> = {}) {
  return {
    asOf: AS_OF, timeZone: 'America/Edmonton', scope: { province: null, market: null, city: null }, from: '2026-09-01T18:00:00Z',
    headline: { gmvCents: 21_200_000, sellers: 1204, verifications: 7, disputes: 3 },
    kpis: { gmvCents: 21_200_000, previousGmvCents: 19_450_000, revenueCents: 2_410_000, orders: 4155, bookings: 2657, onTimeRatio: 0.968, disputeRate: 0.009, averageDeliveryFeeCents: 271 },
    weeks: Array.from({ length: 12 }, (_, i) => ({ start: `2026-06-${String(16 + i).padStart(2, '0')}T18:00:00Z`, goodsCents: 9_800_000 + i * 100_000, servicesCents: 6_200_000 })),
    health: [
      { key: 'api_p95', value: 184.2, status: 'ok' }, { key: 'search_p95', value: 92, status: 'ok' }, { key: 'kafka_lag', value: null, status: 'unknown' },
      { key: 'stripe', value: 0, status: 'ok' }, { key: 'tracking_streams', value: 27_000, status: 'ok' }, { key: 'courier_app', value: 1, status: 'degraded' },
    ],
    workQueue: {
      verifications: { count: 7, oldest: '2026-09-05T18:00:00Z' }, flaggedListings: { count: 5, oldest: '2026-09-08T16:00:00Z' },
      disputes: { count: 3, oldest: '2026-09-06T18:00:00Z' }, stuckRuns: { count: 1, oldestOverdue: '2026-09-08T17:48:00Z' },
      trustFlags: { count: 5, oldest: null, offPlatformPayment: true }, sellersBelowFloor: 41,
    },
    live: { couriersOnRuns: 27, couriersActive: 41, providersOnJobs: 118, escrowHeldCents: 41_822_000,
      pools: [{ market: 'calgary', city: 'Calgary', label: 'R-611', orders: 312, closesAt: '2026-09-08T23:19:00Z', startsAt: '2026-09-09T00:00:00Z' }] },
    ...overrides,
  };
}

