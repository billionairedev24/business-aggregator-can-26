import { isFrenchFirst, type Regions } from '../src/api/geo';

/** S-116: French-first is the delivery place's market rule, else its province's (region configuration). */
describe('isFrenchFirst', () => {
  const regions: Regions = {
    platformTimeZone: 'UTC',
    provinces: [
      { code: 'XF', name: 'Xf', status: 'live', timeZone: 'UTC', frenchFirst: true },
      { code: 'XA', name: 'Xa', status: 'live', timeZone: 'UTC', frenchFirst: false },
    ],
    markets: [
      { id: 'mkt-f', city: 'Effeville', province: 'XF', timeZone: 'UTC', status: 'live' },
      { id: 'mkt-b', city: 'Betaville', province: 'XA', timeZone: 'UTC', status: 'live', frenchFirst: true },
    ],
  };

  it("follows the market's own rule, else its province's", () => {
    expect(isFrenchFirst(regions, { marketId: 'mkt-f' })).toBe(true);
    expect(isFrenchFirst(regions, { city: 'effeville' })).toBe(true);
    expect(isFrenchFirst(regions, { province: 'XA' })).toBe(false);
    expect(isFrenchFirst(regions, { province: 'XA', city: 'Betaville' })).toBe(true);
    expect(isFrenchFirst(regions, { province: 'XF', city: 'Elsewhere' })).toBe(true);
  });

  it('is off when nothing is known', () => {
    expect(isFrenchFirst(undefined, { province: 'XF' })).toBe(false);
    expect(isFrenchFirst(regions, {})).toBe(false);
  });
});
