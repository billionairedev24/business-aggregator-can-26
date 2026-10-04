import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Chip, ErrorState, Field, OptionCard, PageSkeleton, RadioGroup, Select, formatMoney, useLocale } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { ValidationError } from '../../lib/http';
import { rulesQuery, useSaveRules, type Rules, type RulesInput } from './api';
import type { SaveState } from './AvailabilityScreen';
import { useAvailabilityT } from './messages';

type T = ReturnType<typeof useAvailabilityT>;

/** Select values for the combined options (notice, late fee, premium) ↔ rule fields. */
const noticeValue = (r: RulesInput) => (r.sameDayCutoffMin ? 'sameday' : String(r.minNoticeMin));
const lateValue = (r: RulesInput) => (r.lateCancelFeeBps ? 'pct' : String(r.lateCancelFeeCents ?? 0));
const premiumValue = (r: RulesInput) => (r.emergencyPremiumBps ? 'pct' : r.emergencyPremiumCents ? String(r.emergencyPremiumCents) : 'off');

export function RulesTab({ onState }: { onState: (s: SaveState) => void }) {
  const t = useAvailabilityT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const role = useRole();
  const canEdit = role !== 'bookkeeper';
  const q = useQuery(rulesQuery(merchantId));
  const save = useSaveRules(merchantId);
  const [form, setForm] = useState<RulesInput | null>(null);
  useEffect(() => { if (q.data) setForm(strip(q.data)); }, [q.data]);
  const dirty = !!form && !!q.data && JSON.stringify(form) !== JSON.stringify(strip(q.data));
  useEffect(() => { onState(dirty ? 'dirty' : save.isSuccess ? 'saved' : 'clean'); }, [dirty, save.isSuccess, onState]);

  if (q.isPending || !form) return q.isError ? <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /> : <PageSkeleton kpis={0} rows={6} />;
  const set = (patch: Partial<RulesInput>) => setForm(f => f && { ...f, ...patch });
  const money = (c: number) => formatMoney(c, locale, { whole: true });
  const serverErrors = save.error instanceof ValidationError ? save.error.byField() : {};
  const sel = (label: string, value: string, options: { value: string; label: string }[], onChange: (v: string) => void, hint?: string, err?: string) => (
    <Field label={label} hint={hint} error={err}>
      <Select value={value} disabled={!canEdit} options={options} onChange={e => onChange(e.target.value)} />
    </Field>
  );

  return (
    <div>
      <div className="nl-av-rules">
        <div className="nl-av-col">
          <h3 className="nl-av-h3">{t('timing')}</h3>
          {sel(t('interval'), String(form.intervalMin), ['15', '30', '60'].map(v => ({ value: v, label: v })), v => set({ intervalMin: Number(v) }), undefined, serverErrors.intervalMin)}
          {sel(t('buffer'), String(form.bufferMin), ['0', '15', '20', '30', '45'].map(v => ({ value: v, label: v })), v => set({ bufferMin: Number(v) }), t('bufferHint'), serverErrors.bufferMin)}
          {sel(t('notice'), noticeValue(form), [
            { value: '60', label: t('notice60') }, { value: '180', label: t('notice180') }, { value: 'sameday', label: t('noticeSameDay') }, { value: '1440', label: t('notice1440') }, { value: '2880', label: t('notice2880') },
          ], v => set(v === 'sameday' ? { minNoticeMin: 0, sameDayCutoffMin: 540 } : { minNoticeMin: Number(v), sameDayCutoffMin: null }), undefined, serverErrors.minNoticeMin)}
          {sel(t('horizon'), String(form.horizonDays), [14, 28, 42, 90].map(v => ({ value: String(v), label: t(`horizon${v}` as Parameters<T>[0]) })), v => set({ horizonDays: Number(v) }), undefined, serverErrors.horizonDays)}
          {sel(t('maxJobs'), String(form.maxJobsPerDay), ['3', '4', '5', '6', '99'].map(v => ({ value: v, label: v })), v => set({ maxJobsPerDay: Number(v) }), undefined, serverErrors.maxJobsPerDay)}
        </div>
        <div className="nl-av-col">
          <h3 className="nl-av-h3">{t('acceptance')}</h3>
          <div className="nl-field">
            <span className="nl-label" id="av-accept">{t('howBookings')}</span>
            <RadioGroup className="nl-av-options" aria-labelledby="av-accept">
              {(['instant', 'approve', 'request'] as const).map(m => (
                <OptionCard key={m} role="radio" aria-checked={form.acceptMode === m} selected={form.acceptMode === m} disabled={!canEdit} title={t(`accept_${m}`)} description={t(`accept_${m}_desc`)} onClick={() => set({ acceptMode: m })} />
              ))}
            </RadioGroup>
          </div>
          {sel(t('reschedule'), String(form.rescheduleFreeMin), [180, 720, 1440].map(v => ({ value: String(v), label: t(`reschedule${v}` as Parameters<T>[0]) })), v => set({ rescheduleFreeMin: Number(v) }))}
          {sel(t('lateFee'), lateValue(form), [
            { value: '0', label: money(0) }, { value: '2500', label: money(2500) }, { value: '3500', label: money(3500) }, { value: 'pct', label: t('lateFeePct') },
          ], v => set(v === 'pct' ? { lateCancelFeeBps: 5000, lateCancelFeeCents: null } : { lateCancelFeeCents: Number(v), lateCancelFeeBps: null }), undefined, serverErrors.lateCancelFee)}
          {sel(t('premium'), premiumValue(form), [
            { value: 'off', label: t('premiumOff') }, { value: '2500', label: `+${money(2500)}` }, { value: '5000', label: `+${money(5000)}` }, { value: 'pct', label: t('premiumPct') },
          ], v => set(v === 'off' ? { emergencyPremiumCents: null, emergencyPremiumBps: null } : v === 'pct' ? { emergencyPremiumBps: 2500, emergencyPremiumCents: null } : { emergencyPremiumCents: Number(v), emergencyPremiumBps: null }), undefined, serverErrors.emergencyPremium)}
          <div className="nl-field">
            <span className="nl-label" id="av-area">{t('serviceArea')}</span>
            <div className="nl-chips" role="group" aria-labelledby="av-area">
              {(q.data?.zones ?? []).map(z => {
                const on = form.serviceAreas.includes(z);
                return <Chip key={z} selected={on} disabled={!canEdit} onClick={() => set({ serviceAreas: on ? form.serviceAreas.filter(x => x !== z) : [...form.serviceAreas, z].sort() })}>{z}</Chip>;
              })}
            </div>
            {serverErrors.serviceAreas ? <div role="alert" className="nl-error">{serverErrors.serviceAreas}</div> : null}
          </div>
        </div>
      </div>
      {save.isError && !(save.error instanceof ValidationError) ? <Alert tone="error">{t('saveError')}</Alert> : null}
      {canEdit
        ? <button type="button" className="btn btn-primary nl-av-saverules" disabled={save.isPending || !dirty} onClick={() => save.mutate(form)}>{t('saveRules')}</button>
        : <p className="nl-small nl-muted">{t('readOnly', { role: t(`role_${role}` as Parameters<T>[0]) })}</p>}
    </div>
  );
}

function strip(r: Rules): RulesInput {
  const { zones: _z, lastSavedAt: _l, ...rest } = r;
  return { ...rest, serviceAreas: [...rest.serviceAreas].sort(), sameDayCutoffMin: rest.sameDayCutoffMin ?? null, lateCancelFeeCents: rest.lateCancelFeeCents ?? null, lateCancelFeeBps: rest.lateCancelFeeBps ?? null, emergencyPremiumCents: rest.emergencyPremiumCents ?? null, emergencyPremiumBps: rest.emergencyPremiumBps ?? null };
}
