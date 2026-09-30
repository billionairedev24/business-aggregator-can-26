import { useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient, useSuspenseQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { ApiError, newIdempotencyKey, ValidationError } from '@northline/client';
import { BrandMark, EmptyState, ErrorState, Field, Skeleton, TextArea, TextInput, useFormatters, useLocale, type Locale } from '@northline/ui';
import { StepUpDialog } from '../cart/StepUpDialog';
import { providerQuery, storefrontQuery, type ProviderFacts, type ProviderService } from '../provider/api';
import { signInHref, useViewer } from '../session/api';
import { percent, rating, shortTime } from '../services/format';
import { useServicesT } from '../services/messages';
import { dyn } from '../services/text';
import { bookingQuery, calendarQuery, confirmBooking, holdSlot, startCheckout, type Checkout, type Confirmation } from './api';
import { homeHours, toRequest, useDraft, VALUES, type Draft } from './draft';
import { useBookingT } from './messages';
import { StripeCard } from './StripeCard';

export type Step = 'details' | 'location' | 'schedule' | 'pay' | 'done';
const ZONE = 'America/Edmonton';
const CLEANING = new Set(['house-cleaning', 'move-in-move-out-clean', 'carpet-and-upholstery', 'window-cleaning', 'duct-cleaning']);

/** The steps of a service's booking type (design 06 `stepKeys`): appointments have no location step. */
export function stepsFor(kind: string): Step[] {
  return kind === 'appointment' ? ['details', 'schedule', 'pay', 'done'] : ['details', 'location', 'schedule', 'pay', 'done'];
}

const dayKey = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);
const addDays = (iso: string, n: number) => { const d = new Date(`${iso}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
const mondayOf = (iso: string) => { const d = new Date(`${iso}T12:00:00Z`); return addDays(iso, -((d.getUTCDay() + 6) % 7)); };

type Kind = ProviderService['kind'];

/** Whether each step's required answers are in (design 06 `detailsIncomplete` / `locIncomplete` / `noSlot`). */
export function complete(step: Step, d: Draft, kind: Kind, vehicle: boolean, cleaning: boolean): boolean {
  switch (step) {
    case 'details':
      if (!d.serviceId) return false;
      if (kind === 'visit') return d.description.trim().length >= 10 && (!vehicle || (!!d.vehicle.year && !!d.vehicle.make && !!d.vehicle.model.trim()));
      if (kind === 'home') return cleaning ? !!d.home.type && !!d.home.beds : !!d.hours;
      if (kind === 'consult') return d.consult.goal !== undefined;
      return true;
    case 'location':
      if (!d.addressLine.trim()) return false;
      if (kind === 'visit' || kind === 'home') return d.spot !== undefined && d.accessNote.trim().length >= 3;
      return true;
    case 'schedule': return !!d.startsAt;
    default: return true;
  }
}

/** design 06 `book`: job details → location & access → schedule → payment → confirmed. */
export function BookingWizard({ slug, step, serviceId, bookingId, onStep }: {
  slug: string; step: Step; serviceId?: string; bookingId?: string; onStep: (step: Step, extra?: { booking?: string }) => void;
}) {
  const t = useBookingT();
  const { locale } = useLocale();
  const { data: facts } = useSuspenseQuery(providerQuery(slug, locale));
  const { data: page } = useSuspenseQuery(storefrontQuery(slug));
  const { draft, update, clear, loaded } = useDraft(slug);
  const bookable = facts.services.filter(s => s.pricingMode !== 'quote' || s.kind === 'consult');
  const chosen = facts.services.find(s => s.id === (draft.serviceId ?? serviceId)) ?? bookable[0];
  const kind: Kind = chosen?.kind ?? facts.kind;
  const vehicle = kind === 'visit' && facts.vehicle && (chosen?.categorySlug ? true : true);
  const cleaning = kind === 'home' && CLEANING.has(chosen?.categorySlug ?? '');
  const steps = stepsFor(kind);
  const current = step === 'done' || steps.includes(step) ? step : 'details';

  if (loaded && !draft.serviceId && chosen && current !== 'done') update({ serviceId: chosen.id });
  if (current === 'done' && bookingId) return <Done slug={slug} facts={facts} bookingId={bookingId} onAgain={() => { clear(); onStep('details'); }} />;
  if (facts.services.length === 0) {
    return <div className="nl-page"><EmptyState action={<Link to="/providers/$slug" params={{ slug }} className="btn btn-secondary">{facts.name}</Link>}>{t('loadError')}</EmptyState></div>;
  }

  const idx = steps.indexOf(current);
  const next = () => onStep(steps[Math.min(steps.length - 1, idx + 1)]!);
  const back = () => onStep(steps[Math.max(0, idx - 1)]!);

  return (
    <div className="nl-page nl-bk">
      <nav aria-label={t('breadcrumb')} className="nl-bk-crumbs">
        <Link to="/services">{t('crumbServices')}</Link> › <Link to="/providers/$slug" params={{ slug }}>{facts.name}</Link> › <span aria-current="page">{t('crumbBook')}</span>
      </nav>
      <div className="nl-bk-split">
        <div className="nl-bk-main">
          <ol className="nl-bk-steps" aria-label={t('steps')}>
            {steps.filter(s => s !== 'done').map((s, i) => (
              <li key={s}>
                <button type="button" className="nl-bk-step" data-state={i < idx ? 'done' : i === idx ? 'current' : 'todo'} aria-current={i === idx ? 'step' : undefined} disabled={i > idx} onClick={() => onStep(s)}>
                  <span className="nl-bk-step-n" aria-hidden>{i + 1}</span>{t(`step_${s}`)}
                </button>
              </li>
            ))}
          </ol>
          {current === 'details' ? <Details facts={facts} draft={draft} update={update} kind={kind} vehicle={vehicle} cleaning={cleaning} services={bookable} onNext={next} categoryHref={facts.category?.slug} /> : null}
          {current === 'location' ? <Location draft={draft} update={update} kind={kind} vehicle={vehicle} onNext={next} onBack={back} /> : null}
          {current === 'schedule' && chosen ? <Schedule slug={slug} facts={facts} service={chosen} draft={draft} update={update} onNext={next} onBack={back} hoursValue={kind === 'home' ? hoursOf(draft, cleaning) : undefined} /> : null}
          {current === 'pay' && chosen ? <Pay facts={facts} service={chosen} draft={draft} update={update} kind={kind} vehicle={vehicle} cleaning={cleaning} onBack={back} onBooked={b => { clear(); onStep('done', { booking: b.bookingId }); }} onHoldGone={() => { update({ hold: undefined, startsAt: undefined }); onStep('schedule'); }} /> : null}
        </div>
        <Summary facts={facts} brand={page.brandColor} service={chosen} draft={draft} kind={kind} vehicle={vehicle} cleaning={cleaning} />
      </div>
    </div>
  );
}

const hoursOf = (d: Draft, cleaning: boolean) => (cleaning ? homeHours(d.home.beds, d.home.addons.length) : d.hours ?? 0);

function Chips({ label, options, value, onChange }: { label: string; options: string[]; value?: number; onChange: (i: number) => void }) {
  return (
    <fieldset className="nl-bk-field">
      <legend className="nl-label">{label}</legend>
      <div className="nl-bk-chips">
        {options.map((o, i) => <button key={o} type="button" className="nl-chip" aria-pressed={value === i} onClick={() => onChange(i)}>{o}</button>)}
      </div>
    </fieldset>
  );
}

function Details({ facts, draft, update, kind, vehicle, cleaning, services, onNext, categoryHref }: {
  facts: ProviderFacts; draft: Draft; update: (p: Partial<Draft>) => void; kind: Kind; vehicle: boolean; cleaning: boolean;
  services: ProviderService[]; onNext: () => void; categoryHref?: string;
}) {
  const t = useBookingT();
  const s = useServicesT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const ok = complete('details', draft, kind, vehicle, cleaning);
  const years = ['2026', '2025', '2024', '2023', '2022', '2021', '2020', '2019', '2018', '2017', '2016', '2015', t('older')];
  const makes = ['Honda', 'Toyota', 'Ford', 'Chevrolet', 'Hyundai', 'Kia', 'Subaru', 'Tesla', t('other')];
  const hint = ok ? t('detailsHint_ok') : kind === 'visit' ? t(vehicle ? 'detailsHint_vehicle' : 'detailsHint_visit') : kind === 'home' ? t('detailsHint_home') : kind === 'consult' ? t('detailsHint_consult') : '';
  const chosen = services.find(x => x.id === draft.serviceId);
  const rate = chosen?.priceCents ?? 0;
  const hours = hoursOf(draft, cleaning);
  return (
    <section aria-labelledby="bk-title">
      <h1 id="bk-title" className="nl-bk-title">{t('detailsTitle')}</h1>
      <p className="nl-bk-lede">{t('detailsLede')}</p>
      <fieldset className="nl-bk-field">
        <legend className="nl-label">{t('service')}</legend>
        <div className="nl-bk-options">
          {services.map(sv => (
            <button key={sv.id} type="button" className="nl-bk-option" aria-pressed={draft.serviceId === sv.id} onClick={() => update({ serviceId: sv.id, hold: undefined, startsAt: undefined })}>
              <span className="nl-bk-option-text"><span className="nl-bk-option-name">{sv.name}</span>{sv.included ? <span className="nl-bk-option-desc">{sv.included}</span> : null}</span>
              <span className="nl-bk-option-price">{sv.pricingMode === 'quote' ? (sv.kind === 'consult' ? s('priceFree') : t('quoteOnly')) : sv.pricingMode === 'hourly' ? s('perHour', { price: money(sv.priceCents ?? 0, { whole: true }) }) : money(sv.priceCents ?? 0, { whole: (sv.priceCents ?? 0) % 100 === 0 })}</span>
            </button>
          ))}
          {facts.quoteable && categoryHref ? (
            <Link to="/services/$category/quote" params={{ category: categoryHref }} className="nl-bk-option">
              <span className="nl-bk-option-text"><span className="nl-bk-option-name">{t('somethingElse')}</span><span className="nl-bk-option-desc">{t('somethingElseDesc')}</span></span>
              <span className="nl-bk-option-price">{t('quoteOnly')}</span>
            </Link>
          ) : null}
        </div>
      </fieldset>
      {kind !== 'appointment' && kind !== 'consult' ? (
        <Field label={t('describe', { count: draft.description.length })}>
          <TextArea value={draft.description} maxLength={500} placeholder={t('describePh')} onChange={e => update({ description: e.target.value.slice(0, 500) })} rows={4} />
        </Field>
      ) : null}
      {kind === 'visit' || kind === 'home' ? <Chips label={t('urgency')} options={[0, 1, 2, 3].map(i => t(`urg_${i}` as 'urg_0'))} value={draft.urgency} onChange={i => update({ urgency: i })} /> : null}
      {kind === 'visit' && vehicle ? (
        <section aria-labelledby="bk-vehicle" className="nl-bk-section">
          <h2 id="bk-vehicle" className="nl-bk-h2">{t('vehicle')}</h2>
          <div className="nl-bk-grid">
            <Field label={t('year')}><select className="input" value={draft.vehicle.year} onChange={e => update({ vehicle: { ...draft.vehicle, year: e.target.value } })}><option value="">{t('select')}</option>{years.map(y => <option key={y}>{y}</option>)}</select></Field>
            <Field label={t('make')}><select className="input" value={draft.vehicle.make} onChange={e => update({ vehicle: { ...draft.vehicle, make: e.target.value } })}><option value="">{t('select')}</option>{makes.map(m => <option key={m}>{m}</option>)}</select></Field>
            <Field label={t('model')}><TextInput value={draft.vehicle.model} placeholder={t('modelPh')} maxLength={80} onChange={e => update({ vehicle: { ...draft.vehicle, model: e.target.value } })} /></Field>
            <Field label={t('plate')}><TextInput value={draft.vehicle.plate} placeholder={t('platePh')} maxLength={12} onChange={e => update({ vehicle: { ...draft.vehicle, plate: e.target.value } })} /></Field>
          </div>
          <Chips label={t('fuel')} options={[0, 1, 2, 3].map(i => t(`fuel_${i}` as 'fuel_0'))} value={draft.vehicle.fuel} onChange={i => update({ vehicle: { ...draft.vehicle, fuel: i } })} />
        </section>
      ) : null}
      {kind === 'home' && cleaning ? (
        <section aria-labelledby="bk-home" className="nl-bk-section">
          <h2 id="bk-home" className="nl-bk-h2">{t('yourHome')}</h2>
          <div className="nl-bk-grid">
            <Field label={t('homeType')}><select className="input" value={draft.home.type} onChange={e => update({ home: { ...draft.home, type: e.target.value } })}><option value="">{t('select')}</option>{VALUES.home.map((v, i) => <option key={v} value={v}>{t(`home_${i}` as 'home_0')}</option>)}</select></Field>
            <Field label={t('beds')}><select className="input" value={draft.home.beds} onChange={e => update({ home: { ...draft.home, beds: e.target.value } })}><option value="">{t('select')}</option>{['1', '2', '3', '4+'].map(v => <option key={v}>{v}</option>)}</select></Field>
            <Field label={t('baths')}><select className="input" value={draft.home.baths} onChange={e => update({ home: { ...draft.home, baths: e.target.value } })}>{['1', '2', '3+'].map(v => <option key={v}>{v}</option>)}</select></Field>
            <Field label={t('size')}><select className="input" value={draft.home.size} onChange={e => update({ home: { ...draft.home, size: e.target.value } })}>{VALUES.size.map((v, i) => <option key={v} value={v}>{t(`size_${i}` as 'size_0')}</option>)}</select></Field>
          </div>
          <fieldset className="nl-bk-field"><legend className="nl-label">{t('addons')}</legend><div className="nl-bk-chips">
            {VALUES.addons.map((v, i) => <button key={v} type="button" className="nl-chip" aria-pressed={draft.home.addons.includes(i)} onClick={() => update({ home: { ...draft.home, addons: draft.home.addons.includes(i) ? draft.home.addons.filter(x => x !== i) : [...draft.home.addons, i] } })}>{t(`addon_${i}` as 'addon_0')}</button>)}
          </div></fieldset>
          <Chips label={t('pets')} options={[0, 1, 2, 3].map(i => t(`pet_${i}` as 'pet_0'))} value={draft.home.pets} onChange={i => update({ home: { ...draft.home, pets: i } })} />
          {hours > 0 ? <p className="nl-bk-note">{t('estimate', { hours: hours.toFixed(1), total: money(Math.round(rate * hours)), rate: money(rate, { whole: true }) })}</p> : null}
        </section>
      ) : null}
      {kind === 'home' && !cleaning ? (
        <Field label={t('hours')}>
          <select className="input nl-bk-narrow" value={draft.hours ?? ''} onChange={e => update({ hours: e.target.value ? Number(e.target.value) : undefined })}>
            <option value="">{t('select')}</option>
            {[1, 1.5, 2, 2.5, 3, 4, 5, 6, 8].map(h => <option key={h} value={h}>{t('hoursValue', { hours: h.toLocaleString(locale === 'fr' ? 'fr-CA' : 'en-CA') })}</option>)}
          </select>
        </Field>
      ) : null}
      {kind === 'appointment' ? (
        <section aria-labelledby="bk-appt" className="nl-bk-section">
          <h2 id="bk-appt" className="nl-bk-h2">{t('appointmentTitle')}</h2>
          <Field label={t('stylistNote')}><TextInput value={draft.stylistNote} placeholder={t('stylistPh')} maxLength={300} onChange={e => update({ stylistNote: e.target.value })} /></Field>
          <p className="nl-bk-note">{t('atTheirPlace', { address: facts.city ?? '' })}</p>
        </section>
      ) : null}
      {kind === 'consult' ? (
        <section aria-labelledby="bk-consult" className="nl-bk-section">
          <h2 id="bk-consult" className="nl-bk-h2">{t('consultTitle')}</h2>
          <Chips label={t('goal')} options={[0, 1, 2, 3].map(i => t(`goal_${i}` as 'goal_0'))} value={draft.consult.goal} onChange={i => update({ consult: { ...draft.consult, goal: i } })} />
          <div className="nl-bk-grid">
            <Field label={t('timeline')}><select className="input" value={draft.consult.timeline ?? ''} onChange={e => update({ consult: { ...draft.consult, timeline: e.target.value === '' ? undefined : Number(e.target.value) } })}><option value="">{t('select')}</option>{[0, 1, 2, 3].map(i => <option key={i} value={i}>{t(`tl_${i}` as 'tl_0')}</option>)}</select></Field>
            <Field label={t('priceRange')}><select className="input" value={draft.consult.priceRange ?? ''} onChange={e => update({ consult: { ...draft.consult, priceRange: e.target.value === '' ? undefined : Number(e.target.value) } })}><option value="">{t('select')}</option>{[0, 1, 2, 3].map(i => <option key={i} value={i}>{t(`pr_${i}` as 'pr_0')}</option>)}</select></Field>
            <Field label={t('areas')}><TextInput value={draft.consult.areas} placeholder={t('areasPh')} maxLength={120} onChange={e => update({ consult: { ...draft.consult, areas: e.target.value } })} /></Field>
            <Field label={t('preapproved')}><select className="input" value={draft.consult.preapproved ?? ''} onChange={e => update({ consult: { ...draft.consult, preapproved: e.target.value === '' ? undefined : Number(e.target.value) } })}><option value="">{t('select')}</option>{[0, 1, 2].map(i => <option key={i} value={i}>{t(`pa_${i}` as 'pa_0')}</option>)}</select></Field>
          </div>
          <Chips label={t('meeting')} options={[0, 1, 2].map(i => t(`meet_${i}` as 'meet_0'))} value={draft.consult.meeting} onChange={i => update({ consult: { ...draft.consult, meeting: i } })} />
          <p className="nl-bk-note">{t('consultFree')}</p>
        </section>
      ) : null}
      <div className="nl-bk-actions">
        <button type="button" className="btn btn-primary nl-bk-primary" disabled={!ok} onClick={onNext}>{kind === 'appointment' ? t('detailsCta_appointment') : t('detailsCta')}</button>
        {hint ? <span className="nl-bk-hint" aria-live="polite">{hint}</span> : null}
      </div>
      <span hidden>{percent(0, locale)}{rating(0, locale)}</span>
    </section>
  );
}

function Location({ draft, update, kind, vehicle, onNext, onBack }: { draft: Draft; update: (p: Partial<Draft>) => void; kind: Kind; vehicle: boolean; onNext: () => void; onBack: () => void }) {
  const t = useBookingT();
  const ok = complete('location', draft, kind, vehicle, false);
  const spotLabel = kind === 'visit' && vehicle ? t('spot_visit_vehicle') : kind === 'home' ? t('spot_home') : t('spot_other');
  const spots = vehicle ? [0, 1, 2, 3].map(i => t(`spotv_${i}` as 'spotv_0')) : [0, 1, 2, 3].map(i => t(`spoth_${i}` as 'spoth_0'));
  return (
    <section aria-labelledby="bk-loc">
      <h1 id="bk-loc" className="nl-bk-title">{t('locationTitle')}</h1>
      <p className="nl-bk-lede">{vehicle ? t('locationLede_vehicle') : t('locationLede')}</p>
      <div className="nl-bk-grid nl-bk-grid-address">
        <Field label={t('address')}><TextInput value={draft.addressLine} placeholder={t('addressPh')} maxLength={200} autoComplete="street-address" onChange={e => update({ addressLine: e.target.value })} /></Field>
        <Field label={t('unit')}><TextInput value={draft.unit} placeholder={t('unitPh')} maxLength={40} onChange={e => update({ unit: e.target.value })} /></Field>
      </div>
      {kind === 'visit' || kind === 'home' ? <Chips label={spotLabel} options={spots} value={draft.spot} onChange={i => update({ spot: i })} /> : null}
      <Field label={t('access')} hint={t('accessPrivate')}>
        <TextArea value={draft.accessNote} maxLength={500} rows={3} placeholder={t('accessPh')} onChange={e => update({ accessNote: e.target.value })} />
      </Field>
      <Chips label={t('present')} options={[0, 1, 2].map(i => t(`present_${i}` as 'present_0'))} value={draft.present} onChange={i => update({ present: i })} />
      <div className="nl-bk-grid">
        <Field label={t('phone')}><TextInput type="tel" value={draft.contactPhone} placeholder={t('phonePh')} maxLength={20} autoComplete="tel" onChange={e => update({ contactPhone: e.target.value })} /></Field>
        <Field label={t('contactPref')}><select className="input" value={draft.contactPref} onChange={e => update({ contactPref: Number(e.target.value) })}>{[0, 1, 2].map(i => <option key={i} value={i}>{t(`pref_${i}` as 'pref_0')}</option>)}</select></Field>
      </div>
      <div className="nl-bk-actions">
        <button type="button" className="btn btn-primary nl-bk-primary" disabled={!ok} onClick={onNext}>{t('locCta')}</button>
        <button type="button" className="btn btn-ghost" onClick={onBack}>{t('back')}</button>
      </div>
    </section>
  );
}

function Schedule({ slug, facts, service, draft, update, onNext, onBack, hoursValue }: {
  slug: string; facts: ProviderFacts; service: ProviderService; draft: Draft; update: (p: Partial<Draft>) => void; onNext: () => void; onBack: () => void; hoursValue?: number;
}) {
  const t = useBookingT();
  const { locale } = useLocale();
  const { date } = useFormatters();
  const viewer = useViewer();
  const qc = useQueryClient();
  const today = dayKey(new Date());
  const [week, setWeek] = useState(() => mondayOf(draft.day ?? today));
  const cal = useQuery(calendarQuery(slug, service.id, week, 7));
  const [error, setError] = useState<string>();
  const [holding, setHolding] = useState(false);
  const selectedDay = cal.data?.days.find(d => d.date === draft.day);
  const range = `${date(`${week}T18:00:00Z`)}–${date(`${addDays(week, 6)}T18:00:00Z`)}`;
  const here = typeof window === 'undefined' ? `/providers/${slug}/book` : `${window.location.pathname}${window.location.search}`;

  const cont = async () => {
    if (!draft.startsAt) return;
    setHolding(true); setError(undefined);
    try {
      const hold = await holdSlot({ slug, serviceId: service.id, startsAt: draft.startsAt, hours: hoursValue || undefined });
      update({ hold: { holdId: hold.holdId, bookingId: hold.bookingId, startsAt: hold.startsAt, expiresAt: hold.expiresAt } });
      onNext();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setError(t('slotTaken'));
        update({ startsAt: undefined, hold: undefined });
        void qc.invalidateQueries({ queryKey: ['booking', 'calendar', slug] });
      } else setError(e instanceof ValidationError ? e.errors[0]?.message : t('genericError'));
    } finally { setHolding(false); }
  };

  return (
    <section aria-labelledby="bk-sched">
      <h1 id="bk-sched" className="nl-bk-title">{t('scheduleTitle')}</h1>
      <p className="nl-bk-lede">{t('scheduleLede', { name: facts.name })}</p>
      <div className="nl-bk-week">
        <button type="button" className="btn btn-ghost" disabled={week <= mondayOf(today)} onClick={() => setWeek(addDays(week, -7))}>{t('prevWeek')}</button>
        <span className="nl-bk-week-label">{week === mondayOf(today) ? t('thisWeek', { range }) : range}</span>
        <button type="button" className="btn btn-ghost" onClick={() => setWeek(addDays(week, 7))}>{t('nextWeek')}</button>
      </div>
      {cal.isPending ? <div className="nl-bk-days">{Array.from({ length: 7 }, (_, i) => <Skeleton key={i} height={64} />)}</div>
        : cal.isError ? <ErrorState message={t('calendarError')} onRetry={() => void cal.refetch()} />
          : (
            <>
              <div className="nl-bk-days" role="group" aria-label={t('step_schedule')}>
                {cal.data.days.map(d => {
                  const at = new Date(`${d.date}T18:00:00Z`);
                  const off = d.free === 0;
                  return (
                    <button key={d.date} type="button" className="nl-bk-day" aria-pressed={draft.day === d.date} disabled={off} onClick={() => update({ day: d.date, startsAt: undefined })}>
                      <span className="nl-bk-dow">{new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { weekday: 'short', timeZone: ZONE }).format(at)}</span>
                      <span className="nl-bk-dnum">{Number(d.date.slice(8))}</span>
                      <span className="nl-bk-dfree">{d.closed ? t('dayClosed') : t('dayFree', { count: d.free })}</span>
                    </button>
                  );
                })}
              </div>
              {selectedDay ? (
                <div className="nl-bk-slots" role="group" aria-label={t('slotsFor', { day: date(`${selectedDay.date}T18:00:00Z`, 'long') })}>
                  {selectedDay.slots.map(s => (
                    <button key={s.startsAt} type="button" className="nl-chip nl-bk-slot" aria-pressed={draft.startsAt === s.startsAt} disabled={!s.free} onClick={() => update({ startsAt: s.startsAt })}>{shortTime(new Date(s.startsAt), locale)}</button>
                  ))}
                </div>
              ) : <p className="nl-bk-muted">{t('noDay')}</p>}
            </>
          )}
      <Chips label={t('flexibility')} options={[0, 1, 2].map(i => t(`flex_${i}` as 'flex_0'))} value={draft.flexibility} onChange={i => update({ flexibility: i })} />
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
      {!viewer.loading && !viewer.user && draft.startsAt ? (
        <div className="nl-bk-signin" role="note">
          <span>{t('signInToBook')}</span>
          <a className="btn btn-primary" href={signInHref(here)}>{t('signIn')}</a>
          <a className="btn btn-secondary" href={signInHref(here, 'register')}>{t('createAccount')}</a>
        </div>
      ) : null}
      <div className="nl-bk-actions">
        <button type="button" className="btn btn-primary nl-bk-primary" disabled={!draft.startsAt || holding || !viewer.user} aria-busy={holding} onClick={() => void cont()}>{holding ? t('holding') : t('toPayment')}</button>
        <button type="button" className="btn btn-ghost" onClick={onBack}>{t('back')}</button>
      </div>
    </section>
  );
}

type PayPhase = { step: 'form' } | { step: 'stepUp'; mode: 'required' | 'enrol' } | { step: 'card'; checkout: Checkout };

function Pay({ facts, service, draft, update, kind, vehicle, cleaning, onBack, onBooked, onHoldGone }: {
  facts: ProviderFacts; service: ProviderService; draft: Draft; update: (p: Partial<Draft>) => void; kind: Kind; vehicle: boolean; cleaning: boolean;
  onBack: () => void; onBooked: (b: Confirmation) => void; onHoldGone: () => void;
}) {
  const t = useBookingT();
  const { money, date } = useFormatters();
  const { locale } = useLocale();
  const [phase, setPhase] = useState<PayPhase>({ step: 'form' });
  const [error, setError] = useState<string>();
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const keys = useRef<{ body: string; key: string } | null>(null);
  const hold = draft.hold;
  const free = kind === 'consult';
  const estimate = estimateOf(service, draft, cleaning);
  const total = free ? 0 : estimate + Math.round(estimate * 0.05);
  const cancelBy = hold ? new Date(new Date(hold.startsAt).getTime() - 12 * 3_600_000) : null;

  const finish = async (checkout: Checkout) => {
    try {
      const booked = checkout.booking ?? await confirmBooking(checkout.holdId, `confirm-${checkout.holdId}`);
      onBooked(booked);
    } catch (e) { fail(e); }
  };
  const fail = (e: unknown) => {
    if (e instanceof ApiError && e.status === 409 && (e.body as { code?: string } | undefined)?.code === 'hold_expired') { setError(t('holdExpired')); onHoldGone(); return; }
    if (e instanceof ValidationError) { setFieldErrors(e.byField()); setError(e.errors[0]?.message); return; }
    const code = e instanceof ApiError ? (e.body as { code?: string } | undefined)?.code : undefined;
    if (code === 'step_up_required' || code === 'second_factor_required') { setPhase({ step: 'stepUp', mode: code === 'step_up_required' ? 'required' : 'enrol' }); return; }
    setError(t('genericError'));
  };
  const pay = async (proof?: string) => {
    if (!hold) return;
    setBusy(true); setError(undefined); setFieldErrors({});
    const body = toRequest(draft, hold.holdId, service.id, { vehicle, kind, cleaning });
    const text = JSON.stringify(body);
    if (!keys.current || keys.current.body !== text) keys.current = { body: text, key: newIdempotencyKey() };
    try {
      const checkout = await startCheckout(body, keys.current.key, proof);
      if (checkout.status === 'requires_action' || checkout.status === 'requires_payment_method') {
        if (checkout.clientSecret && checkout.publishableKey) setPhase({ step: 'card', checkout });
        else setError(t('genericError'));
      } else await finish(checkout);
    } catch (e) { fail(e); } finally { setBusy(false); }
  };

  return (
    <section aria-labelledby="bk-pay">
      <h1 id="bk-pay" className="nl-bk-title">{t('payTitle')}</h1>
      <p className="nl-bk-lede">{free ? t('payLedeFree') : t('payLede', { name: facts.name })}</p>
      {hold ? <p className="nl-bk-muted">{t('heldUntil', { time: date(hold.expiresAt, 'time') })}</p> : null}
      {!free ? (
        <>
          <h2 className="nl-bk-h2">{t('paymentMethod')}</h2>
          {phase.step === 'card' ? (
            <StripeCard clientSecret={phase.checkout.clientSecret!} publishableKey={phase.checkout.publishableKey!} label={t('confirmCard', { total: money(phase.checkout.totalCents) })}
              onAuthorized={() => void finish(phase.checkout)} onError={setError} />
          ) : (
            <div className="nl-bk-card">
              <div className="nl-bk-card-head"><span className="nl-bk-kicker">{t('fakeKicker')}</span><span className="nl-bk-muted">{t('fakeNote')}</span></div>
              <p className="nl-bk-muted">{t('stripeNote')}</p>
            </div>
          )}
          <h2 className="nl-bk-h2">{t('policies')}</h2>
          <div className="nl-bk-policies">
            <label className="nl-bk-check"><input type="checkbox" checked={draft.agreePolicies} onChange={e => update({ agreePolicies: e.target.checked })} /><span>{t('agreePolicies', { cancelBy: cancelBy ? date(cancelBy, 'dateTime') : '' })}</span></label>
            {fieldErrors.agreePolicies ? <span className="nl-error">{fieldErrors.agreePolicies}</span> : null}
            <label className="nl-bk-check"><input type="checkbox" checked={draft.agreeTerms} onChange={e => update({ agreeTerms: e.target.checked })} /><span>{t('agreeTerms')}</span></label>
            {fieldErrors.agreeTerms ? <span className="nl-error">{fieldErrors.agreeTerms}</span> : null}
          </div>
        </>
      ) : null}
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
      {phase.step !== 'card' ? (
        <div className="nl-bk-actions">
          <button type="button" className="btn btn-primary nl-bk-primary" disabled={busy || !hold || (!free && !(draft.agreePolicies && draft.agreeTerms))} aria-busy={busy} onClick={() => void pay()}>
            {busy ? t('paying') : free ? t('confirmFree') : t('hold', { total: money(total) })}
          </button>
          <button type="button" className="btn btn-ghost" onClick={onBack}>{t('back')}</button>
        </div>
      ) : null}
      {phase.step === 'stepUp' ? <StepUpDialog mode={phase.mode} onClose={() => setPhase({ step: 'form' })} onProof={proof => { setPhase({ step: 'form' }); void pay(proof); }} /> : null}
      <span hidden lang={locale}>{null}</span>
    </section>
  );
}

function estimateOf(service: ProviderService, draft: Draft, cleaning: boolean): number {
  if (service.pricingMode === 'quote') return 0;
  const price = service.priceCents ?? 0;
  if (service.pricingMode === 'hourly') return Math.round(price * (hoursOf(draft, cleaning) || 1));
  return price;
}

function Summary({ facts, brand, service, draft, kind, vehicle, cleaning }: { facts: ProviderFacts; brand: string; service?: ProviderService; draft: Draft; kind: Kind; vehicle: boolean; cleaning: boolean }) {
  const t = useBookingT();
  const s = useServicesT();
  const { money, date } = useFormatters();
  const { locale } = useLocale();
  const estimate = service ? estimateOf(service, draft, cleaning) : 0;
  const free = kind === 'consult';
  const tax = Math.round(estimate * 0.05);
  const row = (label: ReactNode, value: ReactNode) => <div className="nl-bk-sum-row"><dt>{label}</dt><dd>{value}</dd></div>;
  const vehicleText = [draft.vehicle.year, draft.vehicle.make, draft.vehicle.model].filter(Boolean).join(' ') + (draft.vehicle.plate ? ` · ${draft.vehicle.plate}` : '');
  const second = kind === 'visit' ? (vehicle ? vehicleText : '') : kind === 'home' ? (draft.home.type ? `${draft.home.type} · ${draft.home.beds || '?'}` : '') : kind === 'consult' && draft.consult.goal !== undefined ? t(`goal_${draft.consult.goal}` as 'goal_0') : '';
  const when = draft.startsAt ? date(draft.startsAt, 'dateTime') : t('dash');
  const meta = [dyn(s, `tier_${facts.tier}`, facts.tier), facts.reviewCount > 0 ? `★ ${rating(facts.rating, locale)} (${facts.reviewCount})` : null, facts.onTimePct != null ? s('onTime', { pct: percent(facts.onTimePct, locale) }) : null].filter(Boolean).join(' · ');
  return (
    <aside className="nl-bk-aside" aria-label={facts.name}>
      <div className="nl-bk-who"><BrandMark name={facts.name} color={brand} size={44} /><div><div className="nl-bk-who-name">{facts.name}</div><div className="nl-bk-muted">{meta}</div></div></div>
      <dl className="nl-bk-sum">
        {row(t('sumService'), service?.name ?? t('dash'))}
        {kind !== 'appointment' ? row(t('sumProblem'), draft.description ? (draft.description.length > 60 ? `${draft.description.slice(0, 60)}…` : draft.description) : t('dash')) : null}
        {row(t(`sum_${kind}`), second || t('dash'))}
        {kind !== 'appointment' ? row(t('sumWhere'), draft.addressLine || t('dash')) : null}
        {row(t('sumWhen'), when)}
      </dl>
      <hr className="nl-bk-rule" />
      <dl className="nl-bk-sum">
        {row(service?.name ?? t('sumService'), free ? s('priceFree') : estimate ? money(estimate) : t('dash'))}
        {kind === 'visit' || kind === 'home' ? row(t('travel'), t('included')) : null}
        {!free ? row(t('gst'), money(tax)) : null}
        <div className="nl-bk-sum-row nl-bk-total"><dt>{free ? t('nothingToPay') : t('heldInEscrow')}</dt><dd>{money(free ? 0 : estimate + tax)}</dd></div>
      </dl>
      {!free ? <p className="nl-bk-muted">{t('releasedAfter')}</p> : null}
    </aside>
  );
}

function Done({ slug, facts, bookingId, onAgain }: { slug: string; facts: ProviderFacts; bookingId: string; onAgain: () => void }) {
  const t = useBookingT();
  const { money, date } = useFormatters();
  const navigate = useNavigate();
  const q = useQuery(bookingQuery(bookingId));
  const b = q.data;
  return (
    <div className="nl-page nl-bk">
      {q.isPending ? <Skeleton height={200} /> : q.isError || !b ? <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /> : (
        <section aria-labelledby="bk-done" className="nl-bk-done">
          <svg width="64" height="64" viewBox="0 0 72 72" aria-hidden="true"><circle cx="36" cy="36" r="34" fill="var(--color-accent-100)" stroke="var(--color-accent)" strokeWidth="2" /><path d="M22 37 L32 47 L51 27" fill="none" stroke="var(--color-accent-700)" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" /></svg>
          <h1 id="bk-done" className="nl-bk-title">{b.type === 'appointment' ? t('doneTitleShop', { when: date(b.startsAt, 'dateTime') }) : t('doneTitle', { who: b.memberFirstName ?? b.providerName, when: date(b.startsAt, 'dateTime') })}</h1>
          <p className="nl-bk-lede">{b.heldCents > 0 ? t('doneBody', { ref: b.ref, total: money(b.heldCents), name: b.providerName }) : t('doneBodyFree', { ref: b.ref, name: b.providerName })}</p>
          <h2 className="nl-bk-h2">{t('nextTitle')}</h2>
          <ul className="nl-bk-next">{[0, 1, 2, 3, 4].map(i => <li key={i}>{t(`next_${i}` as 'next_0', { name: b.providerName })}</li>)}</ul>
          <div className="nl-bk-actions">
            <Link to="/account/orders" className="btn btn-primary">{t('seeBookings')}</Link>
            <button type="button" className="btn btn-ghost" onClick={() => { onAgain(); void navigate({ to: '/providers/$slug', params: { slug } }); }}>{t('bookElse')}</button>
          </div>
          <span hidden>{facts.slug}</span>
        </section>
      )}
    </div>
  );
}

export function BookingSkeleton() {
  return (
    <div className="nl-page nl-bk" aria-busy="true">
      <Skeleton width={260} height={12} />
      <div className="nl-bk-split">
        <div className="nl-bk-main"><Skeleton width="60%" height={36} style={{ marginTop: 16 }} /><Skeleton height={60} style={{ marginTop: 14 }} />{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} height={52} style={{ marginTop: 8 }} />)}</div>
        <Skeleton height={320} radius="var(--radius-md)" />
      </div>
    </div>
  );
}

export const _internal = { useMemo };
