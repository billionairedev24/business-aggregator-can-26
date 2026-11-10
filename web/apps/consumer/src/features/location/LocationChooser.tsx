import { useId, useMemo, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useRouterState } from '@tanstack/react-router';
import { z } from 'zod';
import { Check, Crosshair } from '@phosphor-icons/react';
import { http, ValidationError } from '@northline/client';
import { Alert, Button, Dialog, Field, SiteLink, Skeleton, TextInput, useLocale } from '@northline/ui';
import { useSiteConfig } from '../../lib/config';
import { signInHref, useViewer } from '../session/api';
import { lookUpPostal, useJoinWaitlist, type PostalPlace, type Resolution } from './api';
import { countryName, countryOptions } from './countries';
import { useLocationT } from './messages';
import { BookAddress, fromAddress, headerPlace, locateDevice, type Located, type SavedLocation } from './sources';
import { useDeliveryLocation, type DeliveryLocation } from './useDeliveryLocation';

type T = ReturnType<typeof useLocationT>;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
/** validation-rules.md § Location (S-192): the same rule and messages as the api's `PlaceParams.postalCode`. */
const POSTAL = /^[A-Za-z0-9](?:[A-Za-z0-9 -]*[A-Za-z0-9])?$/;
export const postalSchema = (t: T) => z.string()
  .transform(v => v.trim().replace(/\s+/g, ' ').toUpperCase())
  .pipe(z.string().min(1, t('postalRequired')).max(12, t('postalTooLong')).regex(POSTAL, t('postalFormat')));

/** What the dialog saves for a postal code (S-192): in a live or pilot market its city; elsewhere the code and its point. */
export function fromPostal(p: PostalPlace): SavedLocation {
  const m = p.resolution?.market;
  const served = !!m && (m.stage === 'live' || m.stage === 'pilot');
  const locality = p.city ?? undefined;
  return {
    label: locality ? `${locality} ${p.postalCode}` : p.postalCode,
    postalCode: p.postalCode,
    ...(locality ? { locality } : {}),
    ...(p.country ? { country: p.country } : {}),
    ...(p.lat != null && p.lng != null ? { lat: p.lat, lng: p.lng } : {}),
    ...(served && m ? { city: m.city, province: m.province, marketId: m.id } : {}),
    ...(served && p.resolution?.zone ? { zoneId: p.resolution.zone.id, zone: p.resolution.zone.name } : {}),
  };
}

/**
 * The location dialog (S-192, after Amazon's "Choose your location"; a bottom sheet on phones), opened from the
 * header's "Deliver to", any `#location` link and the old /location page: a line saying delivery depends on the place;
 * signed in, the saved addresses as cards (the chosen one outlined, the default marked) and "Manage addresses";
 * signed out, "Sign in to see your addresses"; "or enter a postal or ZIP code" with Apply (any country:
 * `GET /api/v1/geo/postal`, kept as typed when nobody knows it); "or deliver to another country" (a country alone, no
 * city); "Use my current location" (the only place the browser is asked for its position — on the press); Done.
 * Each choice is saved at once (the page behind follows); Done closes. A location never blocks (S-190): outside every
 * market the place is kept and the pages rank the nearest markets first; a market not open yet offers its waitlist.
 */
export function LocationChooser() {
  const { chooser } = useDeliveryLocation();
  const t = useLocationT();
  return (
    <Dialog open={chooser.open} onClose={chooser.hide} title={t('title')} width={440} dismissible sheetOnPhone className="nl-loc-dialog"
      actions={<Button block onClick={chooser.hide} className="nl-loc-done">{t('done')}</Button>}>
      <ChooserBody onDone={chooser.hide} />
    </Dialog>
  );
}

function ChooserBody({ onDone }: { onDone: () => void }) {
  const t = useLocationT();
  const { locale } = useLocale();
  const { commonCountries } = useSiteConfig();
  const { location, save, reverse } = useDeliveryLocation();
  const { user } = useViewer();
  const href = useRouterState({ select: s => s.location.href });
  const [located, setLocated] = useState<Located | 'asking' | null>(null);
  const [applied, setApplied] = useState<PostalPlace | null>(null);
  const options = useMemo(() => countryOptions(locale, commonCountries ?? undefined), [locale, commonCountries]);
  const place = headerPlace(location, code => countryName(code, locale));

  const useHere = async () => {
    setLocated('asking');
    setApplied(null);
    const geo = typeof navigator !== 'undefined' ? navigator.geolocation : null;
    const r = await locateDevice(geo, reverse);
    if (r.kind === 'found') { save(r.location); onDone(); return; }
    if (r.kind === 'outside' && r.location) save(r.location);
    setLocated(r);
  };

  return (
    <div className="nl-loc">
      <p className="nl-loc-intro">{t('intro')}</p>
      <p className="nl-loc-current" role="status">{current(t, location, place)}</p>

      {user ? <SavedAddresses t={t} location={location} onPick={a => { setApplied(null); setLocated(null); save(fromAddress(a)); }} />
        : <SiteLink href={signInHref(href)} className="btn btn-secondary btn-block nl-loc-signin">{t('signInForAddresses')}</SiteLink>}

      <Divider>{t('orPostal')}</Divider>
      <PostalForm t={t} onApplied={p => { setLocated(null); setApplied(p); save(fromPostal(p)); }} />
      {applied ? <Applied t={t} p={applied} /> : null}

      <Divider>{t('orCountry')}</Divider>
      <CountrySelect t={t} value={location.status !== 'locating' && !location.city && !location.postalCode ? location.country ?? '' : ''}
        options={options} onPick={code => { setApplied(null); setLocated(null); save({ country: code }); }} />

      <div className="nl-loc-here">
        <button type="button" className="btn btn-tertiary btn-sm nl-loc-here-btn" onClick={() => void useHere()} disabled={located === 'asking'} aria-busy={located === 'asking' || undefined}>
          <Crosshair size={18} aria-hidden />{located === 'asking' ? t('locating') : t('useHere')}
        </button>
        {located && located !== 'asking' && located.kind !== 'found' ? (
          <Alert tone="info" role="status">{hereMessage(t, located)}</Alert>
        ) : null}
      </div>
    </div>
  );
}

function Divider({ children }: { children: string }) {
  return <div className="nl-loc-divider" role="separator" aria-label={children}><span aria-hidden>{children}</span></div>;
}

/** What the header says now ("Delivering to Calgary T2T 0B7"), or that everything is shown. */
function current(t: T, l: DeliveryLocation, place: string | undefined): string {
  if (l.status === 'locating') return t('currentLocating');
  if (!place) return t('currentNone');
  return l.source === 'ip' ? t('currentIp', { place }) : t('currentSaved', { place });
}

function hereMessage(t: T, r: Exclude<Located, { kind: 'found' }>): string {
  switch (r.kind) {
    case 'denied': return t('hereDenied');
    case 'unavailable': return t('hereUnavailable');
    case 'outside': return r.place ? t('hereOutsideNamed', { place: r.place }) : t('hereOutside');
    default: return t('hereError');
  }
}

const Addresses = z.object({ items: z.array(BookAddress) });

/** The signed-in person's addresses as cards (Amazon's): name and address, "Default address", the chosen one outlined. */
function SavedAddresses({ t, location, onPick }: { t: T; location: DeliveryLocation; onPick: (a: BookAddress) => void }) {
  const q = useQuery({ queryKey: ['me', 'addresses'], queryFn: async () => (await http('/api/v1/me/addresses', {}, Addresses)).items, staleTime: 60_000 });
  if (q.isPending) return <Skeleton height={72} radius="var(--radius-panel)" />;
  const items = q.data ?? [];
  const chosen = (a: BookAddress) => location.status !== 'locating' && !!location.street && location.street === a.street && location.city === a.city;
  return (
    <section className="nl-loc-section" aria-labelledby="nl-loc-saved">
      <h3 id="nl-loc-saved" className="nl-loc-h3">{t('savedAddresses')}</h3>
      {items.length ? (
        <ul className="nl-loc-cards">
          {items.map(a => (
            <li key={a.id}>
              <button type="button" className="nl-loc-card" aria-pressed={chosen(a)} onClick={() => onPick(a)}>
                <span className="nl-loc-card-main">{a.label ? <strong>{a.label}</strong> : null}<span>{a.street}{a.unit ? `, ${a.unit}` : ''}</span></span>
                <span className="nl-loc-card-sub">{[a.city, a.province, a.postal].filter(Boolean).join(' ')}</span>
                {a.isDefault ? <span className="nl-loc-card-default">{t('defaultAddress')}</span> : null}
                {chosen(a) ? <Check size={18} weight="bold" className="nl-loc-card-check" aria-hidden /> : null}
              </button>
            </li>
          ))}
        </ul>
      ) : <p className="nl-loc-note">{t('noAddresses')}</p>}
      <SiteLink href="/account?tab=addresses" className="nl-loc-manage">{t('manageAddresses')}</SiteLink>
    </section>
  );
}

function PostalForm({ t, onApplied }: { t: T; onApplied: (p: PostalPlace) => void }) {
  const id = useId();
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const parsed = postalSchema(t).safeParse(value);
    if (!parsed.success) { setError(parsed.error.issues[0]?.message ?? t('postalFormat')); return; }
    setError(null);
    setBusy(true);
    try {
      onApplied(await lookUpPostal(parsed.data));
      setValue(parsed.data);
    } catch (err) {
      if (err instanceof ValidationError) setError(err.byField().code ?? err.message);
      // the lookup is down: the code is still a place (S-190, never blocks) — kept as typed
      else onApplied({ postalCode: parsed.data, found: false });
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="nl-loc-postal" onSubmit={e => void submit(e)} noValidate>
      <Field label={t('postal')} error={error}>
        <TextInput id={`${id}-postal`} value={value} onChange={e => setValue(e.target.value)} autoComplete="postal-code" inputMode="text" maxLength={20}
          placeholder={t('postalPlaceholder')} spellCheck={false} autoCapitalize="characters" />
      </Field>
      <Button type="submit" variant="secondary" disabled={busy} aria-busy={busy || undefined} className="nl-loc-apply">{t('apply')}</Button>
    </form>
  );
}

/** After Apply: where the pages now deliver to, and a market that isn't open yet offers its waitlist. */
function Applied({ t, p }: { t: T; p: PostalPlace }) {
  const m = p.resolution?.market;
  const served = !!m && (m.stage === 'live' || m.stage === 'pilot');
  const name = p.city ? `${p.city} ${p.postalCode}` : p.postalCode;
  if (served) return null; // the line at the top already says "Delivering to …"
  return (
    <div className="nl-loc-applied">
      <p className="nl-loc-note" role="status">{t('appliedElsewhere', { place: name })}</p>
      {p.resolution?.waitlist ? <Waitlist t={t} resolution={p.resolution} fallback={p.city ?? p.postalCode} /> : null}
    </div>
  );
}

function CountrySelect({ t, value, options, onPick }: { t: T; value: string; options: ReturnType<typeof countryOptions>; onPick: (code: string) => void }) {
  return (
    <Field label={t('country')}>
      <select className="input nl-loc-country" value={value} onChange={e => { if (e.target.value) onPick(e.target.value); }}>
        <option value="">{t('chooseCountry')}</option>
        <optgroup label={t('commonCountries')}>
          {options.first.map(c => <option key={c.code} value={c.code}>{c.name}</option>)}
        </optgroup>
        <optgroup label={t('allCountries')}>
          {options.rest.map(c => <option key={c.code} value={c.code}>{c.name}</option>)}
        </optgroup>
      </select>
    </Field>
  );
}

function Waitlist({ t, resolution, fallback }: { t: T; resolution: Resolution; fallback: string }) {
  const { user } = useViewer();
  const join = useJoinWaitlist();
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const target = resolution.waitlist;
  const pilot = resolution.market?.stage === 'pilot';
  const name = target?.name ?? fallback;
  const serverError = useMemo(() => (join.error instanceof ValidationError ? join.error.byField().email ?? null : null), [join.error]);

  if (!target) return null;
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
        <Button variant="secondary" onClick={submit} disabled={join.isPending}>{t('join')}</Button>
      </div>
    </section>
  );
}
