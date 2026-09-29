import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, ErrorState, Field, PageSkeleton, Select, useLocale } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { setupQuery, useSavePrep, type Setup } from './api';
import { FulfilmentDialog, HolidayDialog, HoursDialog, ScheduleDialog } from './HoursDialogs';
import { useKitchenT, type KitchenT } from './messages';
import { rangeText, scheduleText } from './model';
import './Kitchen.css';

type Open = { kind: 'hours' } | { kind: 'holiday' } | { kind: 'fulfilment' } | { kind: 'schedule'; menuId: string } | null;

/** Kitchen · Hours, prep & capacity (design 02 lines 897–921, `kHours`). */
export function HoursScreen() {
  const merchantId = useMerchantId();
  const role = useRole();
  const t = useKitchenT();
  const { locale } = useLocale();
  const q = useQuery(setupQuery(merchantId));
  const prep = useSavePrep(merchantId);
  const canEdit = role !== 'bookkeeper';
  const [open, setOpen] = useState<Open>(null);

  if (q.isPending) return <PageSkeleton kpis={0} rows={7} />;
  if (q.isError) return <><span className="nl-k-kicker">{t('hoursKicker')}</span><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></>;
  const s = q.data;
  const savePrep = (patch: Partial<{ defaultPrepMin: number; maxOrdersPer15: number; largeOrderCents: number; autoPauseLate: number | null }>) =>
    prep.mutate({ defaultPrepMin: s.prep.defaultPrepMin, maxOrdersPer15: s.prep.maxOrdersPer15, largeOrderCents: s.prep.largeOrderCents, autoPauseLate: s.prep.autoPauseLate ?? null, ...patch });
  const f = s.fulfilment;
  const schedMenu = open?.kind === 'schedule' ? s.menus.find(m => m.menuId === open.menuId) : undefined;
  return (
    <div className="nl-kitchen">
      <span className="nl-k-kicker">{t('hoursKicker')}</span>
      <h1 className="nl-k-title nl-k-title-gap">{t('hoursTitle')}</h1>
      <p className="nl-k-lede">{t('hoursLede')}</p>
      <div className="nl-k-two">
        <section aria-labelledby="k-open">
          <h2 id="k-open" className="nl-k-h2">{t('openingTitle')}</h2>
          <dl className="nl-k-hours">
            {s.hours.map(h => (
              <div key={h.weekday} className="nl-k-hours-row">
                <dt>{t(`day_${h.weekday}` as 'day_1')}</dt>
                <dd>{h.ranges.length ? h.ranges.map(r => rangeText(r, locale)).join(', ') : t('closed')}</dd>
                <dd className="nl-k-small">{h.note ?? ''}</dd>
              </div>
            ))}
          </dl>
          {canEdit ? <div className="nl-k-btnrow"><button type="button" className="btn btn-secondary" onClick={() => setOpen({ kind: 'hours' })}>{t('editHours')}</button><button type="button" className="btn btn-secondary" onClick={() => setOpen({ kind: 'holiday' })}>{t('holidayHours')}</button></div> : null}
          {s.holidays.length ? <p className="nl-k-small nl-k-holidays">{s.holidays.map(h => `${new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${h.day}T12:00:00Z`))} · ${h.ranges.length ? h.ranges.map(r => rangeText(r, locale)).join(', ') : t('hol_closedTag')}`).join(' · ')}</p> : null}
          <h2 className="nl-k-h2 nl-k-h2-gap">{t('schedulesTitle')}</h2>
          <div className="nl-k-rows">
            {s.menus.map(m => (
              <div key={m.menuId} className="nl-k-row nl-k-row-tight">
                <span>{m.name}</span>
                {canEdit ? <button type="button" className="nl-k-linkish" aria-label={t('editSchedule', { name: m.name })} onClick={() => setOpen({ kind: 'schedule', menuId: m.menuId })}>{scheduleText(m.schedule, t, locale)}</button> : <span>{scheduleText(m.schedule, t, locale)}</span>}
              </div>
            ))}
          </div>
        </section>
        <section aria-labelledby="k-prep">
          <h2 id="k-prep" className="nl-k-h2">{t('prepTitle')}</h2>
          {prep.isError ? <Alert tone="error" role="alert">{t('saveError')}</Alert> : null}
          <div className="nl-k-prep" aria-busy={prep.isPending}>
            <Field label={t('p_default')}><Select disabled={!canEdit} value={String(s.prep.defaultPrepMin)} onChange={e => savePrep({ defaultPrepMin: Number(e.target.value) })} options={[20, 25, 30, 40].map(n => ({ value: String(n), label: t('minutes', { n }) }))} /></Field>
            <Field label={t('p_max')}><Select disabled={!canEdit} value={String(s.prep.maxOrdersPer15)} onChange={e => savePrep({ maxOrdersPer15: Number(e.target.value) })} options={['4', '6', '8', '12']} /></Field>
            <Field label={t('p_large')}><Select disabled={!canEdit} value={String(s.prep.largeOrderCents)} onChange={e => savePrep({ largeOrderCents: Number(e.target.value) })} options={[{ value: '12000', label: t('p_large_12000') }, { value: '20000', label: t('p_large_20000') }]} /></Field>
            <Field label={t('p_auto')}><Select disabled={!canEdit} value={s.prep.autoPauseLate ? String(s.prep.autoPauseLate) : 'never'} onChange={e => savePrep({ autoPauseLate: e.target.value === 'never' ? null : Number(e.target.value) })} options={[{ value: '3', label: '3' }, { value: '5', label: '5' }, { value: 'never', label: t('p_never') }]} /></Field>
          </div>
          {prep.isSuccess && !prep.isPending ? <span className="nl-k-small" role="status">{t('saved')}</span> : null}
          <div className="nl-k-subhead nl-k-h2-gap"><h2 className="nl-k-h2">{t('fulfilTitle')}</h2>{canEdit ? <button type="button" className="btn btn-ghost nl-k-small-btn" onClick={() => setOpen({ kind: 'fulfilment' })}>{t('editFulfilment')}</button> : null}</div>
          <div className="nl-k-rows">
            <Row label={t('fu_courier')}><OnOff on={f.courier} t={t} /></Row>
            <Row label={t('fu_pickup')}>{f.pickup ? <span className="tag tag-accent">{t('pickupOn', { from: f.pickupFromMin, to: f.pickupToMin })}</span> : <OnOff on={false} t={t} />}</Row>
            <Row label={t('fu_mealKits')}><OnOff on={f.mealKits} t={t} /></Row>
            <Row label={t('fu_radius')}><span>{f.radiusKm == null ? '—' : f.areas.length ? t('radiusText', { km: f.radiusKm, areas: f.areas.join(', ') }) : t('radiusOnly', { km: f.radiusKm })}</span></Row>
            <Row label={t('fu_group')}>{f.groupOrders ? <span className="tag tag-accent">{t('groupOn', { n: f.groupMax })}</span> : <OnOff on={false} t={t} />}</Row>
            <Row label={t('fu_scheduled')}>{f.scheduled ? <span className="tag tag-accent">{t('scheduledOn', { n: f.scheduledDays })}</span> : <OnOff on={false} t={t} />}</Row>
          </div>
          <h2 className="nl-k-h2 nl-k-h2-gap">{t('safetyTitle')}</h2>
          <FoodSafety setup={s} t={t} merchantId={merchantId} />
        </section>
      </div>
      {open?.kind === 'hours' ? <HoursDialog merchantId={merchantId} hours={s.hours} onClose={() => setOpen(null)} /> : null}
      {open?.kind === 'holiday' ? <HolidayDialog merchantId={merchantId} holidays={s.holidays} onClose={() => setOpen(null)} /> : null}
      {open?.kind === 'fulfilment' ? <FulfilmentDialog merchantId={merchantId} fulfilment={f} onClose={() => setOpen(null)} /> : null}
      {schedMenu ? <ScheduleDialog merchantId={merchantId} menu={schedMenu} onClose={() => setOpen(null)} /> : null}
    </div>
  );
}

const Row = ({ label, children }: { label: string; children: React.ReactNode }) => <div className="nl-k-row"><span>{label}</span>{children}</div>;
const OnOff = ({ on, t }: { on: boolean; t: KitchenT }) => <span className={`tag ${on ? 'tag-accent' : 'tag-neutral'}`}>{on ? t('on') : t('off')}</span>;

function evidenceTag(e: Setup['foodSafety']['permit'], t: KitchenT, date: (iso: string) => string, verifiedLabel: 'ev_verified' | 'ev_onFile') {
  switch (e.status) {
    case 'verified': return { cls: 'tag-accent', text: e.expiresAt ? t('ev_renews', { date: date(e.expiresAt) }) : t(verifiedLabel) };
    case 'submitted': return { cls: 'tag-highlight', text: t('ev_submitted') };
    case 'expired': return { cls: 'tag-accent-2', text: t('ev_expired') };
    case 'rejected': return { cls: 'tag-accent-2', text: t('ev_rejected') };
    case 'todo': return { cls: 'tag-accent-2', text: t('ev_todo') };
    default: return { cls: 'tag-neutral', text: t('ev_missing') };
  }
}

function FoodSafety({ setup, t, merchantId }: { setup: Setup; t: KitchenT; merchantId: string }) {
  const { locale } = useLocale();
  const monthYear = (iso: string) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', year: 'numeric', timeZone: 'America/Edmonton' }).format(new Date(iso));
  const p = setup.foodSafety.permit, h = setup.foodSafety.handlers;
  const pt = evidenceTag(p, t, monthYear, 'ev_verified'), ht = evidenceTag(h, t, monthYear, 'ev_onFile');
  return (
    <div className="nl-k-rows">
      <div className="nl-k-row"><span>{p.reference ? t('permit', { ref: p.reference.replace(/^#/, '') }) : t('permitNone')}</span><span className={`tag ${pt.cls}`}>{pt.text}</span></div>
      <div className="nl-k-row"><span>{h.reference ? t('handlers', { ref: h.reference }) : t('handlersNone')}</span><span className={`tag ${ht.cls}`}>{ht.text}</span></div>
      <div className="nl-k-row"><span>{t('seals')}</span><span className="tag tag-accent">{t('sealsTag')}</span></div>
      <Link className="nl-k-link" to="/b/$merchantId/compliance" params={{ merchantId }}>{t('complianceLink')}</Link>
    </div>
  );
}
