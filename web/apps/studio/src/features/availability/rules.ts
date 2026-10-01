import { z } from 'zod';
import { toMinutes } from '../../lib/time';
import type { FrenchMessages } from '../../lib/validation';
import { DAYS, type Days } from './api';

/** Same messages as the server (WeeklyHours / TimeOff, docs/DECISIONS.md "Operations"). */
export const HOURS_MESSAGES = {
  endBeforeStart: 'End time must be after start time.',
  overlap: 'These hours overlap another range on the same day.',
  effectiveInPast: 'Pick today or a later date.',
} as const;

export const TIME_OFF_MESSAGES = {
  fromRequired: 'Pick the first day.',
  toBeforeFrom: "The last day can't be before the first day.",
  specialRequired: "Add the hours you're open.",
  reasonTooLong: 'At most 120 characters.',
} as const;

/** fr-CA of the hours and time-off messages — the api's French (docs/spec/validation-messages.fr-CA.tsv). */
export const AVAILABILITY_MESSAGES_FR: FrenchMessages = {
  [HOURS_MESSAGES.endBeforeStart]: 'L’heure de fin doit suivre l’heure de début.',
  [HOURS_MESSAGES.overlap]: 'Ces heures chevauchent une autre plage le même jour.',
  [HOURS_MESSAGES.effectiveInPast]: 'Choisissez aujourd’hui ou une date ultérieure.',
  [TIME_OFF_MESSAGES.fromRequired]: 'Choisissez le premier jour.',
  [TIME_OFF_MESSAGES.toBeforeFrom]: 'Le dernier jour ne peut pas précéder le premier.',
  [TIME_OFF_MESSAGES.specialRequired]: 'Ajoutez vos heures d’ouverture.',
  [TIME_OFF_MESSAGES.reasonTooLong]: 'Au plus 120 caractères.',
};

/** Field key "mon[1]" → message, for every broken range (same order as the server). */
export function validateDays(days: Days): Record<string, string> {
  const out: Record<string, string> = {};
  for (const d of DAYS) {
    const ranges = days[d];
    ranges.forEach((r, i) => {
      const [s, e] = [toMinutes(r[0]), toMinutes(r[1])];
      if (e <= s) { out[`${d}[${i}]`] = HOURS_MESSAGES.endBeforeStart; return; }
      for (let j = 0; j < i; j++) {
        const o = ranges[j]!;
        if (s < toMinutes(o[1]) && toMinutes(o[0]) < e) { out[`${d}[${i}]`] = HOURS_MESSAGES.overlap; break; }
      }
    });
  }
  return out;
}

export const timeOffSchema = z.object({
  startsOn: z.string().min(1, TIME_OFF_MESSAGES.fromRequired),
  endsOn: z.string(),
  kind: z.enum(['closed', 'special']),
  specialFrom: z.string(),
  specialTo: z.string(),
  reason: z.string().max(120, TIME_OFF_MESSAGES.reasonTooLong),
}).superRefine((v, ctx) => {
  if (v.startsOn && v.endsOn && v.endsOn < v.startsOn) ctx.addIssue({ code: 'custom', path: ['endsOn'], message: TIME_OFF_MESSAGES.toBeforeFrom });
  if (v.kind === 'special' && (!v.specialFrom || !v.specialTo)) ctx.addIssue({ code: 'custom', path: ['specialRanges'], message: TIME_OFF_MESSAGES.specialRequired });
  else if (v.kind === 'special' && toMinutes(v.specialTo) <= toMinutes(v.specialFrom)) ctx.addIssue({ code: 'custom', path: ['specialRanges'], message: HOURS_MESSAGES.endBeforeStart });
});
export type TimeOffForm = z.infer<typeof timeOffSchema>;

/** Start times in one range for a job of `duration` at `interval` (design: "N slots"). */
export const slotCount = (range: [string, string], duration: number, interval: number) => {
  const span = toMinutes(range[1]) - toMinutes(range[0]) - duration;
  return span < 0 ? 0 : Math.floor(span / interval) + 1;
};

/** 6:00 am … 9:30 pm every 30 min (design timeOpts). */
export const TIME_OPTIONS: readonly string[] = Array.from({ length: 32 }, (_, i) => `${String(6 + Math.floor(i / 2)).padStart(2, '0')}:${i % 2 ? '30' : '00'}`);
