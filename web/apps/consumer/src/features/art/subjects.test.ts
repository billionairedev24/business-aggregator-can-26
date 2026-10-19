import { describe, expect, it } from 'vitest';
import { Car, Martini, Scissors, Storefront, Bread, Cow, BowlSteam, GraduationCap } from '@phosphor-icons/react';
import { subjectFor, toneFor, TONES } from './subjects';

describe('illustrated scenes: subjects and tones', () => {
  it('draws a category, department or cuisine by its words, the specific rule first', () => {
    expect(subjectFor('mobile-mechanic').main).toBe(Car);
    expect(subjectFor('barber-and-hair').main).toBe(Scissors); // not the cocktail bar ("bar" is a whole word only)
    expect(subjectFor('cocktail-and-mocktail-bar').main).toBe(Martini);
    expect(subjectFor('tutor-k-12').main).toBe(GraduationCap); // "education" holds "cat": no cat
    expect(subjectFor('Rye loaf bakery').main).toBe(Bread);
    expect(subjectFor('Ribeye, AAA').main).toBe(Cow);
    expect(subjectFor('vietnamese').main).toBe(BowlSteam);
  });

  it('falls back to a storefront, and keeps one tone per key', () => {
    expect(subjectFor('something-unheard-of').main).toBe(Storefront);
    expect(toneFor('bakery')).toBe(toneFor('bakery'));
    expect(TONES).toContain(toneFor('plumber'));
  });
});
