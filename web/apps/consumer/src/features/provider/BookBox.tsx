import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { CaretLeft, CaretRight, Lock, MapPin, Wrench } from '@phosphor-icons/react';
import { addDays, ChoiceTiles, ErrorState, Skeleton, useFormatters, useLocale, type Locale } from '@northline/ui';
import { siteHref, type PageHost } from '../../lib/pages';
import { useRenderNow } from '../../lib/renderClock';
import { calendarQuery } from '../booking/api';
import { seedDraft } from '../booking/draft';
import { useZone } from '../location/regions';
import { percent, price } from '../services/format';
import { useServicesT } from '../services/messages';
import { clock } from '../shop/format';
import type { ProviderFacts, ProviderService } from './api';
import { useProviderT } from './messages';

/** What the booking box can book by time: priced services (a consult is free); quoted ones are asked for a quote. */
export const bookable = (s: ProviderService) => s.pricingMode !== 'quote' || s.kind === 'consult';

export interface BookingChoice {
  service: ProviderService | undefined;
  setService: (id: string) => void;
  day: string | undefined;
  setDay: (day: string) => void;
  startsAt: string | undefined;
  setStartsAt: (at: string) => void;
}

/** The page's one choice — the service (the first bookable one at first), then a day and a time. */
export function useBookingChoice(facts: ProviderFacts): BookingChoice {
  const [serviceId, setServiceId] = useState(() => (facts.services.find(bookable) ?? facts.services[0])?.id);
  const [day, setDay] = useState<string>();
  const [startsAt, setStartsAt] = useState<string>();
  return {
    service: facts.services.find(s => s.id === serviceId),
    setService: id => { setServiceId(id); setDay(undefined); setStartsAt(undefined); },
    day, setDay: d => { setDay(d); setStartsAt(undefined); },
    startsAt, setStartsAt,
  };
}

const DAYS = 5;
const INTL: Record<Locale, string> = { en: 'en-CA', fr: 'fr-CA' };
const dayKey = (d: Date, zone: string) => new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);
/** A calendar date (YYYY-MM-DD) in words, never shifted by a zone: "Sat", "Saturday, Oct 24", "October 2026". */
const dayText = (date: string, locale: Locale, opts: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(INTL[locale], { ...opts, timeZone: 'UTC' }).format(new Date(`${date}T12:00:00Z`));

/**
 * The booking box (E-15 S-172, design/revamp/a.html "Book a time"): the chosen service, the month, five days as
 * one-choice tiles (a day with nothing free is disabled) with the next/previous five, that day's times as one-choice
 * tiles (taken ones struck through), where the job happens, the price with the travel and the tax estimate, then "Book
 * <day> at <time>" — which opens the booking wizard with the choice made — and the no-charge note. A quoted service asks
 * for a quote instead. The calendar is the public `slots` read, fetched in the browser; "today" is the render clock's.
 * Phones: the price, the time and Book in a sticky bottom bar.
 */
export function BookBox({ facts, choice, pageHost, siteOrigin }: { facts: ProviderFacts; choice: BookingChoice; pageHost: PageHost | null; siteOrigin: string }) {
  const t = useProviderT();
  const s = useServicesT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const navigate = useNavigate();
  const zone = useZone(facts.city);
  const today = dayKey(useRenderNow(), zone);
  const [from, setFrom] = useState(today);
  const service = choice.service;
  const timed = !!service && bookable(service);
  const cal = useQuery({ ...calendarQuery(facts.slug, service?.id ?? '', from, DAYS), enabled: timed });
  const calZone = cal.data?.timeZone ?? zone;
  const days = cal.data?.days ?? [];
  const selected = days.find(d => d.date === choice.day);
  const comes = facts.kind === 'visit' || facts.kind === 'home';
  const cents = service?.pricingMode === 'quote' ? 0 : service?.priceCents ?? 0;
  const tax = Math.round((cents * facts.taxBps) / 10_000);
  const when = choice.startsAt && choice.day ? t('bookAt', { day: dayText(choice.day, locale, { weekday: 'short', month: 'short', day: 'numeric' }), time: clock(choice.startsAt, locale, calZone) }) : null;
  const book = () => {
    if (!service || !choice.startsAt) { document.getElementById('prov-book')?.scrollIntoView({ block: 'start' }); return; }
    const path = `/providers/${facts.slug}/book?service=${encodeURIComponent(service.id)}`;
    if (pageHost) { window.location.href = siteHref(pageHost, siteOrigin, path); return; }
    seedDraft(facts.slug, { serviceId: service.id, day: choice.day, startsAt: choice.startsAt, timeZone: calZone, cancellation: cal.data?.cancellation });
    void navigate({ to: '/providers/$slug/book', params: { slug: facts.slug }, search: { service: service.id } });
  };
  const priceText = service ? price(s, locale, service.pricingMode, service.priceCents, service.pricingMode === 'hourly') : '';

  return (
    <>
      <aside className="nl-prov-cta nl-prov-book" id="prov-book" aria-labelledby="prov-book-title">
        <h2 id="prov-book-title" className="nl-prov-cta-title">{t('bookTitle')}</h2>
        {service ? (
          <div className="nl-prov-chosen">
            <Wrench size={16} aria-hidden className="nl-prov-chosen-icon" />
            <span><strong>{service.name}</strong><span className="nl-prov-muted">{[t('minutes', { count: service.durationMin }), priceText].join(' · ')}</span></span>
          </div>
        ) : null}
        {!timed ? (
          <>
            <p className="nl-prov-text">{t('quotedService')}</p>
            {facts.category ? <QuoteLink facts={facts} category={facts.category.slug} pageHost={pageHost} siteOrigin={siteOrigin} className="btn btn-primary btn-lg btn-block">{t('cta_request_quote')}</QuoteLink> : null}
          </>
        ) : (
          <>
            <div className="nl-prov-month">
              <span className="nl-prov-month-label">{dayText(choice.day ?? days[0]?.date ?? from, locale, { month: 'long', year: 'numeric' })}</span>
              <span className="nl-prov-month-nav">
                <button type="button" className="nl-prov-nav" aria-label={t('earlierDays')} disabled={from <= today} onClick={() => setFrom(f => (addDays(f, -DAYS) < today ? today : addDays(f, -DAYS)))}><CaretLeft size={16} aria-hidden /></button>
                <button type="button" className="nl-prov-nav" aria-label={t('laterDays')} onClick={() => setFrom(f => addDays(f, DAYS))}><CaretRight size={16} aria-hidden /></button>
              </span>
            </div>
            {cal.isPending ? <div className="nl-prov-days-skeleton" aria-busy="true">{Array.from({ length: DAYS }, (_, i) => <Skeleton key={i} height={52} />)}</div>
              : cal.isError ? <ErrorState message={t('calendarError')} onRetry={() => void cal.refetch()} />
                : (
                  <>
                    <div className="nl-prov-days">
                      <ChoiceTiles layout="grid" aria-label={t('chooseDay')} value={choice.day ?? null} onChange={choice.setDay}
                        options={days.map(d => ({
                          value: d.date, disabled: d.free === 0,
                          label: <><span className="nl-prov-dow" aria-hidden>{dayText(d.date, locale, { weekday: 'short' })}</span><span className="nl-prov-dnum" aria-hidden>{Number(d.date.slice(8))}</span><span className="nl-sr-only">{dayText(d.date, locale, { weekday: 'long', month: 'long', day: 'numeric' })}</span></>,
                        }))} />
                    </div>
                    {selected ? (
                      <>
                        <p className="nl-prov-dayline">{t('dayLine', { day: dayText(selected.date, locale, { weekday: 'long', month: 'short', day: 'numeric' }), count: selected.slots.filter(x => x.free).length })}</p>
                        <div className="nl-prov-times">
                          <ChoiceTiles layout="grid" aria-label={t('chooseTime', { day: dayText(selected.date, locale, { weekday: 'long', month: 'long', day: 'numeric' }) })}
                            value={choice.startsAt ?? null} onChange={choice.setStartsAt}
                            options={selected.slots.map(x => ({ value: x.startsAt, label: clock(x.startsAt, locale, calZone), disabled: !x.free }))} />
                        </div>
                      </>
                    ) : <p className="nl-prov-dayline nl-prov-muted">{days.some(d => d.free > 0) ? t('pickDay') : t('noDays')}</p>}
                  </>
                )}
            <div className="nl-prov-where">
              <MapPin size={16} aria-hidden className="nl-prov-chosen-icon" />
              <span><strong>{comes ? t('comesToYou') : t('atTheirs', { name: facts.name })}</strong>
                <span className="nl-prov-muted">{comes ? (facts.zones.length ? facts.zones.join(' · ') : t('addressNext')) : facts.city ?? ''}</span></span>
            </div>
            <dl className="nl-prov-sum">
              <div><dt>{service?.name}</dt><dd>{priceText}</dd></div>
              {comes ? <div><dt>{t('travel')}</dt><dd>{t('included')}</dd></div> : null}
              {cents > 0 ? <div><dt>{t('taxEst', { pct: percent(facts.taxBps / 100, locale) })}</dt><dd>{money(tax)}</dd></div> : null}
            </dl>
            <button type="button" className="btn btn-primary btn-lg btn-block" disabled={!choice.startsAt} onClick={book}>{when ?? t('pickTime')}</button>
            <p className="nl-prov-note"><Lock size={14} aria-hidden className="nl-prov-chosen-icon" />{cents > 0 ? t('noCharge') : t('noChargeFree')}</p>
            {facts.quoteable && facts.category ? <QuoteLink facts={facts} category={facts.category.slug} pageHost={pageHost} siteOrigin={siteOrigin} className="btn btn-tertiary btn-block nl-prov-quote-btn">{t('notSure')}</QuoteLink> : null}
          </>
        )}
      </aside>
      {timed && service ? (
        <div className="nl-stickybar nl-prov-stickybar">
          <span className="nl-stickybar-sum"><strong>{priceText}</strong><span>{when ?? service.name}</span></span>
          <button type="button" className="btn btn-primary btn-lg" onClick={book}>{t('bookShort')}</button>
        </div>
      ) : null}
    </>
  );
}

/** S-56: ask this business (pre-ticked) and up to two others for quotes. */
export function QuoteLink({ facts, category, pageHost, siteOrigin, className, children }: { facts: ProviderFacts; category: string; pageHost: PageHost | null; siteOrigin: string; className: string; children: ReactNode }) {
  const path = `/services/${category}/quote?provider=${encodeURIComponent(facts.slug)}`;
  return pageHost
    ? <a href={siteHref(pageHost, siteOrigin, path)} className={className}>{children}</a>
    : <Link to="/services/$category/quote" params={{ category }} search={{ provider: facts.slug }} className={className}>{children}</Link>;
}
