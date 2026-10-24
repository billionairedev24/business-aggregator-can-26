import { describe, expect, it } from 'vitest';
import { niceTop } from './Charts';

// S-180: the ColumnChart's y-axis has four even steps of a round size (the Studio earnings chart drew $63 · $125 · $188)
describe('niceTop', () => {
  const steps = (max: number) => [1, 2, 3, 4].map(i => (niceTop(max) / 4) * i);
  it('picks a round step, and the top is four of them', () => {
    expect(steps(18_800)).toEqual([5_000, 10_000, 15_000, 20_000]); // $188 in cents → $50 steps to $200
    expect(steps(23_500)).toEqual([10_000, 20_000, 30_000, 40_000]);
    expect(steps(9_000)).toEqual([2_500, 5_000, 7_500, 10_000]);
    expect(niceTop(1_000)).toBe(1_000);
    expect(niceTop(7)).toBe(8); // counts: steps of 2
    expect(niceTop(3)).toBe(4); // never a fractional step
  });
  it('covers the largest value, and an empty chart still has an axis', () => {
    for (const max of [1, 9, 13, 99, 101, 640, 12_345, 987_654]) expect(niceTop(max)).toBeGreaterThanOrEqual(max);
    expect(niceTop(0)).toBe(4);
  });
});
