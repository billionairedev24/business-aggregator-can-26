import { afterEach, describe, expect, it } from 'vitest';
import { addDays, configurePlatformTimeZone, messagesFor, firstDayOfWeek, formatDate, formatDay, formatZonedHours, platformTimeZone, setTimeZone, timeZone, unitPriceOf, weekStartOf, zonedInstant } from './i18n';

/** S-134: no zone in code — the platform zone is configured and the market's or merchant's zone is set on top. */
describe('time zones', () => {
  afterEach(() => { setTimeZone(undefined); configurePlatformTimeZone('UTC'); });

  it('reads in the platform zone until a market zone is set, and ignores unknown zones', () => {
    configurePlatformTimeZone('America/Halifax');
    expect(timeZone()).toBe('America/Halifax');
    setTimeZone('America/Vancouver');
    expect(timeZone()).toBe('America/Vancouver');
    expect(platformTimeZone()).toBe('America/Halifax');
    setTimeZone('Not/AZone');
    expect(timeZone()).toBe('America/Halifax');
    configurePlatformTimeZone('');
    expect(platformTimeZone()).toBe('America/Halifax');
  });

  it('formats in the current zone, or the one given', () => {
    const at = '2026-09-30T05:30:00Z';
    setTimeZone('America/Vancouver'); // 22:30 on the 29th
    expect(formatDate(at, 'en', 'date')).toBe('Sep 29');
    expect(formatDate(at, 'en', 'date', 'America/Toronto')).toBe('Sep 30'); // 01:30 on the 30th
  });
});

/** Demo rehearsal 2026-10-19: calendars start the week where the locale does, and a week always holds today. */
describe('weeks and calendar dates', () => {
  afterEach(() => setTimeZone(undefined));

  it('starts the week on the locale\'s first day (CLDR: Sunday in en-CA and fr-CA)', () => {
    expect(firstDayOfWeek('en')).toBe(7);
    expect(firstDayOfWeek('fr')).toBe(7);
    expect(weekStartOf('2026-10-18', 'en')).toBe('2026-10-18'); // a Sunday is the first day of its own week
    expect(weekStartOf('2026-10-24', 'en')).toBe('2026-10-18'); // Saturday
    expect(weekStartOf('2026-10-19', 'fr')).toBe('2026-10-18');
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
  });

  it('formats a calendar date the same in every zone', () => {
    setTimeZone('Pacific/Kiritimati'); // UTC+14: a midday instant there is the next day
    expect(formatDay('2026-10-18', 'en')).toBe('Oct 18');
    expect(formatDay('2026-10-18', 'fr')).toBe('18 oct.');
  });

  it('finds a wall-clock time in a zone, across daylight saving', () => {
    expect(zonedInstant('2026-07-01', '19:00', 'America/Halifax')).toBe('2026-07-01T22:00:00.000Z');
    expect(zonedInstant('2026-12-01', '19:00', 'America/Halifax')).toBe('2026-12-01T23:00:00.000Z');
    expect(zonedInstant('2026-10-18', '07:00', 'UTC')).toBe('2026-10-18T07:00:00.000Z');
  });

  it('shows daily hours kept in a zone with the zone\'s short name', () => {
    const summer = new Date('2026-07-01T12:00:00Z');
    expect(formatZonedHours('07:00', '23:00', 'America/Halifax', 'en', summer)).toBe('7:00 a.m. – 11:00 p.m. ADT');
    expect(formatZonedHours('07:00', '23:00', 'UTC', 'fr', summer)).toMatch(/^7 h 00 à 23 h 00 UTC$/);
  });
});

describe('full stops after values', () => {
  const dict = { en: { held: 'Held for you until {time}.', both: 'From {a}. To {b}.' }, fr: { held: 'Retenue jusqu’à {time}.', both: 'De {a}. À {b}.' } };

  it('drops the sentence\'s full stop after a value that ends with one (en-CA "p.m.")', () => {
    expect(messagesFor(dict, 'en')('held', { time: '11:27 p.m.' })).toBe('Held for you until 11:27 p.m.');
    expect(messagesFor(dict, 'en')('held', { time: '23:27' })).toBe('Held for you until 23:27.');
    expect(messagesFor(dict, 'en')('both', { a: '9 a.m.', b: 'noon' })).toBe('From 9 a.m. To noon.');
    expect(messagesFor(dict, 'fr')('held', { time: '23 h 27' })).toBe('Retenue jusqu’à 23 h 27.');
  });
});

describe('unitPriceOf (E-15: the unit price a pack states)', () => {
  it('reads grams, kilos, pounds, litres and packs from the name', () => {
    expect(unitPriceOf(850, 'Country sourdough, 900 g')).toEqual({ cents: 94, per: '100g' });
    expect(unitPriceOf(749, 'Ambrosia apples, 2 lb')).toEqual({ cents: 826, per: 'kg' });
    expect(unitPriceOf(599, 'Olive oil 1,5 L')).toEqual({ cents: 399, per: 'l' });
    expect(unitPriceOf(1200, 'Butter croissants, 4-pack')).toEqual({ cents: 300, per: 'each' });
    expect(unitPriceOf(650, 'Free-run eggs, dozen')).toEqual({ cents: 54, per: 'each' });
    expect(unitPriceOf(1200, 'Butter croissants ×4')).toEqual({ cents: 300, per: 'each' });
  });
  it('says nothing without a size, and tries the next text', () => {
    expect(unitPriceOf(850, 'Sourdough loaf')).toBeNull();
    expect(unitPriceOf(850, '4 grain bread', '1 large loaf')).toBeNull();
    expect(unitPriceOf(850, null, '500 g')).toEqual({ cents: 170, per: '100g' });
  });
});
