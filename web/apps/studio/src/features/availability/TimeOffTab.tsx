import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, ErrorState, Field, FormGrid, PageSkeleton, Select, TextInput, formatMoney, useLocale, type Locale } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { ValidationError } from '../../lib/http';
import { hhmmLabel, today } from '../../lib/time';
import { conflictsQuery, hoursQuery, timeOffQuery, useAddTimeOff, useRemoveTimeOff, useSetHoliday, type TimeOffEntry } from './api';
import { useAvailabilityT } from './messages';
import { TIME_OPTIONS, timeOffSchema, type TimeOffForm } from './rules';

type T = ReturnType<typeof useAvailabilityT>;
const EMPTY: TimeOffForm = { startsOn: '', endsOn: '', kind: 'closed', specialFrom: '08:00', specialTo: '12:00', reason: '' };

const md = (date: string, locale: Locale) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${date}T12:00:00Z`));
export const whenText = (e: Pick<TimeOffEntry, 'startsOn' | 'endsOn'>, locale: Locale) => (e.startsOn === e.endsOn ? md(e.startsOn, locale) : `${md(e.startsOn, locale)} – ${md(e.endsOn, locale)}`);
const rangeText = (r: [string, string], locale: Locale) => `${hhmmLabel(r[0], locale).replace(':00', '')} – ${hhmmLabel(r[1], locale).replace(':00', '')}`;

export function TimeOffTab() {
  const t = useAvailabilityT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const role = useRole();
  const canEdit = role !== 'bookkeeper';
  const q = useQuery(timeOffQuery(merchantId));
  const members = useQuery(hoursQuery(merchantId)).data?.members ?? [];
  const add = useAddTimeOff(merchantId);
  const remove = useRemoveTimeOff(merchantId);
  const holiday = useSetHoliday(merchantId);
  const [form, setForm] = useState<TimeOffForm>(EMPTY);
  const [who, setWho] = useState('');
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [tried, setTried] = useState(false);
  const conflicts = useQuery(conflictsQuery(merchantId, form.startsOn, form.endsOn || form.startsOn, who || undefined));

  const parsed = timeOffSchema.safeParse(form);
  const errors: Record<string, string> = {};
  if (!parsed.success) for (const i of parsed.error.issues) errors[String(i.path[0])] ??= i.message;
  const server = add.error instanceof ValidationError ? add.error.byField() : {};
  const shown = (k: string) => (tried || touched[k] ? errors[k] ?? server[k] : server[k]);
  const set = (patch: Partial<TimeOffForm>, field?: string) => { setForm(f => ({ ...f, ...patch })); if (field) setTouched(x => ({ ...x, [field]: true })); };

  const submit = () => {
    setTried(true);
    if (!parsed.success) return;
    add.mutate({ startsOn: form.startsOn, endsOn: form.endsOn || undefined, kind: form.kind, memberUserId: who || undefined, reason: form.reason || undefined, specialRanges: form.kind === 'special' ? [[form.specialFrom, form.specialTo]] : undefined }, {
      onSuccess: () => { setForm(EMPTY); setWho(''); setTried(false); setTouched({}); },
    });
  };

  if (q.isPending) return <PageSkeleton kpis={0} rows={5} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const premium = q.data.holidayPremiumCents;
  const conflictText = !form.startsOn ? t('paidNever') : conflicts.data ? (conflicts.data.bookings > 0 ? t('conflicts', { n: conflicts.data.bookings }) : t('noConflicts')) : '';

  return (
    <div className="nl-av-rules">
      <div>
        <h3 className="nl-av-h3">{t('timeOffTitle')}</h3>
        {q.data.entries.length === 0 ? <p className="nl-muted nl-small">{t('noTimeOff')}</p> : (
          <ul className="nl-av-list">
            {q.data.entries.map(e => (
              <li key={e.id} className="nl-av-item">
                <span><strong>{whenText(e, locale)}</strong> · {e.memberName ?? t('wholeTeam')}<span className="nl-av-sub">{e.reason ?? '—'}</span></span>
                <span className="nl-av-itemactions">
                  <span className={`tag ${e.kind === 'closed' ? 'tag-neutral' : 'tag-accent-2'}`}>{e.kind === 'closed' ? t('closed') : t('specialHours', { range: e.specialRanges.map(r => rangeText(r, locale)).join(', ') })}</span>
                  {canEdit ? <button type="button" className="btn btn-ghost" aria-label={t('removeEntry', { when: whenText(e, locale) })} onClick={() => remove.mutate(e.id)}>{t('remove')}</button> : null}
                </span>
              </li>
            ))}
          </ul>
        )}
        {canEdit ? (
          <div className="nl-av-addbox">
            <div className="nl-av-label">{t('add')}</div>
            <FormGrid min={130}>
              <Field label={t('fromDate')} error={shown('startsOn')}><TextInput type="date" min={today()} value={form.startsOn} onChange={e => set({ startsOn: e.target.value }, 'startsOn')} /></Field>
              <Field label={t('toDate')} error={shown('endsOn')}><TextInput type="date" min={form.startsOn || today()} value={form.endsOn} onChange={e => set({ endsOn: e.target.value }, 'endsOn')} /></Field>
              <Field label={t('who')}><Select value={who} onChange={e => setWho(e.target.value)} options={[{ value: '', label: t('wholeTeam') }, ...members.map(m => ({ value: m.userId, label: m.name }))]} /></Field>
              <Field label={t('type')}><Select value={form.kind} onChange={e => set({ kind: e.target.value as TimeOffForm['kind'] })} options={[{ value: 'closed', label: t('closed') }, { value: 'special', label: t('special') }]} /></Field>
              {form.kind === 'special' ? <>
                <Field label={t('openFrom')} error={shown('specialRanges')}><Select value={form.specialFrom} onChange={e => set({ specialFrom: e.target.value }, 'specialRanges')} options={TIME_OPTIONS.map(o => ({ value: o, label: hhmmLabel(o, locale) }))} /></Field>
                <Field label={t('openUntil')}><Select value={form.specialTo} onChange={e => set({ specialTo: e.target.value }, 'specialRanges')} options={TIME_OPTIONS.map(o => ({ value: o, label: hhmmLabel(o, locale) }))} /></Field>
              </> : null}
            </FormGrid>
            <Field label={t('reason')} error={shown('reason')} className="nl-av-reason"><TextInput placeholder={t('reasonPlaceholder')} value={form.reason} onChange={e => set({ reason: e.target.value }, 'reason')} /></Field>
            <div className="nl-av-addrow">
              <button type="button" className="btn btn-primary" disabled={!form.startsOn || add.isPending} onClick={submit}>{t('add')}</button>
              <span className="nl-small nl-muted" aria-live="polite">{conflictText}</span>
            </div>
            {add.isError && !(add.error instanceof ValidationError) ? <Alert tone="error">{t('saveError')}</Alert> : null}
          </div>
        ) : null}
      </div>
      <div>
        <h3 className="nl-av-h3">{q.data.province?.[locale] ? t('holidaysTitle', { province: q.data.province[locale] }) : t('holidaysTitle')}</h3>
        <ul className="nl-av-list">
          {q.data.holidays.map(h => (
            <li key={h.date} className="nl-av-item nl-av-holiday">
              <span>{h.name?.[locale] ?? t(`h_${h.key}` as Parameters<T>[0])} · {md(h.date, locale)}</span>
              <button type="button" className="nl-chip nl-av-smallchip" aria-pressed={h.open} disabled={!canEdit} onClick={() => holiday.mutate({ date: h.date, open: !h.open })}>
                {h.open ? (premium > 0 ? t('holidayOpen', { money: formatMoney(premium, locale, { whole: true }) }) : t('holidayOpenNoPremium')) : t('holidayClosed')}
              </button>
            </li>
          ))}
        </ul>
        <p className="nl-small nl-muted">{t('holidayNote')}</p>
      </div>
    </div>
  );
}
