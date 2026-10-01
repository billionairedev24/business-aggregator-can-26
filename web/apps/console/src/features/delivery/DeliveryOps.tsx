import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import { Button, DataTable, Dialog, EmptyState, ErrorState, Field, formatNumber, PageSkeleton, Select, TextArea, useFormatters, useLocale, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useRegions, type Regions } from '../shell/api';
import { useGrant } from '../shell/grant';
import { SCREEN_PATH } from '../shell/screens';
import { couriersQuery, mapQuery, runsQuery, useAssignRun, usePauseCourier, useResumeCourier, type Courier, type DeliveryMap, type Run } from './api';
import { OpsMap, stuck } from './OpsMap';
import { useDeliveryT, type DeliveryKey, type DeliveryT } from './messages';
import './delivery.css';

type Market = Regions['markets'][number];

/** The market shown: the one asked for, else the first live one, else the first in pilot (region model order). */
export function pickMarket(regions: Regions | undefined, id: string | undefined): Market | undefined {
  const open = (regions?.markets ?? []).filter(m => m.status !== 'off');
  return open.find(m => m.id === id) ?? open.find(m => m.status === 'live') ?? open.find(m => m.status === 'pilot') ?? open[0];
}

const errorText = (e: unknown) => (e instanceof ValidationError ? Object.values(e.byField())[0] : e instanceof ApiError ? e.message : undefined);

/**
 * Delivery ops (S-81, design 03 `delivery`): a market's couriers on runs, on-time share and stuck runs; the map;
 * tonight's pooled runs with Reassign; the couriers with Pause / Resume (not in the design's mock, needed to act on a
 * courier — S-81); zone pricing. Admin and dispatch open it; changes need the `dispatch` action and are audited.
 */
export function DeliveryOps() {
  const t = useDeliveryT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as { market?: string };
  const navigate = useNavigate();
  const regions = useRegions(locale);
  const market = pickMarket(regions.data, search.market);
  const city = market?.city ?? '';
  const runs = useQuery({ ...runsQuery(city), enabled: !!market });
  const couriers = useQuery({ ...couriersQuery(city), enabled: !!market });
  const map = useQuery({ ...mapQuery(market?.id ?? ''), enabled: !!market });
  if (regions.isPending) return <PageSkeleton kpis={0} rows={6} />;
  const open = (regions.data?.markets ?? []).filter(m => m.status !== 'off');
  const picker = open.length ? (
    <Select aria-label={t('market')} value={market?.id ?? ''} options={open.map(m => ({ value: m.id, label: m.city }))}
      onChange={e => void navigate({ to: SCREEN_PATH.delivery, search: { market: e.target.value } as never })} />
  ) : null;
  const top = <div className="nl-dl-top"><span className="nl-dl-kicker">{market ? t('kicker', { city }) : t('kickerNone')}</span>{picker}</div>;
  if (!market) return <div>{top}<EmptyState>{t('noMarkets')}</EmptyState></div>;
  if (runs.isPending || couriers.isPending || map.isPending) return <div>{top}<PageSkeleton kpis={0} rows={6} /></div>;
  if (runs.isError || couriers.isError || map.isError) {
    return <div>{top}<ErrorState message={t('loadError')} onRetry={() => { void runs.refetch(); void couriers.refetch(); void map.refetch(); }} /></div>;
  }
  return <OpsView top={top} runs={runs.data} couriers={couriers.data} map={map.data} />;
}

function OpsView({ top, runs, couriers, map }: { top: React.ReactNode; runs: Run[]; couriers: Courier[]; map: DeliveryMap }) {
  const t = useDeliveryT();
  const { locale } = useLocale();
  const active = runs.filter(r => r.state !== 'done');
  const onRuns = couriers.filter(c => c.runId).length;
  const late = active.filter(r => r.late).length;
  const onTime = active.length ? formatNumber((active.length - late) / active.length, locale, { style: 'percent', maximumFractionDigits: 1 }) : t('none');
  const onShift = couriers.filter(c => c.shift?.state === 'on');
  const quiet = onShift.filter(c => !c.position).length;
  const { grant } = useGrant();
  return (
    <div>
      {top}
      <h1 className="nl-dl-title">{t('title', { couriers: onRuns, runs: active.length, onTime, stuck: formatNumber(late, locale) })}</h1>
      <div className="nl-dl-cols">
        <div>
          <OpsMap map={map} couriers={couriers} runs={runs} />
          {quiet ? <p className="nl-dl-note">{t('noPosition', { n: quiet })}</p> : null}
        </div>
        <div>
          <h2 className="nl-dl-h2">{t('runsTitle')}</h2>
          <RunsTable runs={runs} couriers={couriers} />
          <h2 className="nl-dl-h2 nl-dl-gap">{t('couriersTitle')}</h2>
          <CouriersTable couriers={couriers} runs={runs} />
          <h2 className="nl-dl-h2 nl-dl-gap">{t('zonesTitle')}</h2>
          <ZonesTable map={map} />
          <p className="nl-dl-note">{t('zonesNote')}</p>
          {grant?.screens.includes('regions') ? <div className="nl-dl-actions"><Link to={SCREEN_PATH.regions} className="btn btn-secondary">{t('editZones')}</Link></div> : null}
        </div>
      </div>
    </div>
  );
}

interface RunRow { id: string; run: string; zone: string; stops: number; courier: string; eta: string; state: string; src: Run }

function runState(r: Run, t: DeliveryT, locale: 'en' | 'fr'): { text: string; tone: DataTableTone } {
  if (r.state === 'done') return { text: t('r_done'), tone: 'tag-neutral' };
  if (r.late) {
    const minutes = r.nextEta ? Math.max(0, (Date.now() - Date.parse(r.nextEta)) / 60_000) : 0;
    const age = minutes < 60 ? t('ageMin', { n: Math.round(minutes) }) : t('ageH', { n: formatNumber(minutes / 60, locale, { maximumFractionDigits: 1 }) });
    return { text: t('r_stuck', { age }), tone: 'tag-accent-2' };
  }
  if (r.state === 'loading') return { text: t('r_loading'), tone: 'tag-neutral' };
  if (r.state === 'planned') return { text: r.courier ? t('r_planned') : t('r_unassigned'), tone: 'tag-neutral' };
  return { text: t('r_onTime'), tone: 'tag-accent' };
}

function RunsTable({ runs, couriers }: { runs: Run[]; couriers: Courier[] }) {
  const t = useDeliveryT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const { can, roleName } = useGrant();
  const [moving, setMoving] = useState<Run | null>(null);
  const rows: RunRow[] = runs.map(r => ({
    id: r.id, run: r.label ? (r.part > 1 ? `${r.label}·${r.part}` : r.label) : r.id.slice(-6), zone: r.market, stops: r.stopsTotal,
    courier: r.courier?.name ?? t('none'), eta: r.nextEta ? fmt.date(r.nextEta, 'time') : t('none'), state: runState(r, t, locale).text, src: r,
  }));
  const columns: DataTableColumn<RunRow>[] = [
    { key: 'run', label: t('c_run'), primary: true }, { key: 'zone', label: t('c_zone') }, { key: 'stops', label: t('c_stops'), type: 'num' },
    { key: 'courier', label: t('c_courier') }, { key: 'eta', label: t('c_eta') }, { key: 'state', label: t('c_state'), type: 'tag' },
  ];
  return (
    <>
      <DataTable<RunRow> entity={t('runEntity')} plural={t('runPlural')} columns={columns} rows={rows} pageSize={5}
        rowTones={r => ({ state: runState(r.src, t, locale).tone })} roleName={roleName}
        can={{ create: false, update: can('dispatch'), delete: false }}
        actions={[{ id: 'reassign', label: t('reassign'), perm: 'update', inline: true, bulk: false }]}
        onAction={(_, picked) => { setMoving(picked[0]?.src ?? null); return false; }} />
      {moving ? <ReassignDialog run={moving} couriers={couriers} onClose={() => setMoving(null)} /> : null}
    </>
  );
}

function ReassignDialog({ run, couriers, onClose }: { run: Run; couriers: Courier[]; onClose: () => void }) {
  const t = useDeliveryT();
  const assign = useAssignRun();
  const free = couriers.filter(c => c.active && c.status === 'available' && !c.runId && c.id !== run.courier?.id);
  const [courierId, setCourierId] = useState(free[0]?.id ?? '');
  const error = assign.error ? errorText(assign.error) : undefined;
  return (
    <Dialog open onClose={onClose} title={t('reassignTitle', { run: run.label ?? run.id })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={!courierId || assign.isPending} onClick={() => assign.mutate({ runId: run.id, courierId }, { onSuccess: onClose })}>{t('reassign')}</Button></>}>
      <p className="nl-dl-note">{t('reassignNote')}</p>
      {free.length ? (
        <Field label={t('reassignTo')} error={error}>
          <Select value={courierId} onChange={e => setCourierId(e.target.value)} options={free.map(c => ({ value: c.id, label: c.name ?? c.id }))} />
        </Field>
      ) : <p>{t('reassignNone')}</p>}
      {!free.length && error ? <p role="alert" className="nl-dl-error">{error}</p> : null}
    </Dialog>
  );
}

interface CourierRow { id: string; name: string; vehicle: string; status: string; shift: string; run: string; src: Courier }

function CouriersTable({ couriers, runs }: { couriers: Courier[]; runs: Run[] }) {
  const t = useDeliveryT();
  const fmt = useFormatters();
  const { can, roleName } = useGrant();
  const resume = useResumeCourier();
  const [pausing, setPausing] = useState<Courier | null>(null);
  const status = (c: Courier) => (c.active ? t(`st_${c.status}` as DeliveryKey) : t('st_paused'));
  const rows: CourierRow[] = couriers.map(c => ({
    id: c.id, name: c.name ?? c.id, vehicle: c.vehicle ? t(`v_${c.vehicle}` as DeliveryKey) : t('none'), status: status(c),
    shift: c.shift ? t('shift', { from: fmt.date(c.shift.startsAt, 'time'), to: fmt.date(c.shift.endsAt, 'time') }) : t('none'),
    run: c.runId ? (runs.find(r => r.id === c.runId)?.label ?? c.runId.slice(-6)) : t('none'), src: c,
  }));
  const tone = (c: Courier): DataTableTone => (!c.active || stuck(c, runs, Date.now()) ? 'tag-accent-2' : c.status === 'offline' ? 'tag-neutral' : 'tag-accent');
  const columns: DataTableColumn<CourierRow>[] = [
    { key: 'name', label: t('k_name'), primary: true }, { key: 'vehicle', label: t('k_vehicle') }, { key: 'status', label: t('k_status'), type: 'tag' },
    { key: 'shift', label: t('k_shift') }, { key: 'run', label: t('k_run') },
  ];
  const paused = t('st_paused');
  return (
    <>
      <DataTable<CourierRow> entity={t('courierEntity')} plural={t('courierPlural')} columns={columns} rows={rows} pageSize={5}
        rowTones={r => ({ status: tone(r.src) })} roleName={roleName} can={{ create: false, update: can('dispatch'), delete: false }}
        actions={[
          { id: 'pause', label: t('pause'), perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [t('st_offline'), t('st_available'), t('st_on_run')] } },
          { id: 'resume', label: t('resume'), perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [paused] } },
        ]}
        onAction={async (a, picked) => {
          const c = picked[0]?.src;
          if (!c) return false;
          if (a.id === 'pause') { setPausing(c); return false; }
          await resume.mutateAsync(c.id);
          return undefined;
        }} />
      {pausing ? <PauseDialog courier={pausing} onClose={() => setPausing(null)} /> : null}
    </>
  );
}

function PauseDialog({ courier, onClose }: { courier: Courier; onClose: () => void }) {
  const t = useDeliveryT();
  const pause = usePauseCourier();
  const [reason, setReason] = useState('');
  const error = pause.error ? errorText(pause.error) : undefined;
  const name = courier.name ?? courier.id;
  return (
    <Dialog open onClose={onClose} title={t('pauseTitle', { name })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={pause.isPending} onClick={() => pause.mutate({ courierId: courier.id, reason }, { onSuccess: onClose })}>{t('pause')}</Button></>}>
      <p className="nl-dl-note">{t('pauseNote')}</p>
      <Field label={t('pauseReason')} error={error}>
        <TextArea value={reason} maxLength={500} onChange={e => setReason(e.target.value)} />
      </Field>
    </Dialog>
  );
}

interface ZoneRow { id: string; zone: string; runs: string; fee: string; plus: string; cost: string; margin: string }

function ZonesTable({ map }: { map: DeliveryMap }) {
  const t = useDeliveryT();
  const fmt = useFormatters();
  const { roleName } = useGrant();
  const money = (c: number | null | undefined) => (c == null ? t('none') : c === 0 ? t('free') : fmt.money(c));
  const rows: ZoneRow[] = map.zones.map(z => ({
    id: z.id, zone: z.name, runs: z.runsPerDay == null ? t('none') : String(z.runsPerDay), fee: money(z.feeStdCents), plus: money(z.feePlusCents),
    cost: t('none'), margin: t('none'),
  }));
  const columns: DataTableColumn<ZoneRow>[] = [
    { key: 'zone', label: t('z_zone'), primary: true }, { key: 'runs', label: t('z_runs') }, { key: 'fee', label: t('z_fee') },
    { key: 'plus', label: t('z_plus') }, { key: 'cost', label: t('z_cost') }, { key: 'margin', label: t('z_margin') },
  ];
  return <DataTable<ZoneRow> entity={t('zoneEntity')} plural={t('zonePlural')} columns={columns} rows={rows} pageSize={5}
    can={{ create: false, update: false, delete: false }} roleName={roleName} />;
}
