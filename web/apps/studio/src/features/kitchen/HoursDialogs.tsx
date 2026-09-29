import { useState } from 'react';
import { Alert, Checkbox, Chip, Dialog, Field, Select, Switch, TextInput, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { attentionCount } from '../../lib/forms';
import { today } from '../../lib/time';
import { useAddHoliday, useRemoveHoliday, useSaveFulfilment, useSaveHours, useSaveSchedule, type MenuSchedule, type Setup } from './api';
import { useKitchenT } from './messages';
import { rangeErrors, rangeText } from './model';

const serverErrors = (e: unknown) => (e instanceof ValidationError ? e.byField() : {});

/** Weekly opening hours: open/closed per day, one or more ranges, a note. */
export function HoursDialog({ merchantId, hours, onClose }: { merchantId: string; hours: Setup['hours']; onClose: () => void }) {
  const t = useKitchenT();
  const save = useSaveHours(merchantId);
  const [days, setDays] = useState(hours.map(h => ({ weekday: h.weekday, ranges: h.ranges.map(r => [...r]), note: h.note ?? '' })));
  const [tried, setTried] = useState(false);
  const local = days.map(d => rangeErrors(d.ranges, t));
  const server = serverErrors(save.error);
  const errFor = (i: number, j: number) => (tried ? local[i]?.[j] : undefined) ?? server[`days[${i}].ranges[${j}].from`] ?? server[`days[${i}].ranges[${j}].to`];
  const all: Record<string, string | undefined> = {};
  days.forEach((d, i) => d.ranges.forEach((_, j) => { all[`${i}.${j}`] = errFor(i, j); }));
  const n = attentionCount(all);
  const set = (i: number, patch: Partial<(typeof days)[number]>) => setDays(list => list.map((d, k) => (k === i ? { ...d, ...patch } : d)));
  const submit = () => {
    setTried(true);
    if (local.some(e => Object.keys(e).length)) return;
    save.mutate(days.map(d => ({ weekday: d.weekday, ranges: d.ranges, note: d.note.trim() || null })), { onSuccess: onClose });
  };
  return (
    <Dialog open onClose={onClose} width={640} title={t('hoursDialog')} actions={<><button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={save.isPending} onClick={submit}>{save.isPending ? t('saving') : t('save')}</button></>}>
      <div className="nl-k-editor-form">
        {tried && n > 0 ? <Alert tone="error" role="alert">{t('attention', { n })}</Alert> : null}
        {days.map((d, i) => {
          const dayName = t(`dayLong_${d.weekday}` as 'dayLong_1');
          return (
            <fieldset key={d.weekday} className="nl-k-fieldset nl-k-day">
              <legend className="nl-label">{dayName}</legend>
              <Switch checked={d.ranges.length > 0} label={t('h_open', { day: dayName })} onChange={on => set(i, { ranges: on ? [['11:00', '21:00']] : [] })} />
              {d.ranges.map((r, j) => (
                <div key={j} className="nl-k-range">
                  <Field label={t('h_from')} error={errFor(i, j)}><TextInput type="time" value={r[0]} onChange={e => set(i, { ranges: d.ranges.map((x, k) => (k === j ? [e.target.value, x[1] ?? ''] : x)) })} /></Field>
                  <Field label={t('h_to')}><TextInput type="time" value={r[1]} onChange={e => set(i, { ranges: d.ranges.map((x, k) => (k === j ? [x[0] ?? '', e.target.value] : x)) })} /></Field>
                  {d.ranges.length > 1 ? <button type="button" className="btn btn-ghost nl-k-x" aria-label={t('h_removeRange')} onClick={() => set(i, { ranges: d.ranges.filter((_, k) => k !== j) })}>×</button> : null}
                </div>
              ))}
              {d.ranges.length ? (
                <>
                  <button type="button" className="btn btn-ghost nl-k-small-btn" onClick={() => set(i, { ranges: [...d.ranges, ['17:00', '21:00']] })}>{t('h_addRange')}</button>
                  <Field label={t('h_note')}><TextInput value={d.note} maxLength={80} placeholder={t('h_notePh')} onChange={e => set(i, { note: e.target.value })} /></Field>
                </>
              ) : null}
            </fieldset>
          );
        })}
        {save.isError && !(save.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      </div>
    </Dialog>
  );
}

/** Holiday hours: a date closed all day, or with its own hours; upcoming ones can be removed. */
export function HolidayDialog({ merchantId, holidays, onClose }: { merchantId: string; holidays: Setup['holidays']; onClose: () => void }) {
  const t = useKitchenT();
  const { locale } = useLocale();
  const add = useAddHoliday(merchantId);
  const remove = useRemoveHoliday(merchantId);
  const [day, setDay] = useState('');
  const [closedAll, setClosedAll] = useState(true);
  const [from, setFrom] = useState('12:00');
  const [to, setTo] = useState('18:00');
  const [note, setNote] = useState('');
  const [tried, setTried] = useState(false);
  const errors = {
    day: !day || day < today() ? t('v_futureDate') : undefined,
    hours: closedAll ? undefined : rangeErrors([[from, to]], t)[0],
  };
  const server = serverErrors(add.error);
  const submit = () => {
    setTried(true);
    if (errors.day || errors.hours) return;
    add.mutate({ day, ranges: closedAll ? [] : [[from, to]], note: note.trim() || null }, { onSuccess: () => { setDay(''); setNote(''); setTried(false); } });
  };
  const fmt = (d: string) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${d}T12:00:00Z`));
  return (
    <Dialog open onClose={onClose} width={560} title={t('holidayDialog')} actions={<button type="button" className="btn btn-ghost" onClick={onClose}>{t('close')}</button>}>
      <div className="nl-k-editor-form">
        <h3 className="nl-label">{t('hol_upcoming')}</h3>
        {holidays.length === 0 ? <p className="nl-k-muted">{t('hol_none')}</p> : (
          <ul className="nl-k-list">
            {holidays.map(h => (
              <li key={h.id} className="nl-k-row">
                <span>{fmt(h.day)} · {h.ranges.length ? h.ranges.map(r => rangeText(r, locale)).join(', ') : t('hol_closedTag')}{h.note ? ` · ${h.note}` : ''}</span>
                <button type="button" className="btn btn-ghost nl-k-x" aria-label={t('hol_remove', { date: fmt(h.day) })} disabled={remove.isPending} onClick={() => remove.mutate(h.id)}>×</button>
              </li>
            ))}
          </ul>
        )}
        <Field label={t('hol_day')} error={(tried ? errors.day : undefined) ?? server.day}><TextInput type="date" min={today()} value={day} onChange={e => setDay(e.target.value)} /></Field>
        <Checkbox checked={closedAll} onChange={setClosedAll} label={t('hol_closed')} />
        {!closedAll ? (
          <div className="nl-k-pair">
            <Field label={t('h_from')} error={(tried ? errors.hours : undefined) ?? server['ranges[0].from'] ?? server['ranges[0].to']}><TextInput type="time" value={from} onChange={e => setFrom(e.target.value)} /></Field>
            <Field label={t('h_to')}><TextInput type="time" value={to} onChange={e => setTo(e.target.value)} /></Field>
          </div>
        ) : null}
        <Field label={t('h_note')}><TextInput value={note} maxLength={80} onChange={e => setNote(e.target.value)} /></Field>
        {(add.isError && !(add.error instanceof ValidationError)) || remove.isError ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
        <button type="button" className="btn btn-primary nl-k-self-start" disabled={add.isPending} onClick={submit}>{t('hol_add')}</button>
      </div>
    </Dialog>
  );
}

/** A menu's schedule: all open hours, days + window, or catering with notice. */
export function ScheduleDialog({ merchantId, menu, onClose }: { merchantId: string; menu: Setup['menus'][number]; onClose: () => void }) {
  const t = useKitchenT();
  const save = useSaveSchedule(merchantId);
  const [mode, setMode] = useState<MenuSchedule['mode']>(menu.schedule.mode);
  const [days, setDays] = useState<number[]>(menu.schedule.days.length ? menu.schedule.days : [1, 2, 3, 4, 5]);
  const [from, setFrom] = useState(menu.schedule.from ?? '11:00');
  const [to, setTo] = useState(menu.schedule.to ?? '14:00');
  const [notice, setNotice] = useState(String(menu.schedule.noticeHours ?? 48));
  const [tried, setTried] = useState(false);
  const nn = Number(notice);
  const errors = {
    days: mode === 'window' && days.length === 0 ? t('v_days') : undefined,
    hours: mode === 'window' ? rangeErrors([[from, to]], t)[0] : undefined,
    notice: mode === 'quote' && (!Number.isInteger(nn) || nn < 1 || nn > 336) ? t('v_notice') : undefined,
  };
  const server = serverErrors(save.error);
  const submit = () => {
    setTried(true);
    if (errors.days || errors.hours || errors.notice) return;
    save.mutate({ menuId: menu.menuId, schedule: { mode, days: mode === 'window' ? days : [], from: mode === 'window' ? from : null, to: mode === 'window' ? to : null, noticeHours: mode === 'quote' ? nn : null } }, { onSuccess: onClose });
  };
  return (
    <Dialog open onClose={onClose} title={t('scheduleDialog', { name: menu.name })} actions={<><button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={save.isPending} onClick={submit}>{t('save')}</button></>}>
      <div className="nl-k-editor-form">
        <Field label={t('c_when')}><Select value={mode} onChange={e => setMode(e.target.value as MenuSchedule['mode'])} options={[{ value: 'open_hours', label: t('sm_open') }, { value: 'window', label: t('sm_window') }, { value: 'quote', label: t('sm_quote') }]} /></Field>
        {mode === 'window' ? (
          <>
            <div className="nl-field" role="group" aria-label={t('c_days')}>
              <span className="nl-label">{t('c_days')}</span>
              <div className="nl-chips">{[1, 2, 3, 4, 5, 6, 7].map(d => <Chip key={d} selected={days.includes(d)} onClick={() => setDays(x => (x.includes(d) ? x.filter(v => v !== d) : [...x, d].sort()))}>{t(`day_${d}` as 'day_1')}</Chip>)}</div>
              {(tried ? errors.days : undefined) ?? server.days ? <div role="alert" className="nl-error">{(tried ? errors.days : undefined) ?? server.days}</div> : null}
            </div>
            <div className="nl-k-pair">
              <Field label={t('c_from')} error={(tried ? errors.hours : undefined) ?? server['hours[0].from'] ?? server['hours[0].to']}><TextInput type="time" value={from} onChange={e => setFrom(e.target.value)} /></Field>
              <Field label={t('c_to')}><TextInput type="time" value={to} onChange={e => setTo(e.target.value)} /></Field>
            </div>
          </>
        ) : null}
        {mode === 'quote' ? <Field label={t('sm_notice')} error={(tried ? errors.notice : undefined) ?? server.noticeHours}><TextInput inputMode="numeric" value={notice} onChange={e => setNotice(e.target.value)} /></Field> : null}
        {save.isError && !(save.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      </div>
    </Dialog>
  );
}

/** Fulfilment modes, radius and areas, group and scheduled orders. */
export function FulfilmentDialog({ merchantId, fulfilment: f, onClose }: { merchantId: string; fulfilment: Setup['fulfilment']; onClose: () => void }) {
  const t = useKitchenT();
  const save = useSaveFulfilment(merchantId);
  const [v, setV] = useState({ courier: f.courier, pickup: f.pickup, mealKits: f.mealKits, scheduled: f.scheduled, groupOrders: f.groupOrders });
  const [scheduledDays, setScheduledDays] = useState(String(f.scheduledDays));
  const [groupMax, setGroupMax] = useState(String(f.groupMax));
  const [radius, setRadius] = useState(String(f.radiusKm ?? 6));
  const [areas, setAreas] = useState(f.areas.join(', '));
  const [tried, setTried] = useState(false);
  const sd = Number(scheduledDays), gm = Number(groupMax), rk = Number(radius.replace(',', '.'));
  const errors: Record<string, string | undefined> = {
    scheduledDays: !Number.isInteger(sd) || sd < 1 || sd > 14 ? t('v_scheduledDays') : undefined,
    groupMax: !Number.isInteger(gm) || gm < 2 || gm > 50 ? t('v_groupMax') : undefined,
    radiusKm: !Number.isFinite(rk) || rk < 1 || rk > 25 ? t('v_radius') : undefined,
  };
  const server = serverErrors(save.error);
  const err = (k: string) => (tried ? errors[k] : undefined) ?? server[k];
  const n = attentionCount({ a: err('scheduledDays'), b: err('groupMax'), c: err('radiusKm') });
  const submit = () => {
    setTried(true);
    if (Object.values(errors).some(Boolean)) return;
    save.mutate({ ...v, scheduledDays: sd, groupMax: gm, radiusKm: rk, areas: areas.split(',').map(a => a.trim()).filter(Boolean) }, { onSuccess: onClose });
  };
  const sw = (k: keyof typeof v, label: string) => <Switch checked={v[k]} label={label} onChange={on => setV(x => ({ ...x, [k]: on }))} />;
  return (
    <Dialog open onClose={onClose} width={560} title={t('fulfilDialog')} actions={<><button type="button" className="btn btn-ghost" onClick={onClose}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={save.isPending} onClick={submit}>{t('save')}</button></>}>
      <div className="nl-k-editor-form">
        {tried && n > 0 ? <Alert tone="error" role="alert">{t('attention', { n })}</Alert> : null}
        {sw('courier', t('fu_courier'))}
        {sw('pickup', t('fu_pickup'))}
        {sw('mealKits', t('fu_mealKits'))}
        {sw('scheduled', t('fu_scheduled'))}
        {v.scheduled ? <Field label={t('fd_scheduledDays')} error={err('scheduledDays')}><TextInput inputMode="numeric" value={scheduledDays} onChange={e => setScheduledDays(e.target.value)} /></Field> : null}
        {sw('groupOrders', t('fu_group'))}
        {v.groupOrders ? <Field label={t('fd_groupMax')} error={err('groupMax')}><TextInput inputMode="numeric" value={groupMax} onChange={e => setGroupMax(e.target.value)} /></Field> : null}
        <Field label={t('fd_radius')} error={err('radiusKm')}><TextInput inputMode="decimal" value={radius} onChange={e => setRadius(e.target.value)} /></Field>
        <Field label={t('fd_areas')}><TextInput value={areas} onChange={e => setAreas(e.target.value)} /></Field>
        {save.isError && !(save.error instanceof ValidationError) ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      </div>
    </Dialog>
  );
}

