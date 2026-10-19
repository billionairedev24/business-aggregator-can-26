import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { CaretRight, Crosshair, House, MapPin, MagnifyingGlass, Storefront } from '@phosphor-icons/react';
import { ApiError, http, ValidationError } from '@northline/client';
import { Alert, Button, Dialog, Field, Skeleton, TextInput } from '@northline/ui';
import { useViewer } from '../session/api';
import { addressQuery, newSessionToken, useJoinWaitlist, useMarkets, useSuggestions, type Address, type Market } from './api';
import { useLocationT } from './messages';
import { BookAddress, fromAddress, fromMarket, locateDevice, type Located } from './sources';
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
 * The location dialog (2026-10-19; a bottom sheet on phones), opened from the header's location control, from any
 * `#location` link and from the old /location page: "Use my current location" (the only place the browser is asked
 * for its position — on the press), an address or postal-code search (`/api/v1/geo/autocomplete` → `/places/{id}`,
 * resolved to a market), the signed-in person's saved addresses, and the live markets. Choosing saves the place and
 * closes: every screen follows without leaving the page. A place outside every market offers the waitlist.
 */
export function LocationChooser() {
  const { chooser } = useDeliveryLocation();
  const t = useLocationT();
  return (
    <Dialog open={chooser.open} onClose={chooser.hide} title={t('title')} width={560} dismissible sheetOnPhone className="nl-loc-dialog">
      <ChooserBody onDone={chooser.hide} />
    </Dialog>
  );
}

function ChooserBody({ onDone }: { onDone: () => void }) {
  const t = useLocationT();
  const { location, save, reverse } = useDeliveryLocation();
  const { user } = useViewer();
  const queryClient = useQueryClient();
  const [session, setSession] = useState(() => newSessionToken());
  const [located, setLocated] = useState<Located | 'asking' | null>(null);
  const [outside, setOutside] = useState<Address | null>(null);
  const [resolving, setResolving] = useState(false);
  const [pickError, setPickError] = useState<string | null>(null);

  const useHere = async () => {
    setLocated('asking');
    const geo = typeof navigator !== 'undefined' ? navigator.geolocation : null;
    const r = await locateDevice(geo, reverse);
    if (r.kind === 'found') { save(r.location); onDone(); return; }
    setLocated(r);
  };

  const pickPlace = async (placeId: string) => {
    setResolving(true);
    setPickError(null);
    try {
      const a = await queryClient.fetchQuery(addressQuery(placeId, session));
      const m = a.resolution.market;
      if (m && (m.stage === 'live' || m.stage === 'pilot')) {
        save({
          label: a.label, city: m.city, lat: a.lat, lng: a.lng, placeId: a.placeId, street: a.street,
          ...(a.province ? { province: a.province } : {}), ...(a.postalCode ? { postalCode: a.postalCode } : {}),
          marketId: m.id, ...(a.resolution.zone ? { zoneId: a.resolution.zone.id, zone: a.resolution.zone.name } : {}),
        });
        onDone();
      } else setOutside(a);
    } catch {
      setPickError(t('addressError'));
    } finally {
      setResolving(false);
      setSession(newSessionToken()); // the details call ends the Google session
    }
  };

  return (
    <div className="nl-loc">
      <p className="nl-loc-current"><MapPin size={18} weight="fill" aria-hidden />{current(t, location)}</p>

      <div className="nl-loc-here">
        <Button variant="secondary" onClick={() => void useHere()} disabled={located === 'asking'} aria-busy={located === 'asking' || undefined}>
          <Crosshair size={20} aria-hidden />{located === 'asking' ? t('locating') : t('useHere')}
        </Button>
        {located && located !== 'asking' && located.kind !== 'found' ? (
          <Alert tone={located.kind === 'outside' ? 'highlight' : 'info'} role="status">{hereMessage(t, located)}</Alert>
        ) : null}
      </div>

      <AddressCombobox t={t} session={session} near={location} onChoose={id => void pickPlace(id)} error={pickError} busy={resolving} />
      {outside ? <Waitlist t={t} a={outside} onBack={() => setOutside(null)} /> : null}

      {user ? <SavedAddresses t={t} onPick={a => { save(fromAddress(a)); onDone(); }} /> : null}
      <Markets t={t} onPick={m => { save(fromMarket(m)); onDone(); }} />
    </div>
  );
}

/** "Showing results near {place}" / "We'll use {place} until you choose" — what the control says now, and why. */
function current(t: T, l: DeliveryLocation): string {
  if (l.status === 'locating') return t('currentLocating');
  if (!l.label) return t('currentNone');
  switch (l.source) {
    case 'saved': return t('currentSaved', { place: l.label });
    case 'address': return t('currentAddress', { place: l.label });
    case 'ip': return t('currentIp', { place: l.label });
    default: return t('currentDefault', { place: l.label });
  }
}

function hereMessage(t: T, r: Exclude<Located, { kind: 'found' }>): string {
  switch (r.kind) {
    case 'denied': return t('hereDenied');
    case 'unavailable': return t('hereUnavailable');
    case 'outside': return r.place ? t('hereOutsideNamed', { place: r.place }) : t('hereOutside');
    default: return t('hereError');
  }
}

function AddressCombobox({ t, session, near, onChoose, error, busy }: {
  t: T; session: string; near: DeliveryLocation; onChoose: (placeId: string) => void; error: string | null; busy: boolean;
}) {
  const id = useId();
  const listId = `${id}-list`;
  const [value, setValue] = useState('');
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
    setValue(`${s.main}, ${s.secondary.split(',')[0]}`);
    setOpen(false);
    onChoose(s.placeId);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive(a => Math.min(items.length - 1, a + 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(0, a - 1)); }
    else if (e.key === 'Enter' && showList && active >= 0) { e.preventDefault(); pick(active); }
    else if (e.key === 'Escape' && open) { e.stopPropagation(); setOpen(false); }
  };
  const status = suggestions.isError
    ? (suggestions.error instanceof ApiError && suggestions.error.status === 429 ? t('tooMany') : t('lookupError'))
    : null;

  return (
    <div className="nl-loc-search">
      <MagnifyingGlass size={18} className="nl-loc-search-icon" aria-hidden />
      <Field label={t('search')} error={error ?? undefined}>
          <input ref={inputRef} className="input" type="text" role="combobox" aria-autocomplete="list" aria-expanded={showList}
            aria-controls={listId} aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined} aria-busy={busy || undefined}
            value={value} placeholder={t('searchPlaceholder')} autoComplete="off" maxLength={200}
            onChange={e => { setValue(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)}
            onBlur={() => setTimeout(() => setOpen(false), 150)} onKeyDown={onKey} />
      </Field>
      {busy ? <p className="nl-loc-note" role="status">{t('resolving')}</p> : null}
      {showList && (
        <div className="nl-loc-suggest">
          <ul id={listId} role="listbox" aria-label={t('suggestionsLabel')}>
            {items.map((s, i) => (
              <li key={s.placeId} id={`${listId}-${i}`} role="option" aria-selected={i === active} className="nl-loc-option"
                onMouseDown={e => { e.preventDefault(); pick(i); }}>
                <MapPin size={18} className="nl-loc-option-icon" aria-hidden />
                <span><strong>{s.main}</strong><span className="nl-loc-option-sub">{s.secondary}</span></span>
              </li>
            ))}
          </ul>
          {suggestions.isFetching && items.length === 0 && <p className="nl-loc-note" role="status">{t('searching')}</p>}
          {!suggestions.isFetching && suggestions.isSuccess && items.length === 0 && <p className="nl-loc-note" role="status">{t('noMatch', { q })}</p>}
          {status && (
            <p className="nl-loc-error" role="alert">{status} <button type="button" className="btn btn-ghost btn-sm" onMouseDown={e => { e.preventDefault(); void suggestions.refetch(); }}>{t('retry')}</button></p>
          )}
          {suggestions.data && items.length > 0 && <p className="nl-loc-foot">{t('poweredBy', { name: suggestions.data.attribution })}</p>}
        </div>
      )}
    </div>
  );
}

const Addresses = z.object({ items: z.array(BookAddress) });

function SavedAddresses({ t, onPick }: { t: T; onPick: (a: BookAddress) => void }) {
  const q = useQuery({ queryKey: ['me', 'addresses'], queryFn: async () => (await http('/api/v1/me/addresses', {}, Addresses)).items, staleTime: 60_000 });
  if (q.isPending) return <Skeleton height={56} radius={12} />;
  if (!q.data?.length) return null;
  return (
    <section className="nl-loc-section" aria-labelledby="nl-loc-saved">
      <h3 id="nl-loc-saved" className="nl-loc-h3">{t('savedAddresses')}</h3>
      <ul className="nl-loc-list">
        {q.data.map(a => (
          <li key={a.id}>
            <button type="button" className="nl-loc-row" onClick={() => onPick(a)}>
              <House size={20} className="nl-loc-row-icon" aria-hidden />
              <span className="nl-loc-row-text">
                <span className="nl-loc-row-main">{a.label ? `${a.label} · ${a.street}` : a.street}</span>
                <span className="nl-loc-row-sub">{[a.city, a.province, a.postal].filter(Boolean).join(' ')}{a.isDefault ? ` · ${t('default')}` : ''}</span>
              </span>
              <CaretRight size={16} className="nl-loc-row-go" aria-hidden />
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Markets({ t, onPick }: { t: T; onPick: (m: Market) => void }) {
  const markets = useMarkets();
  const live = useMemo(() => (markets.data?.items ?? []).flatMap(p => p.markets.filter(m => m.stage === 'live').map(m => ({ ...m, provinceName: p.name }))), [markets.data]);
  if (markets.isPending) return <Skeleton height={44} radius={999} />;
  if (!live.length) return null;
  return (
    <section className="nl-loc-section" aria-labelledby="nl-loc-markets">
      <h3 id="nl-loc-markets" className="nl-loc-h3">{t('markets')}</h3>
      <ul className="nl-loc-markets">
        {live.map(m => (
          <li key={m.id}>
            <button type="button" className="nl-chip nl-loc-market" onClick={() => onPick(m)}>
              <Storefront size={16} aria-hidden />{m.city}<span className="nl-loc-market-prov">{m.provinceName}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Waitlist({ t, a, onBack }: { t: T; a: Address; onBack: () => void }) {
  const { user } = useViewer();
  const join = useJoinWaitlist();
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const target = a.resolution.waitlist;
  const pilot = a.resolution.market?.stage === 'pilot';
  const name = target?.name ?? a.city ?? '';
  const serverError = useMemo(() => (join.error instanceof ValidationError ? join.error.byField().email ?? null : null), [join.error]);

  if (!target) return <Alert tone="highlight" role="status">{t('outside')}</Alert>;
  if (join.isSuccess) return <Alert tone="info" role="status">{t('joined', { name })}</Alert>;
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
    <section className="nl-loc-waitlist" aria-labelledby="nl-loc-waitlist">
      <h3 id="nl-loc-waitlist" className="nl-loc-h3">{t('notLiveTitle', { name })}</h3>
      <p>{pilot ? t('notLivePilot', { name }) : t('notLiveBody')}</p>
      {!user && (
        <Field label={t('email')} error={error ?? serverError ?? undefined}>
          <TextInput type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder={t('emailPlaceholder')} autoComplete="email" />
        </Field>
      )}
      {join.isError && !(join.error instanceof ValidationError) && <Alert tone="error" role="alert">{t('lookupError')}</Alert>}
      <div className="nl-loc-waitlist-actions">
        <Button onClick={submit} disabled={join.isPending}>{t('join')}</Button>
        <Button variant="ghost" onClick={onBack}>{t('chooseAnother')}</Button>
      </div>
    </section>
  );
}
