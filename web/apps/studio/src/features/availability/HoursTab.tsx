import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Chip, ErrorState, Select, Skeleton, Switch, TextInput, useLocale } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { useSession } from '../../lib/session';
import { ValidationError } from '../../lib/http';
import { addDays, hhmmLabel, isoWeekday, today } from '../../lib/time';
import { DAYS, hoursQuery, previewQuery, rulesQuery, servicesQuery, useSaveHours, type Day, type Days, type Range } from './api';
import type { SaveState } from './AvailabilityScreen';
import { useAvailabilityT } from './messages';
import { TIME_OPTIONS, slotCount, validateDays } from './rules';

type Apply = 'today' | 'monday' | 'date';

export function HoursTab({ onState }: { onState: (s: SaveState) => void }) {
  const t = useAvailabilityT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const role = useRole();
  const canEdit = role !== 'bookkeeper';
  const me = useSession().data?.user.id;
  const hours = useQuery(hoursQuery(merchantId));
  const rules = useQuery(rulesQuery(merchantId));
  const services = useQuery(servicesQuery(merchantId));
  const save = useSaveHours(merchantId);

  const saved = useMemo(() => Object.fromEntries((hours.data?.members ?? []).map(m => [m.userId, m.days])) as Record<string, Days>, [hours.data]);
  const [drafts, setDrafts] = useState<Record<string, Days>>({});
  const [member, setMember] = useState<string | null>(null);
  const [apply, setApply] = useState<Apply>('today');
  const [applyDate, setApplyDate] = useState('');
  const [tried, setTried] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [svcId, setSvcId] = useState<string | undefined>(undefined);
  const [previewDay, setPreviewDay] = useState<Day>(DAYS[isoWeekday(today()) - 1]!);

  useEffect(() => { if (hours.data) setDrafts(saved); }, [hours.data, saved]);
  const members = hours.data?.members ?? [];
  const current = member ?? (members.find(m => m.userId === me)?.userId ?? members[0]?.userId ?? null);
  const days: Days | undefined = current ? drafts[current] ?? saved[current] : undefined;
  const dirtyIds = members.map(m => m.userId).filter(id => JSON.stringify(drafts[id]) !== JSON.stringify(saved[id]));
  const dirty = dirtyIds.length > 0;
  useEffect(() => { onState(dirty ? 'dirty' : save.isSuccess ? 'saved' : 'clean'); }, [dirty, save.isSuccess, onState]);

  const interval = rules.data?.intervalMin ?? 30;
  const buffer = rules.data?.bufferMin ?? 20;
  const serviceList = services.data ?? [];
  const selected = serviceList.find(x => x.id === svcId) ?? serviceList[0];
  const duration = selected?.durationMin ?? 45;

  const errorsFor = (id: string) => validateDays(drafts[id] ?? saved[id] ?? emptyDays());
  const clientErrors = current && days ? errorsFor(current) : {};
  const allErrorCount = dirtyIds.reduce((n, id) => n + Object.keys(errorsFor(id)).length, 0);
  const shownError = (k: string) => (tried ? clientErrors[k] ?? serverErrors[`${current}:${k}`] : undefined);

  const setDays = (next: Days) => { if (current) { setDrafts(d => ({ ...d, [current]: next })); setServerErrors({}); } };
  const setRange = (day: Day, i: number, which: 0 | 1, v: string) => days && setDays({ ...days, [day]: days[day].map((r, j) => (j === i ? (which === 0 ? [v, r[1]] : [r[0], v]) as Range : r)) });

  const effectiveFrom = apply === 'today' ? today() : apply === 'monday' ? addDays(today(), 8 - isoWeekday(today())) : applyDate;
  const onSave = () => {
    setTried(true);
    if (allErrorCount > 0 || !effectiveFrom) return;
    save.mutate({ effectiveFrom, members: dirtyIds.map(id => ({ memberUserId: id, days: drafts[id]! })) }, {
      onSuccess: () => { setTried(false); setServerErrors({}); },
      onError: e => {
        if (!(e instanceof ValidationError)) return;
        const map: Record<string, string> = {};
        e.errors.forEach(er => { const m = /^members\[(\d+)\]\.days\.(\w+\[\d+\])$/.exec(er.field); if (m) map[`${dirtyIds[Number(m[1])]}:${m[2]}`] = er.message; else map[`_:${er.field}`] = er.message; });
        setServerErrors(map);
      },
    });
  };

  const previewDate = (() => { const d0 = today(); const diff = (DAYS.indexOf(previewDay) + 1 - isoWeekday(d0) + 7) % 7; return addDays(d0, diff); })();
  const preview = useQuery({ ...previewQuery(merchantId, { memberUserId: current ?? '', date: previewDate, durationMin: duration, ranges: days?.[previewDay] ?? [], intervalMin: interval, bufferMin: buffer }), enabled: !!current && !!days });

  if (hours.isPending) return <div aria-busy="true">{Array.from({ length: 7 }, (_, i) => <Skeleton key={i} height={44} style={{ marginBottom: 8 }} />)}</div>;
  if (hours.isError) return <ErrorState message={t('loadError')} onRetry={() => void hours.refetch()} />;
  const name = members.find(m => m.userId === current)?.name ?? '';
  const summaryCount = allErrorCount + Object.keys(serverErrors).length;

  return (
    <div className="nl-av-grid">
      <div>
        <div className="nl-av-members">
          <span className="nl-av-label">{t('hoursFor')}</span>
          {members.map(m => <Chip key={m.userId} selected={m.userId === current} onClick={() => setMember(m.userId)}>{m.name}</Chip>)}
          {canEdit && members.length > 1 ? <button type="button" className="btn btn-ghost nl-av-copy" onClick={() => { if (!current || !days) return; setDrafts(() => Object.fromEntries(members.map(m => [m.userId, m.userId === current ? days : structuredClone(days)]))); }}>{t('copyHours')}</button> : null}
        </div>
        {days ? DAYS.map(day => {
          const on = days[day].length > 0;
          return (
            <div key={day} className="nl-av-day">
              <span className="nl-av-dayname">{t(`day_${day}`)}</span>
              <Switch checked={on} disabled={!canEdit} label={t('toggleDay', { day: t(`day_${day}`) })} onChange={v => setDays({ ...days, [day]: v ? [['09:00', '17:00']] : [] })} />
              <div>
                {!on ? <span className="nl-av-off">{t('notBookable')}</span> : (
                  <div className="nl-av-ranges">
                    {days[day].map((r, i) => {
                      const err = shownError(`${day}[${i}]`);
                      return (
                        <div key={i}>
                          <div className="nl-av-range">
                            <Select aria-label={`${t(`day_${day}`)} ${t('from')}`} className="nl-av-time" value={r[0]} disabled={!canEdit} aria-invalid={err ? true : undefined} options={withValue(r[0]).map(o => ({ value: o, label: hhmmLabel(o, locale) }))} onChange={e => setRange(day, i, 0, e.target.value)} />
                            <span className="nl-muted">–</span>
                            <Select aria-label={`${t(`day_${day}`)} ${t('until')}`} className="nl-av-time" value={r[1]} disabled={!canEdit} aria-invalid={err ? true : undefined} options={withValue(r[1]).map(o => ({ value: o, label: hhmmLabel(o, locale) }))} onChange={e => setRange(day, i, 1, e.target.value)} />
                            <span className="nl-small nl-muted">{t('slots', { n: slotCount(r, duration, interval) })}</span>
                            {canEdit ? <button type="button" className="nl-av-x" aria-label={t('removeRange', { from: r[0], to: r[1] })} onClick={() => setDays({ ...days, [day]: days[day].filter((_, j) => j !== i) })}>×</button> : null}
                          </div>
                          {err ? <div role="alert" className="nl-error">{err}</div> : null}
                        </div>
                      );
                    })}
                    {canEdit ? <button type="button" className="btn btn-ghost nl-av-add" onClick={() => setDays({ ...days, [day]: [...days[day], ['18:00', '20:00']] })}>{t('addRange')}</button> : null}
                  </div>
                )}
              </div>
            </div>
          );
        }) : null}
        {tried && summaryCount > 0 ? <Alert tone="error">{t('attention', { n: summaryCount })}</Alert> : null}
        {save.isError && !(save.error instanceof ValidationError) ? <Alert tone="error">{t('saveError')}</Alert> : null}
        {canEdit ? (
          <div className="nl-av-save">
            <button type="button" className="btn btn-primary" onClick={onSave} disabled={save.isPending || !dirty}>{t('saveHours')}</button>
            <Select aria-label={t('applyFrom')} className="nl-av-apply" value={apply} onChange={e => setApply(e.target.value as Apply)} options={[{ value: 'today', label: t('applyToday') }, { value: 'monday', label: t('applyMonday') }, { value: 'date', label: t('applyDate') }]} />
            {apply === 'date' ? <TextInput type="date" aria-label={t('applyDateLabel')} min={today()} value={applyDate} onChange={e => setApplyDate(e.target.value)} className="nl-av-apply" /> : null}
            <span className="nl-small nl-muted">{t('neverMoved')}</span>
          </div>
        ) : null}
      </div>

      <PreviewPanel
        t={t} name={name} day={previewDay} setDay={setPreviewDay} duration={duration} serviceId={selected?.id} setServiceId={setSvcId} services={serviceList}
        preview={preview.data} loading={preview.isPending && preview.fetchStatus !== 'idle'} error={preview.isError} retry={() => void preview.refetch()}
      />
    </div>
  );
}

const emptyDays = (): Days => Object.fromEntries(DAYS.map(d => [d, []])) as unknown as Days;
const withValue = (v: string) => (TIME_OPTIONS.includes(v) ? TIME_OPTIONS : [...TIME_OPTIONS, v].sort());

function PreviewPanel({ t, name, day, setDay, duration, serviceId, setServiceId, services, preview, loading, error, retry }: {
  t: ReturnType<typeof useAvailabilityT>; name: string; day: Day; setDay: (d: Day) => void; duration: number; serviceId?: string; setServiceId: (id: string) => void;
  services: { id: string; name: string; durationMin: number }[]; preview?: { slots: { start: string; free: boolean }[]; jobs: number; intervalMin: number; bufferMin: number; closed?: string | null };
  loading: boolean; error: boolean; retry: () => void;
}) {
  const { locale } = useLocale();
  const first = name.split(' ')[0] ?? name;
  const free = preview?.slots.filter(s => s.free).length ?? 0;
  const note = !preview ? '' : preview.closed === 'holiday' ? t('closedHoliday', { day: t(`day_${day}`) }) : preview.closed === 'time_off' ? t('closedTimeOff', { name: first, day: t(`day_${day}`) })
    : preview.slots.length === 0 ? t('notBookableOn', { name: first, day: t(`day_${day}`) })
    : t('previewNote', { free, total: preview.slots.length, min: duration, jobs: preview.jobs, buffer: preview.bufferMin, interval: preview.intervalMin });
  return (
    <section aria-labelledby="av-preview">
      <h3 id="av-preview" className="nl-av-h3">{t('previewTitle')}</h3>
      <p className="nl-small nl-muted nl-av-help">{t('previewHelp')}</p>
      <Select aria-label={t('previewService')} className="nl-av-svc" value={serviceId ?? ''} onChange={e => setServiceId(e.target.value)}
        options={services.map(s => ({ value: s.id, label: s.name ? t('serviceDuration', { name: s.name, min: s.durationMin }) : t('durationOnly', { min: s.durationMin }) }))} />
      <div className="nl-av-daychips" role="group" aria-label={t('previewDay')}>
        {DAYS.map(d => <Chip key={d} selected={d === day} onClick={() => setDay(d)}>{t(`day_${d}`)}</Chip>)}
      </div>
      {error ? <ErrorState message={t('loadError')} onRetry={retry} /> : (
        <div className="nl-av-slots" aria-busy={loading}>
          {(preview?.slots ?? []).map(s => (
            <span key={s.start} className="nl-av-slot" data-free={s.free} aria-label={t(s.free ? 'slotFree' : 'slotTaken', { time: hhmmLabel(s.start, locale) })}>{hhmmLabel(s.start, locale)}</span>
          ))}
        </div>
      )}
      <div className="nl-small nl-muted nl-av-note" aria-live="polite">{note}</div>
      <div className="nl-av-legend nl-small nl-muted"><span className="nl-av-swatch" data-free="true" />{t('legendFree')}<span className="nl-av-swatch" data-free="false" />{t('legendTaken')}</div>
    </section>
  );
}
