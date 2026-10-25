import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, BoHeader, ErrorState, Field, FormSection, KpiStrip, PageSkeleton, Select, StatusTag, formatDay, useLocale, timeZone } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { setupQuery, useSavePrep, type Setup } from './api';
import { FulfilmentDialog, HolidayDialog, HoursDialog, ScheduleDialog } from './HoursDialogs';
import { useKitchenT, type KitchenT } from './messages';
import { rangeText, scheduleText } from './model';
import './Kitchen.css';

type Open = { kind: 'hours' } | { kind: 'holiday' } | { kind: 'fulfilment' } | { kind: 'schedule'; menuId: string } | null;

/** Kitchen · Hours, prep & capacity (design 02 lines 897–921, `kHours`; S-176: page head, KPI strip, FormSection panels, status tags). */
export function HoursScreen() {
  const merchantId = useMerchantId();
  const role = useRole();
  const t = useKitchenT();
  const { locale } = useLocale();
  const q = useQuery(setupQuery(merchantId));
  const prep = useSavePrep(merchantId);
  const canEdit = role !== 'bookkeeper';
  const [open, setOpen] = useState<Open>(null);

  if (q.isPending) return <div className="nl-kitchen"><BoHeader title={t('hoursKicker')} /><PageSkeleton kpis={4} rows={7} /></div>;
  if (q.isError) return <div className="nl-kitchen"><BoHeader title={t('hoursKicker')} /><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;
  const s = q.data;
  const savePrep = (patch: Partial<{ defaultPrepMin: number; maxOrdersPer15: number; largeOrderCents: number; autoPauseLate: number | null }>) =>
    prep.mutate({ defaultPrepMin: s.prep.defaultPrepMin, maxOrdersPer15: s.prep.maxOrdersPer15, largeOrderCents: s.prep.largeOrderCents, autoPauseLate: s.prep.autoPauseLate ?? null, ...patch });
  const f = s.fulfilment;
  const schedMenu = open?.kind === 'schedule' ? s.menus.find(m => m.menuId === open.menuId) : undefined;
  const openDays = s.hours.filter(h => h.ranges.length).length;
  const ways = [f.courier, f.pickup, f.mealKits].filter(Boolean).length;
  return (
    <div className="nl-kitchen">
      <BoHeader title={t('hoursKicker')} sub={t('hoursTitle')} />
      <KpiStrip label={t('hoursKpis')} items={[
        { key: 'days', label: t('kpiOpenDays'), value: t('kpiDaysValue', { n: openDays }) },
        { key: 'prep', label: t('p_default'), value: t('minutes', { n: s.prep.defaultPrepMin }) },
        { key: 'max', label: t('p_max'), value: String(s.prep.maxOrdersPer15) },
        { key: 'ways', label: t('kpiWays'), value: String(ways), delta: f.radiusKm == null ? undefined : t('radiusOnly', { km: f.radiusKm }) },
      ]} />
      <p className="nl-k-lede">{t('hoursLede')}</p>
      <div className="nl-k-two">
        <div className="nl-k-col">
          <FormSection title={t('openingTitle')} id="k-open"
            actions={canEdit ? <div className="nl-k-btnrow"><button type="button" className="btn btn-secondary btn-sm" onClick={() => setOpen({ kind: 'hours' })}>{t('editHours')}</button><button type="button" className="btn btn-secondary btn-sm" onClick={() => setOpen({ kind: 'holiday' })}>{t('holidayHours')}</button></div> : undefined}>
            <dl className="nl-k-hours">
              {s.hours.map(h => (
                <div key={h.weekday} className="nl-k-hours-row" data-closed={h.ranges.length ? undefined : ''}>
                  <dt>{t(`day_${h.weekday}` as 'day_1')}</dt>
                  <dd>{h.ranges.length ? h.ranges.map(r => rangeText(r, locale)).join(', ') : <StatusTag tone="neutral">{t('closed')}</StatusTag>}</dd>
                  <dd className="nl-k-small">{h.note ?? ''}</dd>
                </div>
              ))}
            </dl>
            {s.holidays.length ? <p className="nl-k-small nl-k-holidays">{s.holidays.map(h => `${formatDay(h.day, locale)} · ${h.ranges.length ? h.ranges.map(r => rangeText(r, locale)).join(', ') : t('hol_closedTag')}`).join(' · ')}</p> : null}
          </FormSection>
          <FormSection title={t('schedulesTitle')} id="k-sched">
            <div className="nl-k-rows">
              {s.menus.map(m => (
                <div key={m.menuId} className="nl-k-row nl-k-row-tight">
                  <span>{m.name}</span>
                  {canEdit ? <button type="button" className="nl-k-linkish" aria-label={t('editSchedule', { name: m.name })} onClick={() => setOpen({ kind: 'schedule', menuId: m.menuId })}>{scheduleText(m.schedule, t, locale)}</button> : <span>{scheduleText(m.schedule, t, locale)}</span>}
                </div>
              ))}
            </div>
          </FormSection>
        </div>
        <div className="nl-k-col">
          <FormSection title={t('prepTitle')} id="k-prep" actions={prep.isSuccess && !prep.isPending ? <StatusTag tone="success"><span role="status">{t('saved')}</span></StatusTag> : undefined}>
            {prep.isError ? <Alert tone="error" role="alert">{t('saveError')}</Alert> : null}
            <div className="nl-k-prep" aria-busy={prep.isPending}>
              <Field label={t('p_default')}><Select disabled={!canEdit} value={String(s.prep.defaultPrepMin)} onChange={e => savePrep({ defaultPrepMin: Number(e.target.value) })} options={[20, 25, 30, 40].map(n => ({ value: String(n), label: t('minutes', { n }) }))} /></Field>
              <Field label={t('p_max')}><Select disabled={!canEdit} value={String(s.prep.maxOrdersPer15)} onChange={e => savePrep({ maxOrdersPer15: Number(e.target.value) })} options={['4', '6', '8', '12']} /></Field>
              <Field label={t('p_large')}><Select disabled={!canEdit} value={String(s.prep.largeOrderCents)} onChange={e => savePrep({ largeOrderCents: Number(e.target.value) })} options={[{ value: '12000', label: t('p_large_12000') }, { value: '20000', label: t('p_large_20000') }]} /></Field>
              <Field label={t('p_auto')}><Select disabled={!canEdit} value={s.prep.autoPauseLate ? String(s.prep.autoPauseLate) : 'never'} onChange={e => savePrep({ autoPauseLate: e.target.value === 'never' ? null : Number(e.target.value) })} options={[{ value: '3', label: '3' }, { value: '5', label: '5' }, { value: 'never', label: t('p_never') }]} /></Field>
            </div>
          </FormSection>
          <FormSection title={t('fulfilTitle')} id="k-fulfil" actions={canEdit ? <button type="button" className="btn btn-secondary btn-sm" onClick={() => setOpen({ kind: 'fulfilment' })}>{t('editFulfilment')}</button> : undefined}>
            <div className="nl-k-rows">
              <Row label={t('fu_courier')}><OnOff on={f.courier} t={t} /></Row>
              <Row label={t('fu_pickup')}>{f.pickup ? <StatusTag tone="success">{t('pickupOn', { from: f.pickupFromMin, to: f.pickupToMin })}</StatusTag> : <OnOff on={false} t={t} />}</Row>
              <Row label={t('fu_mealKits')}><OnOff on={f.mealKits} t={t} /></Row>
              <Row label={t('fu_radius')}><span>{f.radiusKm == null ? '—' : f.areas.length ? t('radiusText', { km: f.radiusKm, areas: f.areas.join(', ') }) : t('radiusOnly', { km: f.radiusKm })}</span></Row>
              <Row label={t('fu_group')}>{f.groupOrders ? <StatusTag tone="success">{t('groupOn', { n: f.groupMax })}</StatusTag> : <OnOff on={false} t={t} />}</Row>
              <Row label={t('fu_scheduled')}>{f.scheduled ? <StatusTag tone="success">{t('scheduledOn', { n: f.scheduledDays })}</StatusTag> : <OnOff on={false} t={t} />}</Row>
            </div>
          </FormSection>
          <FormSection title={t('safetyTitle')} id="k-safety">
            <FoodSafety setup={s} t={t} merchantId={merchantId} />
          </FormSection>
        </div>
      </div>
      {open?.kind === 'hours' ? <HoursDialog merchantId={merchantId} hours={s.hours} onClose={() => setOpen(null)} /> : null}
      {open?.kind === 'holiday' ? <HolidayDialog merchantId={merchantId} holidays={s.holidays} onClose={() => setOpen(null)} /> : null}
      {open?.kind === 'fulfilment' ? <FulfilmentDialog merchantId={merchantId} fulfilment={f} onClose={() => setOpen(null)} /> : null}
      {schedMenu ? <ScheduleDialog merchantId={merchantId} menu={schedMenu} onClose={() => setOpen(null)} /> : null}
    </div>
  );
}

const Row = ({ label, children }: { label: string; children: React.ReactNode }) => <div className="nl-k-row"><span>{label}</span>{children}</div>;
const OnOff = ({ on, t }: { on: boolean; t: KitchenT }) => <StatusTag tone={on ? 'success' : 'neutral'}>{on ? t('on') : t('off')}</StatusTag>;

function evidenceTag(e: Setup['foodSafety']['permit'], t: KitchenT, date: (iso: string) => string, verifiedLabel: 'ev_verified' | 'ev_onFile') {
  switch (e.status) {
    case 'verified': return { tone: 'success' as const, text: e.expiresAt ? t('ev_renews', { date: date(e.expiresAt) }) : t(verifiedLabel) };
    case 'submitted': return { tone: 'warning' as const, text: t('ev_submitted') };
    case 'expired': return { tone: 'danger' as const, text: t('ev_expired') };
    case 'rejected': return { tone: 'danger' as const, text: t('ev_rejected') };
    case 'todo': return { tone: 'danger' as const, text: t('ev_todo') };
    default: return { tone: 'neutral' as const, text: t('ev_missing') };
  }
}

function FoodSafety({ setup, t, merchantId }: { setup: Setup; t: KitchenT; merchantId: string }) {
  const { locale } = useLocale();
  const monthYear = (iso: string) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', year: 'numeric', timeZone: timeZone() }).format(new Date(iso));
  const p = setup.foodSafety.permit, h = setup.foodSafety.handlers;
  const pt = evidenceTag(p, t, monthYear, 'ev_verified'), ht = evidenceTag(h, t, monthYear, 'ev_onFile');
  return (
    <div className="nl-k-rows">
      <div className="nl-k-row"><span>{p.reference ? t('permit', { ref: p.reference.replace(/^#/, '') }) : t('permitNone')}</span><StatusTag tone={pt.tone}>{pt.text}</StatusTag></div>
      <div className="nl-k-row"><span>{h.reference ? t('handlers', { ref: h.reference }) : t('handlersNone')}</span><StatusTag tone={ht.tone}>{ht.text}</StatusTag></div>
      <div className="nl-k-row"><span>{t('seals')}</span><StatusTag tone="success">{t('sealsTag')}</StatusTag></div>
      <Link className="nl-k-link" to="/b/$merchantId/compliance" params={{ merchantId }}>{t('complianceLink')}</Link>
    </div>
  );
}
