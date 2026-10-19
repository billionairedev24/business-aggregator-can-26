import { describe, expect, it } from 'vitest';
import { visitSlots } from './model';

/** Kitchen-visit slots are local times of the business's zone (they assumed one zone's UTC offset before). */
describe('visitSlots', () => {
  it('offers 10:00 and 14:00 on the next business days in the given zone', () => {
    const friday = new Date('2026-10-16T15:00:00Z');
    const slots = visitSlots(friday, 4, 'America/Halifax').map(d => d.toISOString());
    // Monday and Tuesday (the weekend is skipped), Atlantic daylight time (UTC−3)
    expect(slots).toEqual(['2026-10-19T13:00:00.000Z', '2026-10-19T17:00:00.000Z', '2026-10-20T13:00:00.000Z', '2026-10-20T17:00:00.000Z']);
    expect(visitSlots(friday, 2, 'America/Vancouver').map(d => d.toISOString())).toEqual(['2026-10-19T17:00:00.000Z', '2026-10-19T21:00:00.000Z']);
  });
});
