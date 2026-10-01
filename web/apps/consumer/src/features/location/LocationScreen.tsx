import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';
import { ApiError, ValidationError } from '@northline/client';
import { Alert, Button, ErrorState, Field, Skeleton, Tag, TextInput, useLocale } from '@northline/ui';
import { useViewer } from '../session/api';
import { newSessionToken, useAddress, useJoinWaitlist, useMarkets, useSuggestions, type Address, type Province, type Stage } from './api';
import { useLocationT } from './messages';
import { useDeliveryLocation, type DeliveryLocation } from './useDeliveryLocation';

type T = ReturnType<typeof useLocationT>;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

/** Waits `ms` after the last change (typeahead: one request per pause, not per keystroke). */
function useDebounced<V>(value: V, ms: number): V {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

/**
 * Location (design 06 `location`, S-47): province, street address with Google Places suggestions (Canada only, one
 * session token per search, the key on the server), the address parts, unit / drop-off note, the market / zone /
 * pooled runs / tax it resolves to, and Save — or the waitlist when the address is outside a live market. Saving
 * writes the location every screen reads (`useDeliveryLocation().save`) and goes back to `next` (or home).
 */
export function LocationScreen({ next }: { next?: string }) {
  const t = useLocationT();
  const navigate = useNavigate();
  const { location, save } = useDeliveryLocation();
  const markets = useMarkets();

  const [province, setProvince] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [session, setSession] = useState(() => newSessionToken());
  const [placeId, setPlaceId] = useState<string | null>(null);
  const [unit, setUnit] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [touchedSaved, setTouchedSaved] = useState(false);

  // The saved address, until the person starts another search.
  const saved = location.status === 'saved' && !touchedSaved ? location : null;
  useEffect(() => {
    if (location.status === 'saved' && !touchedSaved) {
      setQuery(location.street ? `${location.street}, ${location.city}` : (location.label ?? ''));
      setUnit(location.unit ?? '');
    }
  }, [location, touchedSaved]);

  const address = useAddress(placeId, session);
  const picked = address.data ?? null;
  const shownProvince = picked?.province ?? province ?? saved?.province ?? location.province ?? markets.data?.items[0]?.code ?? '';
  const provinceOf = (code: string) => markets.data?.items.find(p => p.code === code);

  const onQuery = (value: string) => {
    setQuery(value);
    setTouchedSaved(true);
    setError(null);
    if (placeId) { setPlaceId(null); setSession(newSessionToken()); }
  };

  const choose = (id: string, label: string) => {
    setPlaceId(id);
    setQuery(label);
    setError(null);
  };

  const onSave = () => {
    if (!picked) { setError(t('pickFromList')); return; }
    if (!picked.resolution.market || picked.resolution.market.stage !== 'live') return; // the waitlist panel is shown instead
    const r = picked.resolution;
    save({
      label: picked.label, city: r.market!.city, lat: picked.lat, lng: picked.lng, placeId: picked.placeId,
      street: picked.street, ...(unit.trim() ? { unit: unit.trim() } : {}),
      ...(picked.province ? { province: picked.province } : {}), ...(picked.postalCode ? { postalCode: picked.postalCode } : {}),
      marketId: r.market!.id, ...(r.zone ? { zoneId: r.zone.id, zone: r.zone.name } : {}),
    });
    void navigate({ to: next && next.startsWith('/') && !next.startsWith('//') ? next : '/' });
  };

  const live = picked ? picked.resolution.market?.stage === 'live' : !!saved;

  return (
    <div className="nl-location">
      <h1 className="nl-location-title">{t('title')}</h1>
      <p className="nl-location-lede">{t('lede')}</p>

      <ProvincePicker t={t} markets={markets} value={shownProvince} onChange={p => { setProvince(p); }} />

      <AddressCombobox t={t} value={query} onChange={onQuery} session={session} near={location}
        onChoose={choose} error={error ?? (address.isError ? t('addressError') : null)} />

      {address.isFetching && placeId && <p className="nl-location-muted" role="status">{t('resolving')}</p>}
      {picked && <Parts t={t} a={picked} />}
      {!picked && saved && saved.street && <SavedParts t={t} l={saved} />}

      <Field label={t('unit')} className="nl-location-unit">
        <TextInput value={unit} onChange={e => setUnit(e.target.value)} placeholder={t('unitPlaceholder')} maxLength={120} autoComplete="address-line2" />
      </Field>

      {picked ? <Tags t={t} market={picked.resolution.market ?? null} zone={picked.resolution.zone ?? null} province={provinceOf(picked.province ?? shownProvince)} />
        : saved ? <Tags t={t} market={saved.marketId ? { city: saved.city!, stage: 'live' } : null} zone={saved.zone ? { name: saved.zone } : null} province={provinceOf(saved.province ?? shownProvince)} />
        : null}
      <p className="nl-location-info">{info(t, provinceOf(shownProvince))}</p>

      {picked && !live ? (
        <Waitlist t={t} a={picked} />
      ) : (
        <Button className="nl-location-save" onClick={onSave} disabled={address.isFetching}>{t('save')}</Button>
      )}
    </div>
  );
}

function ProvincePicker({ t, markets, value, onChange }: { t: T; markets: ReturnType<typeof useMarkets>; value: string; onChange: (p: string) => void }) {
  if (markets.isPending) return <div className="nl-location-provinces" aria-busy="true">{[0, 1, 2, 3].map(p => <Skeleton key={p} height={60} radius={12} />)}</div>;
  if (markets.isError) return <ErrorState message={t('marketsError')} onRetry={() => void markets.refetch()} />;
  const list = markets.data.items;
  return (
    <div className="nl-location-provinces" role="group" aria-label={t('provinces')}>
      {list.map(p => {
        const open = p.stage === 'live' || p.stage === 'pilot';
        const note = stageNote(t, p);
        return (
          <button key={p.code} type="button" className="nl-location-province" aria-pressed={value === p.code} disabled={!open} onClick={() => onChange(p.code)}>
            {p.name}
            <span className="nl-location-province-note">{note}</span>
          </button>
        );
      })}
    </div>
  );
}

/** "Live · {first two live markets}" (as the design), "Pilot · invite only", "Waitlist". */
function stageNote(t: T, p: Province): string {
  switch (p.stage) {
    case 'live': {
      const cities = p.markets.filter(m => m.stage === 'live').slice(0, 2).map(m => m.city).join(', ');
      return cities ? t('stageLive', { cities }) : t('stageLiveOnly');
    }
    case 'pilot': return t('stagePilot');
    case 'waitlist': return t('stageWaitlist');
    default: return t('stageOff');
  }
}

function AddressCombobox({ t, value, onChange, session, near, onChoose, error }: {
  t: T; value: string; onChange: (v: string) => void; session: string; near: DeliveryLocation;
  onChoose: (placeId: string, label: string) => void; error: string | null;
}) {
  const id = useId();
  const listId = `${id}-list`;
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const debounced = useDebounced(value, 250);
  const suggestions = useSuggestions(open ? debounced : '', session, near);
  const items = suggestions.data?.items ?? [];
  const inputRef = useRef<HTMLInputElement>(null);
  const q = debounced.trim();
  const showList = open && q.length >= 3;

  useEffect(() => { setActive(-1); }, [suggestions.data]);

  const pick = (i: number) => {
    const s = items[i];
    if (!s) return;
    onChoose(s.placeId, `${s.main}, ${s.secondary.split(',')[0]}`);
    setOpen(false);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive(a => Math.min(items.length - 1, a + 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(0, a - 1)); }
    else if (e.key === 'Enter' && showList && active >= 0) { e.preventDefault(); pick(active); }
    else if (e.key === 'Escape') setOpen(false);
  };
  const status = suggestions.isError
    ? (suggestions.error instanceof ApiError && suggestions.error.status === 429 ? t('tooMany') : t('lookupError'))
    : null;

  return (
    <div className="nl-location-address">
      <Field label={t('street')} error={error ?? undefined}>
        <input ref={inputRef} className="input" type="text" role="combobox" aria-autocomplete="list" aria-expanded={showList}
          aria-controls={listId} aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
          value={value} placeholder={t('streetPlaceholder')} autoComplete="off" maxLength={200}
          onChange={e => { onChange(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)} onKeyDown={onKey} />
      </Field>
      {showList && (
        <div className="nl-location-suggest">
          <ul id={listId} role="listbox" aria-label={t('suggestionsLabel')}>
            {items.map((s, i) => (
              <li key={s.placeId} id={`${listId}-${i}`} role="option" aria-selected={i === active} className="nl-location-option"
                onMouseDown={e => { e.preventDefault(); pick(i); }}>
                <span aria-hidden className="nl-location-option-icon">⌖</span>
                <span><strong>{s.main}</strong><span className="nl-location-option-sub">{s.secondary}</span></span>
              </li>
            ))}
          </ul>
          {suggestions.isFetching && items.length === 0 && <p className="nl-location-suggest-note" role="status">{t('searching')}</p>}
          {!suggestions.isFetching && suggestions.isSuccess && items.length === 0 && <p className="nl-location-suggest-note" role="status">{t('noMatch', { q })}</p>}
          {status && (
            <p className="nl-location-suggest-error" role="alert">{status} <button type="button" className="btn btn-ghost" onMouseDown={e => { e.preventDefault(); void suggestions.refetch(); }}>{t('retry')}</button></p>
          )}
          {suggestions.data && (
            <p className="nl-location-suggest-foot">
              <span>{t('poweredBy', { name: suggestions.data.attribution })}</span>
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function Parts({ t, a }: { t: T; a: Address }) {
  return (
    <dl className="nl-location-parts">
      <div><dt>{t('partStreet')}</dt><dd>{a.street}</dd></div>
      <div><dt>{t('partCity')}</dt><dd>{a.city ?? '—'}</dd></div>
      <div><dt>{t('partProvince')}</dt><dd>{a.province ?? '—'}</dd></div>
      <div><dt>{t('partPostal')}</dt><dd>{a.postalCode ?? '—'}</dd></div>
      <div><dt>{t('partPlace')}</dt><dd className="nl-location-placeid">{a.placeId}</dd></div>
    </dl>
  );
}

function SavedParts({ t, l }: { t: T; l: DeliveryLocation }) {
  return (
    <dl className="nl-location-parts">
      <div><dt>{t('partStreet')}</dt><dd>{l.street}</dd></div>
      <div><dt>{t('partCity')}</dt><dd>{l.city ?? '—'}</dd></div>
      <div><dt>{t('partProvince')}</dt><dd>{l.province ?? '—'}</dd></div>
      <div><dt>{t('partPostal')}</dt><dd>{l.postalCode ?? '—'}</dd></div>
      {l.placeId && <div><dt>{t('partPlace')}</dt><dd className="nl-location-placeid">{l.placeId}</dd></div>}
    </dl>
  );
}

/** "Northline opens city by city. Live in {province}: … Pilot: … Waitlist: … An address outside …" from the api's markets. */
function info(t: T, p: Province | undefined): string {
  const cities = (stage: Stage) => (p?.markets ?? []).filter(m => m.stage === stage).map(m => m.city).join(', ');
  const parts = [t('infoIntro')];
  if (p && cities('live')) parts.push(t('infoLive', { province: p.name, cities: cities('live') }));
  if (cities('pilot')) parts.push(t('infoPilot', { cities: cities('pilot') }));
  if (cities('waitlist')) parts.push(t('infoWaitlist', { cities: cities('waitlist') }));
  parts.push(t('infoTail'));
  return parts.join(' ');
}

function Tags({ t, market, zone, province }: { t: T; market: { city: string; stage: Stage } | null; zone: { name: string; runsPerDay?: number | null } | null; province: Province | undefined }) {
  const { locale } = useLocale();
  const tax = province ? t('tax', { rate: (province.taxBps / 100).toLocaleString(locale === 'fr' ? 'fr-CA' : 'en-CA', { maximumFractionDigits: 3 }) }) : null;
  return (
    <div className="nl-location-tags">
      {market && <Tag tone={market.stage === 'live' ? 'accent' : 'neutral'}>{t('tagMarket', { city: market.city, stage: t(market.stage) })}</Tag>}
      {zone && <Tag tone="accent">{t('tagZone', { zone: zone.name })}</Tag>}
      {zone?.runsPerDay ? <Tag tone="neutral">{t('tagRuns', { count: zone.runsPerDay })}</Tag> : null}
      {tax && <Tag tone="neutral">{tax}</Tag>}
    </div>
  );
}

function Waitlist({ t, a }: { t: T; a: Address }) {
  const { user } = useViewer();
  const join = useJoinWaitlist();
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const target = a.resolution.waitlist;
  const pilot = a.resolution.market?.stage === 'pilot';
  const name = target?.name ?? a.city ?? '';
  const serverError = useMemo(() => (join.error instanceof ValidationError ? join.error.byField().email ?? null : null), [join.error]);

  if (!target) return <Alert tone="info" role="status">{t('outside')}</Alert>;
  if (join.isSuccess) {
    return (
      <div className="nl-location-waitlist" role="status">
        <p>{t('joined', { name })}</p>
        <Link to="/" className="btn btn-secondary">{t('keepBrowsing')}</Link>
      </div>
    );
  }
  const submit = () => {
    const value = email.trim();
    if (!user) {
      if (!value) { setError(t('emailRequired')); return; }
      if (!EMAIL.test(value)) { setError(t('emailFormat')); return; }
    }
    setError(null);
    join.mutate({ regionId: target.regionId, ...(value ? { email: value } : {}) });
  };
  return (
    <section className="nl-location-waitlist" aria-labelledby="waitlist-title">
      <h2 id="waitlist-title">{t('notLiveTitle', { name })}</h2>
      <p>{pilot ? t('notLivePilot', { name }) : t('notLiveBody')}</p>
      {!user && (
        <Field label={t('email')} error={error ?? serverError ?? undefined}>
          <TextInput type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder={t('emailPlaceholder')} autoComplete="email" />
        </Field>
      )}
      {join.isError && !(join.error instanceof ValidationError) && <Alert tone="error" role="alert">{t('lookupError')}</Alert>}
      <div className="nl-location-waitlist-actions">
        <Button onClick={submit} disabled={join.isPending}>{t('join')}</Button>
        <Link to="/" className="btn btn-ghost">{t('keepBrowsing')}</Link>
      </div>
    </section>
  );
}
