import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import { Button, Dialog, ErrorState, Field, formatNumber, PageSkeleton, Segmented, Select, Tag, TextArea, TextInput, useFormatters, useLocale, type Locale } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { SCREEN_PATH } from '../shell/screens';
import { boardQuery, STAGES, useSwitchboard, type Market, type Province, type Stage, type Zone } from './api';
import { useRegionsT, type RegionsKey, type RegionsT } from './messages';
import './regions.css';

const TONE: Record<Stage, 'neutral' | 'accent-2' | 'accent'> = { off: 'neutral', waitlist: 'neutral', pilot: 'accent-2', live: 'accent' };
const CHECKS = ['taxProfile', 'holidays', 'registries', 'marketWithZones'] as const;

/** "GST 5% + PST 7%", "HST 13%", "GST 5% + QST 9.975%": the tax profile's rates (basis points). */
export function taxText(tax: Record<string, number>, locale: Locale, t: RegionsT): string {
  const parts = (['gst', 'hst', 'pst', 'qst'] as const).filter(k => tax[k] != null)
    .map(k => `${k.toUpperCase()} ${formatNumber(tax[k]! / 100, locale, { maximumFractionDigits: 3 })}%`);
  return parts.length ? parts.join(' + ') : t('noTax');
}

/** Dollars typed in a form ("$2.99", "2,99", "Free") → cents; empty → null; unreadable → NaN. */
export function cents(text: string, free: string): number | null {
  const v = text.trim();
  if (!v) return null;
  if (v.toLowerCase() === free.toLowerCase() || v.toLowerCase() === 'free') return 0;
  const n = Number(v.replace(/[$\s]/g, '').replace(',', '.'));
  return Number.isFinite(n) ? Math.round(n * 100) : NaN;
}

const errorOf = (e: unknown) => (e instanceof ValidationError ? e.byField() : {});
const conflictOf = (e: unknown) => (e && !(e instanceof ValidationError) && e instanceof ApiError ? e.message : undefined);

/**
 * The province switchboard (S-84, design 03 `regions`): every province with its stage, tax profile, languages and
 * courier model; its markets, each with its own stage under the province's ceiling; its delivery zones (GeoJSON
 * boundaries); the rollout stage with the go-live checklist and a typed confirmation. Admins only; every change is
 * audited and reaches the region model at once.
 */
export function Switchboard() {
  const t = useRegionsT();
  const search = useSearch({ strict: false }) as { province?: string };
  const navigate = useNavigate();
  const board = useQuery(boardQuery);
  if (board.isPending) return <PageSkeleton kpis={0} rows={10} />;
  if (board.isError) return <ErrorState message={t('loadError')} onRetry={() => void board.refetch()} />;
  const provinces = board.data;
  const selected = provinces.find(p => p.code === search.province) ?? provinces.find(p => p.stage === 'live') ?? provinces[0];
  const pick = (code: string) => void navigate({ to: SCREEN_PATH.regions, search: { province: code } as never });
  return (
    <div>
      <span className="nl-rg-kicker">{t('kicker')}</span>
      <h1 className="nl-rg-title">{t('title')}</h1>
      <p className="nl-rg-lede">{t('lede')}</p>
      <div className="nl-rg-cols">
        <ProvinceList provinces={provinces} selected={selected?.code} onPick={pick} />
        {selected ? <ProvincePanel key={selected.code} province={selected} /> : null}
      </div>
    </div>
  );
}

function ProvinceList({ provinces, selected, onPick }: { provinces: Province[]; selected?: string; onPick: (code: string) => void }) {
  const t = useRegionsT();
  const { locale } = useLocale();
  return (
    <div className="nl-rg-list" role="list">
      {provinces.map(p => (
        <button key={p.code} type="button" role="listitem" className="nl-rg-row" aria-current={p.code === selected ? 'true' : undefined} onClick={() => onPick(p.code)}>
          <span className="nl-rg-dot" data-stage={p.stage} />
          <span className="nl-rg-row-text"><strong>{p.names[locale] ?? p.names.en}</strong><br /><span className="nl-rg-meta">{[taxText(p.tax, locale, t), p.languages.join(' · ')].join(' · ')}</span></span>
          <Tag tone={TONE[p.stage]}>{t(`st_${p.stage}` as RegionsKey)}</Tag>
        </button>
      ))}
    </div>
  );
}

function ProvincePanel({ province: p }: { province: Province }) {
  const t = useRegionsT();
  const { locale } = useLocale();
  const { can } = useGrant();
  const allowed = can('province');
  const change = useSwitchboard();
  const name = p.names[locale] ?? p.names.en ?? p.code;
  const [stage, setStage] = useState<Stage>(p.stage);
  const [confirming, setConfirming] = useState<null | { kind: 'province' } | { kind: 'market'; market: Market; stage: Stage }>(null);
  return (
    <div>
      <h2 className="nl-rg-h2">{name}</h2>
      <div className="nl-rg-sub">{[taxText(p.tax, locale, t), p.languages.join(' · '), p.timeZones[0]].filter(Boolean).join(' · ')}</div>
      <div className="nl-rg-fields">
        <div className="nl-rg-field"><span className="nl-rg-label">{t('taxProfile')}</span><div>{taxText(p.tax, locale, t)}</div></div>
        <div className="nl-rg-field"><span className="nl-rg-label">{t('languages')}</span>
          <div className="nl-rg-tags"><Tag tone={p.languages.includes('en') ? 'accent' : 'neutral'}>{t('english')}</Tag><Tag tone={p.languages.includes('fr') ? 'accent' : 'neutral'}>{t('french')}</Tag></div></div>
        <div className="nl-rg-field"><span className="nl-rg-label" id={`cm-${p.code}`}>{t('courierModel')}</span>
          <Segmented name={`cm-${p.code}`} aria-label={t('courierModel')} value={p.courierModel ?? ''}
            options={(['own', 'contracted', 'hybrid'] as const).map(m => ({ value: m, label: t(`cm_${m}` as RegionsKey) }))}
            onChange={v => { if (allowed) change.mutate({ kind: 'courierModel', code: p.code, courierModel: v }); }} />
        </div>
        <Markets province={p} name={name} allowed={allowed} onStage={(market, s) => setConfirming({ kind: 'market', market, stage: s })} />
        <Zones province={p} name={name} allowed={allowed} />
        <div className="nl-rg-field"><span className="nl-rg-label">{t('rollout')}</span>
          <div className="nl-rg-stages" role="radiogroup" aria-label={t('rollout')}>
            {STAGES.map(s => (
              <button key={s} type="button" role="radio" aria-checked={stage === s} className="nl-rg-stage" disabled={!allowed} onClick={() => setStage(s)}>
                <strong>{t(`st_${s}` as RegionsKey)}</strong><span> · {t(`sd_${s}` as RegionsKey)}</span>
              </button>
            ))}
          </div>
        </div>
        <div className="nl-rg-actions">
          <Button disabled={!allowed} onClick={() => setConfirming({ kind: 'province' })}>{t(`cta_${stage}` as RegionsKey)}</Button>
        </div>
        {!allowed ? <div className="nl-rg-error">{t('cannot')}</div> : null}
        <div className="nl-rg-note">{t('audited')}</div>
      </div>
      {confirming?.kind === 'province' ? <ConfirmStage province={p} name={name} stage={stage} onClose={() => setConfirming(null)} /> : null}
      {confirming?.kind === 'market' ? <ConfirmMarket market={confirming.market} stage={confirming.stage} onClose={() => setConfirming(null)} /> : null}
    </div>
  );
}

function Markets({ province: p, name, allowed, onStage }: { province: Province; name: string; allowed: boolean; onStage: (m: Market, s: Stage) => void }) {
  const t = useRegionsT();
  const change = useSwitchboard();
  const ceiling = STAGES.indexOf(p.stage);
  const [city, setCity] = useState('');
  const [lat, setLat] = useState('');
  const [lng, setLng] = useState('');
  const [radius, setRadius] = useState('15');
  const errors = errorOf(change.error);
  const conflict = conflictOf(change.error);
  const add = () => change.mutate({ kind: 'addMarket', province: p.code, city: city.trim(), lat: Number(lat), lng: Number(lng), radiusKm: Number(radius) },
    { onSuccess: () => { setCity(''); setLat(''); setLng(''); } });
  return (
    <div className="nl-rg-field">
      <span className="nl-rg-label">{t('marketsLabel', { name })}</span>
      {p.markets.length ? p.markets.map(m => (
        <div key={m.id} className="nl-rg-market">
          <span><strong>{m.city}</strong><span className="nl-rg-meta nl-rg-block">{t('marketMeta', { zones: m.zones, waitlist: m.waitlist })}</span></span>
          <div className="nl-rg-mstages" role="radiogroup" aria-label={t('marketStages', { city: m.city })}>
            {STAGES.map((s, i) => (
              <button key={s} type="button" role="radio" aria-checked={m.stage === s} className="nl-rg-mstage" data-live={s === 'live' || undefined}
                disabled={!allowed || i > ceiling || m.stage === s || s === 'live'} onClick={() => onStage(m, s)}>{t(`st_${s}` as RegionsKey)}</button>
            ))}
          </div>
          {m.stage === 'pilot' ? <Link className="nl-rg-meta" to={SCREEN_PATH.go_live} search={{ market: m.id } as never}>{t('goLiveLink')}</Link> : null}
        </div>
      )) : <div className="nl-rg-meta">{t('noMarkets')}</div>}
      {allowed ? (
        <div className="nl-rg-add">
          <TextInput aria-label={t('addMarketPlaceholder')} placeholder={t('addMarketPlaceholder')} value={city} onChange={e => setCity(e.target.value)} />
          {city.trim() ? <>
            <TextInput aria-label={t('lat')} placeholder={t('lat')} inputMode="decimal" value={lat} onChange={e => setLat(e.target.value)} />
            <TextInput aria-label={t('lng')} placeholder={t('lng')} inputMode="decimal" value={lng} onChange={e => setLng(e.target.value)} />
            <TextInput aria-label={t('radius')} placeholder={t('radius')} inputMode="decimal" value={radius} onChange={e => setRadius(e.target.value)} />
          </> : null}
          <Button variant="secondary" disabled={!city.trim() || change.isPending} onClick={add}>{t('addMarket')}</Button>
        </div>
      ) : null}
      {Object.values(errors)[0] ?? conflict ? <p role="alert" className="nl-rg-error">{Object.values(errors)[0] ?? conflict}</p> : null}
      <div className="nl-rg-note">{t('ceiling')}</div>
    </div>
  );
}

interface ZoneForm { id?: string; name: string; marketId: string; runs: string; fee: string; plus: string; min: string; boundary: string; hadBoundary: boolean }

function Zones({ province: p, name, allowed }: { province: Province; name: string; allowed: boolean }) {
  const t = useRegionsT();
  const fmt = useFormatters();
  const change = useSwitchboard();
  const [form, setForm] = useState<ZoneForm | null>(null);
  const [geo, setGeo] = useState(false);
  const [removing, setRemoving] = useState<Zone | null>(null);
  const city = (id: string) => p.markets.find(m => m.id === id)?.city ?? '';
  const money = (c: number | null | undefined) => (c == null ? t('free') : c === 0 ? t('free') : fmt.money(c));
  const edit = (z?: Zone) => {
    setGeo(false);
    change.reset();
    setForm(z ? { id: z.id, name: z.name, marketId: z.marketId, runs: String(z.runsPerDay ?? 2), fee: z.feeStdCents == null ? '' : (z.feeStdCents / 100).toFixed(2),
      plus: z.feePlusCents ? (z.feePlusCents / 100).toFixed(2) : t('free'), min: z.minBasketCents == null ? '' : (z.minBasketCents / 100).toFixed(2), boundary: '', hadBoundary: z.areaKm2 != null }
      : { name: '', marketId: p.markets[0]?.id ?? '', runs: '2', fee: '', plus: t('free'), min: '25', boundary: '', hadBoundary: false });
  };
  const errors = errorOf(change.error);
  const conflict = conflictOf(change.error);
  const save = () => {
    if (!form) return;
    change.mutate({ kind: 'saveZone', zoneId: form.id, zone: {
      marketId: form.marketId, name: form.name.trim(), runsPerDay: Number(form.runs), feeStdCents: cents(form.fee, t('free')),
      feePlusCents: cents(form.plus, t('free')), minBasketCents: cents(form.min, t('free')), boundary: form.boundary.trim() || null,
    } }, { onSuccess: () => setForm(null) });
  };
  const set = (k: keyof ZoneForm) => (e: { target: { value: string } }) => setForm(f => (f ? { ...f, [k]: e.target.value } : f));
  return (
    <div className="nl-rg-field">
      <span className="nl-rg-label">{t('zonesLabel', { name, count: p.zones.length })}</span>
      {p.zones.map(z => (
        <div key={z.id} className="nl-rg-zone">
          <span><strong>{z.name}</strong> <span className="nl-rg-meta">· {city(z.marketId)}</span>
            <span className="nl-rg-meta nl-rg-block">{t('zoneMeta', { runs: z.runsPerDay ?? 0, fee: money(z.feeStdCents), plus: money(z.feePlusCents), min: z.minBasketCents == null ? t('free') : fmt.money(z.minBasketCents),
              boundary: z.areaKm2 == null ? t('noBoundary') : t('polygon', { area: fmt.number(z.areaKm2, { maximumFractionDigits: 1 }) }) })}</span></span>
          <div className="nl-rg-zone-actions">
            <Button variant="ghost" aria-label={`${t('edit')} ${z.name}`} disabled={!allowed} onClick={() => edit(z)}>{t('edit')}</Button>
            <Button variant="ghost" className="nl-rg-danger" aria-label={`${t('remove')} ${z.name}`} disabled={!allowed} onClick={() => setRemoving(z)}>{t('remove')}</Button>
          </div>
        </div>
      ))}
      {form ? (
        <div className="nl-rg-form" role="group" aria-label={form.id ? t('editZone', { name: form.name }) : t('newZone')}>
          <div className="nl-rg-form-title">{form.id ? t('editZone', { name: form.name }) : t('newZone')}</div>
          <div className="nl-rg-grid">
            <Field label={t('zoneName')} error={errors.name}><TextInput placeholder={t('zonePlaceholder')} value={form.name} onChange={set('name')} /></Field>
            <Field label={t('market')} error={errors.marketId}><Select value={form.marketId} onChange={set('marketId')} options={p.markets.map(m => ({ value: m.id, label: m.city }))} /></Field>
            <Field label={t('runsPerDay')} error={errors.runsPerDay}><Select value={form.runs} onChange={set('runs')} options={['0', '1', '2', '3', '4'].map(v => ({ value: v, label: v }))} /></Field>
            <Field label={t('feeStd')} error={errors.feeStdCents}><TextInput placeholder="$2.99" inputMode="decimal" value={form.fee} onChange={set('fee')} /></Field>
            <Field label={t('feePlus')} error={errors.feePlusCents}><TextInput placeholder={t('free')} value={form.plus} onChange={set('plus')} /></Field>
            <Field label={t('minBasket')} error={errors.minBasketCents}><TextInput placeholder="$25" inputMode="decimal" value={form.min} onChange={set('min')} /></Field>
          </div>
          <div className="nl-rg-field">
            <span className="nl-rg-label">{t('boundary')}</span>
            <div className="nl-rg-tags"><Button variant="secondary" onClick={() => setGeo(g => !g)} aria-expanded={geo}>{t('importGeoJson')}</Button>
              <span className="nl-rg-meta">{form.hadBoundary ? t('keepBoundary') : t('noBoundary')}</span></div>
            {geo ? <Field label={t('geoJsonLabel')} error={errors.boundary}><TextArea rows={4} value={form.boundary} onChange={set('boundary')} /></Field> : null}
          </div>
          {conflict ? <p role="alert" className="nl-rg-error">{conflict}</p> : null}
          <div className="nl-rg-tags"><Button disabled={!form.name.trim() || !form.marketId || change.isPending} onClick={save}>{form.id ? t('saveZone') : t('createZone')}</Button>
            <Button variant="ghost" onClick={() => setForm(null)}>{t('cancel')}</Button></div>
        </div>
      ) : (
        <div className="nl-rg-tags">
          <Button variant="secondary" disabled={!allowed || !p.markets.length} onClick={() => edit()}>{t('addZone')}</Button>
          {p.markets[0] ? <Link to={SCREEN_PATH.delivery} search={{ market: p.markets[0].id } as never} className="btn btn-ghost">{t('openMap')}</Link> : null}
        </div>
      )}
      <div className="nl-rg-note">{t('zonesNote')}</div>
      {removing ? (
        <Dialog open role="alertdialog" onClose={() => setRemoving(null)} title={t('removeTitle', { name: removing.name })}
          actions={<><Button variant="ghost" onClick={() => setRemoving(null)}>{t('cancel')}</Button>
            <Button onClick={() => change.mutate({ kind: 'removeZone', zoneId: removing.id }, { onSuccess: () => setRemoving(null) })}>{t('remove')}</Button></>}>
          <p>{t('removeBody')}</p>
          {conflictOf(change.error) ? <p role="alert" className="nl-rg-error">{conflictOf(change.error)}</p> : null}
        </Dialog>
      ) : null}
    </div>
  );
}

function ConfirmStage({ province: p, name, stage, onClose }: { province: Province; name: string; stage: Stage; onClose: () => void }) {
  const t = useRegionsT();
  const change = useSwitchboard();
  const [typed, setTyped] = useState('');
  const errors = errorOf(change.error);
  const goingLive = stage === 'live' && p.stage !== 'live';
  const lowers = STAGES.indexOf(stage) < STAGES.indexOf(p.stage) && p.markets.some(m => STAGES.indexOf(m.stage) > STAGES.indexOf(stage));
  return (
    <Dialog open role="alertdialog" onClose={onClose} title={t('confirmTitle', { name, stage: t(`st_${stage}` as RegionsKey) })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={typed.trim().toUpperCase() !== p.code || change.isPending}
          onClick={() => change.mutate({ kind: 'provinceStage', code: p.code, stage, confirm: typed.trim() }, { onSuccess: onClose })}>{t('confirm')}</Button></>}>
      {goingLive ? (
        <div className="nl-rg-field">
          <span className="nl-rg-label">{t('checklist')}</span>
          <ul className="nl-rg-checklist">
            {CHECKS.map(k => <li key={k} data-done={p.checklist[k] ? 'true' : 'false'}>{t(`ck_${k}` as RegionsKey)} · {p.checklist[k] ? t('ckDone') : t('ckMissing')}</li>)}
          </ul>
        </div>
      ) : null}
      {lowers ? <p className="nl-rg-note">{t('lowers', { stage: t(`st_${stage}` as RegionsKey) })}</p> : null}
      <Field label={t('confirmField')} hint={t('confirmProvince', { code: p.code })} error={errors.confirm}>
        <TextInput value={typed} onChange={e => setTyped(e.target.value)} autoComplete="off" />
      </Field>
      {conflictOf(change.error) ? <p role="alert" className="nl-rg-error">{conflictOf(change.error)}</p> : null}
    </Dialog>
  );
}

function ConfirmMarket({ market, stage, onClose }: { market: Market; stage: Stage; onClose: () => void }) {
  const t = useRegionsT();
  const change = useSwitchboard();
  const [typed, setTyped] = useState('');
  const errors = errorOf(change.error);
  return (
    <Dialog open role="alertdialog" onClose={onClose} title={t('confirmMarketTitle', { city: market.city, stage: t(`st_${stage}` as RegionsKey) })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={typed.trim().toLowerCase() !== market.city.toLowerCase() || change.isPending}
          onClick={() => change.mutate({ kind: 'marketStage', marketId: market.id, stage, confirm: typed.trim() }, { onSuccess: onClose })}>{t('confirm')}</Button></>}>
      <Field label={t('confirmField')} hint={t('confirmMarket', { city: market.city })} error={errors.confirm ?? errors.stage}>
        <TextInput value={typed} onChange={e => setTyped(e.target.value)} autoComplete="off" />
      </Field>
      {conflictOf(change.error) ? <p role="alert" className="nl-rg-error">{conflictOf(change.error)}</p> : null}
    </Dialog>
  );
}
