import { useState, type ReactNode } from 'react';
import { useInfiniteQuery, useQuery, useSuspenseQueries } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, ErrorState, OptionCard, RadioGroup, Skeleton, Stars, Tag, UnderlineTabs, useLocale, type Locale } from '@northline/ui';
import { siteHref, type PageHost } from '../../lib/pages';
import { useSiteConfig } from '../../lib/config';
import { percent, price, rating } from '../services/format';
import { useServicesT } from '../services/messages';
import { categoryName, copyKeys, familyOf } from '../services/taxonomy';
import { dyn } from '../services/text';
import { FavouriteButton } from '../account/FavouriteButton';
import { useStorefrontVisit } from '../../lib/visits';
import { moreReviewsQuery, providerQuery, rewardQuery, storefrontQuery, type ProviderFacts, type ProviderService, type PublicReview, type Storefront } from './api';
import { useProviderT } from './messages';
import { categorySlot } from '@northline/media';
import { Scene } from '../art/Scene';
import { Crumbs } from '../pages/Crumbs';
import { BookBox, bookable, QuoteLink, useBookingChoice, type BookingChoice } from './BookBox';
import { useRenderNow } from '../../lib/renderClock';
import { ShieldCheck, Star } from '@phosphor-icons/react';

/** Sections the About tab renders, in the page's order. */
const ABOUT: readonly string[] = ['about', 'area', 'faq', 'policies'];
type TabKey = 'services' | 'reviews' | 'about';

/**
 * A provider's page (E-15 S-172, design/revamp/a.html "Service provider with booking"; S-54 behaviour): the breadcrumb,
 * the header (photo, category · area, name, the tagline, rating and track record, the verified facts as trust badges),
 * underline tabs Services / Reviews / About, the services as one-choice option cards with the business's recent work
 * and its first reviews, and beside them the booking box (S-172 `BookBox`: days, times, where, the price, "Book <day>
 * at <time>"). The business's own section choices (storefront API) still decide what shows. On `pages.<zone>` and the
 * business's own domain the same page renders; its links then lead to the site. Phones: a sticky Book bar.
 */
export function ProviderPage({ slug }: { slug: string }) {
  const { locale } = useLocale();
  const t = useProviderT();
  // both at once: two useSuspenseQuery calls suspended twice in turn — the facts were only asked for once the page
  // had arrived (a request waterfall on client navigation, and the slowest first render in the tests)
  const [{ data: page }, { data: facts }] = useSuspenseQueries({ queries: [storefrontQuery(slug), providerQuery(slug, locale)] });
  const config = useSiteConfig();
  useStorefrontVisit(slug);
  const enabled = new Set(page.sections.map(s => s.kind));
  const showServices = enabled.has('services') && facts.services.length > 0;
  const about = page.sections.filter(s => ABOUT.includes(s.kind));
  const tabs = [
    ...(showServices ? [{ value: 'services' as const, label: t('tabServices'), count: facts.services.length }] : []),
    ...(enabled.has('reviews') ? [{ value: 'reviews' as const, label: t('tabReviews'), count: facts.reviewCount }] : []),
    { value: 'about' as const, label: t('tabAbout') },
  ];
  const [tab, setTab] = useState<TabKey>(tabs[0]!.value);
  const choice = useBookingChoice(facts);
  const category = facts.category ? categoryName(facts.category.slug, facts.category.names, locale) : null;
  const pageHost = config.page ?? null;
  const crumbs = [
    { label: t('crumbServices'), href: siteHref(pageHost, config.siteOrigin, '/services') },
    ...(category && facts.category ? [{ label: category.text, href: siteHref(pageHost, config.siteOrigin, `/services/${facts.category.slug}`), lang: category.lang }] : []),
    { label: facts.name },
  ];

  return (
    <div className="nl-page nl-prov">
      <Crumbs label={t('crumbs')} items={crumbs} />
      {page.announcement ? <div className="nl-prov-announce" role="note"><Alert tone="info">{page.announcement}</Alert></div> : null}
      <div className="nl-prov-split">
        <div className="nl-prov-main">
          <Hero page={page} facts={facts} locale={locale} />
          <div className="nl-prov-tabs">
            <UnderlineTabs aria-label={t('tabsLabel', { name: facts.name })} options={tabs} value={tab} onChange={setTab} />
          </div>
          <div role="tabpanel" aria-label={tabs.find(x => x.value === tab)?.label} className="nl-prov-panel">
            {tab === 'services' ? (
              <>
                <Services facts={facts} choice={choice} />
                <RecentWork facts={facts} />
                {enabled.has('reviews') && facts.reviews.items.length > 0 ? (
                  <SectionBlock id="prov-neighbours" title={t('neighbours')}>
                    <ul className="nl-prov-reviews">{facts.reviews.items.slice(0, 2).map(r => <li key={r.id}><Review review={r} name={facts.name} /></li>)}</ul>
                    {facts.reviewCount > 2 ? <button type="button" className="btn btn-tertiary nl-prov-all" onClick={() => setTab('reviews')}>{t('allReviewsCount', { count: facts.reviewCount })}</button> : null}
                  </SectionBlock>
                ) : null}
              </>
            ) : null}
            {tab === 'reviews' ? <Reviews facts={facts} slug={slug} pageHost={pageHost} siteOrigin={config.siteOrigin} /> : null}
            {tab === 'about' ? (
              <>
                <Figures facts={facts} locale={locale} />
                {about.map(s => <Section key={s.kind} kind={s.kind} settings={s.settings} page={page} facts={facts} />)}
              </>
            ) : null}
          </div>
        </div>
        {showServices
          ? <BookBox facts={facts} choice={choice} pageHost={pageHost} siteOrigin={config.siteOrigin} />
          : <CtaCard page={page} facts={facts} pageHost={pageHost} siteOrigin={config.siteOrigin} />}
      </div>
    </div>
  );
}

function Hero({ page, facts, locale }: { page: Storefront; facts: ProviderFacts; locale: Locale }) {
  const t = useProviderT();
  const s = useServicesT();
  const category = facts.category ? categoryName(facts.category.slug, facts.category.names, locale).text : null;
  const year = new Date(facts.since).getFullYear();
  const tier = dyn(s, `tier_${facts.tier}`, facts.tier);
  const area = facts.zones.length > 0 ? facts.zones.join(', ') : facts.city;
  // the business's own photo (its most booked service with one), else its category's library photo
  const cover = facts.services.find(sv => sv.imageUrl)?.imageUrl ?? null;
  const track = [
    facts.onTimePct != null ? t('onTimeLine', { pct: percent(facts.onTimePct, locale) }) : null,
    facts.rebookPct != null ? t('rebookLine', { pct: percent(facts.rebookPct, locale) }) : null,
  ].filter(Boolean).join(' · ');
  return (
    <header className="nl-prov-hero">
      <span className="nl-prov-photo" aria-hidden>
        {page.logoUrl ? <img src={page.logoUrl} alt="" />
          : cover ? <img src={cover} alt="" />
            : <Scene slot={[categorySlot(facts.category?.id), 'banner/services']} subject={facts.category?.slug ?? facts.kind} sizes="160px" priority />}
      </span>
      <div className="nl-prov-idtext">
        {category || area ? <span className="nl-prov-kicker">{[category, area].filter(Boolean).join(' · ')}</span> : null}
        <h1 className="nl-prov-name">{facts.name}</h1>
        <div className="nl-prov-tagline">{[page.tagline, t('since', { year }), t('tierVerified', { tier })].filter(Boolean).join(' · ')}</div>
        <div className="nl-prov-rating">
          {facts.reviewCount > 0 ? <Stars rating={facts.rating} reviewCount={facts.reviewCount} /> : <span>{t('newOnNorthline')}</span>}
          {track ? <span>{track}</span> : null}
        </div>
        <Credentials facts={facts} />
      </div>
      <FavouriteButton merchantId={facts.merchantId} className="btn btn-secondary btn-icon nl-prov-fav" />
    </header>
  );
}

function Figures({ facts, locale }: { facts: ProviderFacts; locale: Locale }) {
  const t = useProviderT();
  const pct = (v: number | null | undefined) => (v === null || v === undefined ? t('noFigure') : percent(v, locale));
  return (
    <dl className="nl-prov-figures">
      <div><dt>{facts.reviewCount > 0 ? t('verifiedReviews', { count: facts.reviewCount }) : t('newOnNorthline')}</dt><dd className="nl-prov-fig">{facts.reviewCount > 0 ? rating(facts.rating, locale) : '—'}</dd></div>
      <div><dt>{t('onTime')}</dt><dd className="nl-prov-fig">{pct(facts.onTimePct)}</dd></div>
      <div><dt>{t('disputes')}</dt><dd className="nl-prov-fig">{pct(facts.disputePct)}</dd></div>
      <div><dt>{t('rebook')}</dt><dd className="nl-prov-fig">{pct(facts.rebookPct)}</dd></div>
    </dl>
  );
}

/** Verified facts as the mockup's trust badges (success), plus how the business is booked and a running reward. */
function Credentials({ facts }: { facts: ProviderFacts }) {
  const t = useProviderT();
  const { locale } = useLocale();
  const reward = useQuery(rewardQuery(facts.merchantId)).data;
  const tags = facts.verifiedFacts.map(f => f.startsWith('licence:')
    ? t('fact_licence', { registry: f.slice('licence:'.length) })
    : dyn(t, `fact_${f}`, ''))
    .filter(Boolean);
  return (
    <ul className="nl-prov-creds">
      {tags.map(tag => <li key={tag}><span className="tag tag-success nl-prov-badge"><ShieldCheck size={14} aria-hidden />{tag}</span></li>)}
      <li><Tag tone="neutral">{t(`mode_${facts.kind}`)}</Tag></li>
      {reward ? <li><Tag tone="accent">{t(reward.label ? 'rewardOn' : 'reward', { multiplier: reward.multiplier, label: reward.label ?? '', until: new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${reward.endsOn}T12:00:00Z`)) })}</Tag></li> : null}
    </ul>
  );
}

/** "Choose a service": one-choice option cards (the booking box follows the choice); quoted services say "Quote". */
function Services({ facts, choice }: { facts: ProviderFacts; choice: BookingChoice }) {
  const t = useProviderT();
  const s = useServicesT();
  const { locale } = useLocale();
  const line = (sv: ProviderService) => [t('minutes', { count: sv.durationMin }), sv.included].filter(Boolean).join(' · ');
  return (
    <SectionBlock id="prov-services" title={t('chooseService')}>
      <RadioGroup className="nl-prov-services" aria-labelledby="prov-services">
        {facts.services.map(sv => (
          <OptionCard key={sv.id} role="radio" mark="radio" selected={choice.service?.id === sv.id}
            title={sv.name} description={bookable(sv) ? line(sv) : `${line(sv)} · ${t('quoteOnly')}`}
            trailing={<strong className="nl-prov-service-price">{price(s, locale, sv.pricingMode, sv.priceCents)}</strong>}
            onClick={() => choice.setService(sv.id)} />
        ))}
      </RadioGroup>
    </SectionBlock>
  );
}

/** The business's own photos (its services'); nothing when it has none (never stock photos of its category). */
function RecentWork({ facts }: { facts: ProviderFacts }) {
  const t = useProviderT();
  const photos = [...new Set(facts.services.map(s => s.imageUrl).filter((u): u is string => !!u))].slice(0, 6);
  if (photos.length === 0) return null;
  return (
    <SectionBlock id="prov-work" title={t('recentWork')}>
      <ul className="nl-prov-work">{photos.map(src => <li key={src}><img src={src} alt="" loading="lazy" decoding="async" /></li>)}</ul>
    </SectionBlock>
  );
}

interface SectionProps { kind: string; settings: Record<string, unknown>; page: Storefront; facts: ProviderFacts }

function Section({ kind, settings, page, facts }: SectionProps) {
  const t = useProviderT();
  switch (kind) {
    case 'about': {
      const about = page.business.about;
      return about ? <SectionBlock id="prov-about" title={t('aboutHeading', { name: facts.name })}><p className="nl-prov-about">{about}</p></SectionBlock> : null;
    }
    case 'area': {
      const theirs = facts.kind === 'appointment' || facts.kind === 'consult';
      if (!theirs && facts.zones.length === 0) return null;
      return (
        <SectionBlock id="prov-area" title={t('areaHeading')}>
          <p className="nl-prov-text">{theirs && facts.city ? t('areaTheirs', { city: facts.city }) : t('areaComes', { zones: facts.zones.join(' · ') })}</p>
          {!theirs ? <ul className="nl-prov-zones">{facts.zones.map(z => <li key={z}><Tag tone="neutral">{z}</Tag></li>)}</ul> : null}
        </SectionBlock>
      );
    }
    case 'faq': {
      const pairs = Array.isArray(settings.pairs) ? (settings.pairs as { q?: unknown; a?: unknown }[]).filter(p => typeof p.q === 'string' && p.q.trim()) : [];
      if (pairs.length === 0) return null;
      return (
        <SectionBlock id="prov-faq" title={t('faqHeading')}>
          {pairs.map((p, i) => (
            <details key={i} className="nl-prov-faq">
              <summary>{String(p.q)}</summary>
              {typeof p.a === 'string' ? <p>{p.a}</p> : null}
            </details>
          ))}
        </SectionBlock>
      );
    }
    case 'policies': {
      const text = [settings.returns_text, settings.allergen_text].filter((x): x is string => typeof x === 'string' && x.trim() !== '');
      if (text.length === 0) return null;
      return <SectionBlock id="prov-policies" title={t('policiesHeading')}>{text.map((p, i) => <p key={i} className="nl-prov-text">{p}</p>)}</SectionBlock>;
    }
    default: return null;
  }
}

function SectionBlock({ id, title, children }: { id: string; title: ReactNode; children: ReactNode }) {
  return <section aria-labelledby={id} className="nl-prov-section"><h2 id={id} className="nl-prov-h2">{title}</h2>{children}</section>;
}

function Reviews({ facts, slug, pageHost, siteOrigin }: { facts: ProviderFacts; slug: string; pageHost: PageHost | null; siteOrigin: string }) {
  const t = useProviderT();
  const first = facts.reviews.items;
  const more = useInfiniteQuery({ ...moreReviewsQuery(slug, first.length), enabled: false });
  const loaded = more.data?.pages.flatMap(p => p.items) ?? [];
  const hasMore = more.data ? more.hasNextPage : facts.reviews.nextOffset !== null && facts.reviews.nextOffset !== undefined;
  return (
    <SectionBlock id="prov-reviews" title={t('reviewsHeading', { count: facts.reviewCount })}>
      {facts.reviewCount > 0 ? <div className="nl-prov-reviews-sum"><Stars rating={facts.rating} size={18} /><span>{t('verifiedReviews', { count: facts.reviewCount })}</span></div> : null}
      {first.length === 0 ? <p className="nl-prov-text">{t('newOnNorthline')}</p> : null}
      <ul className="nl-prov-reviews">{[...first, ...loaded].map(r => <li key={r.id}><Review review={r} name={facts.name} /></li>)}</ul>
      {more.isError ? <ErrorState message={t('reviewsError')} onRetry={() => void more.fetchNextPage()} /> : null}
      {hasMore
        ? pageHost
          ? <a className="btn btn-secondary" href={siteHref(pageHost, siteOrigin, `/providers/${slug}`)}>{t('allReviews')}</a>
          : <button type="button" className="btn btn-secondary" disabled={more.isFetching} onClick={() => void (more.data ? more.fetchNextPage() : more.refetch())}>{t('moreReviews')}</button>
        : null}
    </SectionBlock>
  );
}

function Review({ review: r, name }: { review: PublicReview; name: string }) {
  const t = useProviderT();
  const { locale } = useLocale();
  const now = useRenderNow();
  const who = r.author ?? t('reviewAnonymous');
  const initials = who.split(/\s+/).map(w => w.charAt(0)).join('').slice(0, 2).toUpperCase();
  return (
    <figure className="nl-prov-review">
      <figcaption className="nl-prov-who"><span className="nl-prov-avatar" aria-hidden>{initials}</span>{t('reviewWho', { who, ref: r.refType, when: ago(r.createdAt, locale, now.getTime()) })}</figcaption>
      <span className="nl-prov-review-stars" role="img" aria-label={t('stars', { count: r.rating })}>
        {Array.from({ length: 5 }, (_, i) => <Star key={i} size={14} weight={i < r.rating ? 'fill' : 'regular'} aria-hidden />)}
      </span>
      {r.text ? <blockquote className="nl-prov-quote">“{r.text}”</blockquote> : null}
      {r.reply ? <p className="nl-prov-reply"><strong>{t('reply', { name })}</strong> {r.reply}</p> : null}
    </figure>
  );
}

/** "3 days ago", "2 weeks ago", "1 month ago" (fr: « il y a 3 jours »). */
export function ago(iso: string, locale: Locale, now = Date.now()): string {
  const days = Math.max(0, Math.round((now - new Date(iso).getTime()) / 86_400_000));
  const f = new Intl.RelativeTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { numeric: 'auto' });
  if (days < 7) return f.format(-days, 'day');
  if (days < 30) return f.format(-Math.round(days / 7), 'week');
  if (days < 365) return f.format(-Math.round(days / 30), 'month');
  return f.format(-Math.round(days / 365), 'year');
}

/** A business that publishes no services section (or lists none): the plain booking card — title, hint, the CTA. */
function CtaCard({ page, facts, pageHost, siteOrigin }: { page: Storefront; facts: ProviderFacts; pageHost: PageHost | null; siteOrigin: string }) {
  const t = useProviderT();
  const s = useServicesT();
  const family = familyOf(facts.kind, facts.category?.slug ?? '', facts.vehicle);
  const keys = copyKeys(family, facts.kind);
  const book = `/providers/${facts.slug}/book`;
  const bookLink = (className: string, children: ReactNode) => (pageHost
    ? <a href={siteHref(pageHost, siteOrigin, book)} className={className}>{children}</a>
    : <Link to="/providers/$slug/book" params={{ slug: facts.slug }} className={className}>{children}</Link>);
  return (
    <aside className="nl-prov-cta" aria-labelledby="prov-cta">
      <h2 id="prov-cta" className="nl-prov-cta-title">{t(`ctaTitle_${facts.kind}`, { name: facts.name })}</h2>
      <p className="nl-prov-cta-hint">{dyn(s, keys.ctaHint)}</p>
      {facts.services.length === 0 ? <p className="nl-prov-text">{t('noServices')}</p> : null}
      {facts.kind === 'event' && facts.category
        ? <QuoteLink facts={facts} category={facts.category.slug} pageHost={pageHost} siteOrigin={siteOrigin} className="btn btn-primary btn-lg btn-block">{t('cta_request_quote')}</QuoteLink>
        : bookLink('btn btn-primary btn-lg btn-block', t(`cta_${page.ctaLabel}`))}
      {facts.quoteable && facts.kind !== 'event' && facts.category
        ? <QuoteLink facts={facts} category={facts.category.slug} pageHost={pageHost} siteOrigin={siteOrigin} className="btn btn-secondary btn-block nl-prov-quote-btn">{t('notSure')}</QuoteLink> : null}
    </aside>
  );
}

export function ProviderPageSkeleton() {
  return (
    <div className="nl-page nl-prov" aria-busy="true">
      <div className="nl-prov-split nl-prov-skeleton">
        <div className="nl-prov-main">
          <div className="nl-prov-hero"><span className="nl-prov-photo" /><div className="nl-prov-idtext"><Skeleton width="min(420px, 90%)" height={28} /><Skeleton width="min(360px, 80%)" height={16} /></div></div>
          {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={60} />)}
        </div>
        <Skeleton height={320} radius="var(--radius-panel)" />
      </div>
    </div>
  );
}
