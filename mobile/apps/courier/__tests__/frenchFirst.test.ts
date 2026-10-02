import { frenchFirst, type Regions } from '../src/api/courier';

/** S-116: French-first is the courier's market's rule, else its province's (region configuration). */
describe('frenchFirst', () => {
  const regions: Regions = {
    platformTimeZone: 'UTC',
    provinces: [{ code: 'XF', frenchFirst: true }, { code: 'XA', frenchFirst: false }],
    markets: [
      { id: 'mkt-f', city: 'Effeville', province: 'XF', timeZone: 'UTC' },
      { id: 'mkt-a', city: 'Alphaville', province: 'XA', timeZone: 'UTC' },
      { id: 'mkt-b', city: 'Betaville', province: 'XA', timeZone: 'UTC', frenchFirst: true },
    ],
  };

  it("follows the market's own rule, else its province's", () => {
    expect(frenchFirst(regions, 'mkt-f')).toBe(true);
    expect(frenchFirst(regions, 'Effeville')).toBe(true);
    expect(frenchFirst(regions, 'mkt-a')).toBe(false);
    expect(frenchFirst(regions, 'mkt-b')).toBe(true);
  });

  it('is off when nothing is known (older servers send no rule)', () => {
    expect(frenchFirst(undefined, 'mkt-f')).toBe(false);
    expect(frenchFirst(regions, null)).toBe(false);
    expect(frenchFirst(regions, 'Nowhere')).toBe(false);
    expect(frenchFirst({ platformTimeZone: 'UTC', markets: regions.markets.slice(0, 1) }, 'mkt-f')).toBe(false);
  });
});
