import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

export const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
export type Day = (typeof DAYS)[number];
export type Range = [string, string];
export type Days = Record<Day, Range[]>;

const RangeSchema = z.tuple([z.string(), z.string()]);
const DaysSchema = z.record(z.string(), z.array(RangeSchema)).transform(d => Object.fromEntries(DAYS.map(k => [k, d[k] ?? []])) as Days);
const Role = z.string();

export const MemberHours = z.object({ userId: z.string(), name: z.string(), role: Role, bookable: z.boolean(), effectiveFrom: z.string().nullish(), days: DaysSchema });
export type MemberHours = z.infer<typeof MemberHours>;
export const HoursView = z.object({ members: z.array(MemberHours), lastSavedAt: z.string().nullish() });
export type HoursView = z.infer<typeof HoursView>;

export const Preview = z.object({ slots: z.array(z.object({ start: z.string(), free: z.boolean() })), jobs: z.number(), intervalMin: z.number(), bufferMin: z.number(), closed: z.string().nullish() });
export type Preview = z.infer<typeof Preview>;
export const Service = z.object({ id: z.string(), name: z.string(), durationMin: z.number() });

export const AcceptMode = z.enum(['instant', 'approve', 'request']);
export const Rules = z.object({
  intervalMin: z.number(), bufferMin: z.number(), minNoticeMin: z.number(), sameDayCutoffMin: z.number().nullish(), horizonDays: z.number(), maxJobsPerDay: z.number(),
  acceptMode: AcceptMode, rescheduleFreeMin: z.number(), lateCancelFeeCents: z.number().nullish(), lateCancelFeeBps: z.number().nullish(),
  emergencyPremiumCents: z.number().nullish(), emergencyPremiumBps: z.number().nullish(), holidayPremiumCents: z.number(), serviceAreas: z.array(z.string()),
  zones: z.array(z.string()), lastSavedAt: z.string().nullish(),
});
export type Rules = z.infer<typeof Rules>;

export const TimeOffEntry = z.object({
  id: z.string(), memberUserId: z.string().nullish(), memberName: z.string().nullish(), startsOn: z.string(), endsOn: z.string(),
  kind: z.enum(['closed', 'special']), specialRanges: z.array(RangeSchema), reason: z.string().nullish(),
});
export type TimeOffEntry = z.infer<typeof TimeOffEntry>;
export const Holiday = z.object({ key: z.string(), date: z.string(), open: z.boolean() });
export type Holiday = z.infer<typeof Holiday>;
export const TimeOffView = z.object({ entries: z.array(TimeOffEntry), holidays: z.array(Holiday), holidayPremiumCents: z.number() });
export type TimeOffView = z.infer<typeof TimeOffView>;

export const Calendar = z.object({ provider: z.enum(['google', 'outlook', 'ical']), connected: z.boolean(), accountLabel: z.string().nullish(), lastSyncAt: z.string().nullish(), feedUrl: z.string().nullish() });
export type Calendar = z.infer<typeof Calendar>;
export const TeamMember = z.object({ userId: z.string(), name: z.string(), role: Role, bookable: z.boolean(), days: DaysSchema.nullish() });
export type TeamMember = z.infer<typeof TeamMember>;
export const SyncView = z.object({ calendars: z.array(Calendar), team: z.array(TeamMember) });
export type SyncView = z.infer<typeof SyncView>;

const base = (m: string) => `/api/v1/merchants/${m}/availability`;
const key = (m: string, ...rest: string[]) => ['merchant', m, 'availability', ...rest];

export const hoursQuery = (m: string) => queryOptions({ queryKey: key(m, 'hours'), queryFn: () => http(`${base(m)}/hours`, {}, HoursView) });
export const rulesQuery = (m: string) => queryOptions({ queryKey: key(m, 'rules'), queryFn: () => http(`${base(m)}/rules`, {}, Rules) });
export const timeOffQuery = (m: string) => queryOptions({ queryKey: key(m, 'time-off'), queryFn: () => http(`${base(m)}/time-off`, {}, TimeOffView) });
export const syncQuery = (m: string) => queryOptions({ queryKey: key(m, 'sync'), queryFn: () => http(`${base(m)}/sync`, {}, SyncView) });
export const servicesQuery = (m: string) => queryOptions({ queryKey: key(m, 'services'), queryFn: () => http(`${base(m)}/services`, {}, z.object({ items: z.array(Service) })).then(r => r.items), staleTime: 5 * 60_000 });

export interface PreviewInput { memberUserId: string; date: string; durationMin: number; ranges?: Range[]; intervalMin?: number; bufferMin?: number }
export const previewQuery = (m: string, p: PreviewInput) => queryOptions({
  queryKey: key(m, 'preview', JSON.stringify(p)),
  queryFn: () => http(`${base(m)}/preview`, { method: 'POST', body: p }, Preview),
  placeholderData: prev => prev,
});
export const conflictsQuery = (m: string, from: string, to: string, memberUserId?: string) => queryOptions({
  queryKey: key(m, 'conflicts', from, to, memberUserId ?? ''),
  queryFn: () => http(`${base(m)}/time-off/conflicts?${new URLSearchParams({ from, to: to || from, ...(memberUserId ? { memberUserId } : {}) })}`, {}, z.object({ bookings: z.number() })),
  enabled: !!from,
});

function useInvalidate(m: string) {
  const qc = useQueryClient();
  return (...parts: string[]) => Promise.all([
    ...parts.map(p => qc.invalidateQueries({ queryKey: key(m, p) })),
    qc.invalidateQueries({ queryKey: key(m, 'preview') }),
  ]);
}

export function useSaveHours(m: string) {
  const qc = useQueryClient();
  const inv = useInvalidate(m);
  return useMutation({
    mutationFn: (body: { effectiveFrom: string; members: { memberUserId: string; days: Days }[] }) => http(`${base(m)}/hours`, { method: 'PUT', body }, HoursView),
    onSuccess: data => { qc.setQueryData(hoursQuery(m).queryKey, data); void inv('sync', 'rules'); },
  });
}

export type RulesInput = Omit<Rules, 'zones' | 'lastSavedAt'>;
export function useSaveRules(m: string) {
  const qc = useQueryClient();
  const inv = useInvalidate(m);
  return useMutation({
    mutationFn: (body: RulesInput) => http(`${base(m)}/rules`, { method: 'PUT', body }, Rules),
    onSuccess: data => { qc.setQueryData(rulesQuery(m).queryKey, data); void inv('hours'); },
  });
}

export interface TimeOffInput { memberUserId?: string; startsOn: string; endsOn?: string; kind: 'closed' | 'special'; specialRanges?: Range[]; reason?: string }
export function useAddTimeOff(m: string) {
  const inv = useInvalidate(m);
  return useMutation({ mutationFn: (body: TimeOffInput) => http(`${base(m)}/time-off`, { method: 'POST', body }, TimeOffEntry), onSuccess: () => inv('time-off') });
}

export function useRemoveTimeOff(m: string) {
  const qc = useQueryClient();
  const k = timeOffQuery(m).queryKey;
  const inv = useInvalidate(m);
  return useMutation({
    mutationFn: (id: string) => http(`${base(m)}/time-off/${id}`, { method: 'DELETE' }),
    onMutate: async id => { await qc.cancelQueries({ queryKey: k }); const prev = qc.getQueryData(k); qc.setQueryData(k, v => v && { ...v, entries: v.entries.filter(e => e.id !== id) }); return { prev }; },
    onError: (_e, _id, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSettled: () => inv('time-off'),
  });
}

export function useSetHoliday(m: string) {
  const qc = useQueryClient();
  const k = timeOffQuery(m).queryKey;
  return useMutation({
    mutationFn: ({ date, open }: { date: string; open: boolean }) => http(`${base(m)}/holidays/${date}`, { method: 'PUT', body: { open } }, Holiday),
    onMutate: async ({ date, open }) => { await qc.cancelQueries({ queryKey: k }); const prev = qc.getQueryData(k); qc.setQueryData(k, v => v && { ...v, holidays: v.holidays.map(h => (h.date === date ? { ...h, open } : h)) }); return { prev }; },
    onError: (_e, _v, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSettled: () => qc.invalidateQueries({ queryKey: k }),
  });
}

export function useToggleCalendar(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ provider, connect }: { provider: Calendar['provider']; connect: boolean }) => http(`${base(m)}/calendars/${provider}`, { method: connect ? 'POST' : 'DELETE' }, Calendar),
    onSuccess: cal => qc.setQueryData(syncQuery(m).queryKey, v => v && { ...v, calendars: v.calendars.map(c => (c.provider === cal.provider ? cal : c)) }),
  });
}

export function useSetBookable(m: string) {
  const qc = useQueryClient();
  const k = syncQuery(m).queryKey;
  return useMutation({
    mutationFn: ({ userId, bookable }: { userId: string; bookable: boolean }) => http(`${base(m)}/team/${userId}`, { method: 'PUT', body: { bookable } }, TeamMember),
    onMutate: async ({ userId, bookable }) => { await qc.cancelQueries({ queryKey: k }); const prev = qc.getQueryData(k); qc.setQueryData(k, v => v && { ...v, team: v.team.map(t => (t.userId === userId ? { ...t, bookable } : t)) }); return { prev }; },
    onError: (_e, _v, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSettled: () => { void qc.invalidateQueries({ queryKey: k }); void qc.invalidateQueries({ queryKey: key(m, 'hours') }); },
  });
}
