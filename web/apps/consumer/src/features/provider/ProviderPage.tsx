import type { ReactNode } from 'react';
import { useInfiniteQuery, useSuspenseQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, ErrorState, Skeleton, Tag, useLocale, type Locale } from '@northline/ui';
import { siteHref, type PageHost } from '../../lib/pages';
import { useSiteConfig } from '../../lib/config';
import { nextAvailable, percent, price, rating } from '../services/format';
import { useServicesT } from '../services/messages';
import { categoryName, copyKeys, familyOf } from '../services/taxonomy';
import { dyn } from '../services/text';
import { moreReviewsQuery, providerQuery, storefrontQuery, type ProviderFacts, type PublicReview, type Storefront } from './api';
import { useProviderT } from './messages';

/** Sections the provider page renders in the page's order (hero first, the CTA card beside them). */
const MAIN: readonly string[] = ['about', 'reviews', 'area', 'faq', 'policies'];

/**
 * design 06 `provider`: the business's published page — its sections in the order the business chose in the page
 * builder (storefront API), with the trust figures, services, service area and reviews Northline holds. On
 * `pages.<zone>` and the business's own domain the same page renders; its links then lead to the site.
 */
export function ProviderPage({ slug }: { slug: string }) {
  const { locale } = useLocale();
  const { data: page } = useSuspenseQuery(storefrontQuery(slug));
  const { data: facts } = useSuspenseQuery(providerQuery(slug, locale));
  const config = useSiteConfig();
  const enabled = new Set(page.sections.map(s => s.kind));
  const main = page.sections.filter(s => MAIN.includes(s.kind));
  return (
    <div className="nl-page nl-prov">
      {page.announcement ? <div className="nl-prov-announce" role="note"><Alert tone="highlight">{page.announcement}</Alert></div> : null}
      <Hero page={page} facts={facts} locale={locale} />
      <div className="nl-prov-split">
        <div className="nl-prov-main">
          <Figures facts={facts} locale={locale} />
          <Credentials facts={facts} />
          {main.map(s => <Section key={s.kind} kind={s.kind} settings={s.settings} page={page} facts={facts} slug={slug} pageHost={config.page ?? null} siteOrigin={config.siteOrigin} />)}
        </div>
        <CtaCard page={page} facts={facts} showServices={enabled.has('services')} pageHost={config.page ?? null} siteOrigin={config.siteOrigin} />
      </div>
    </div>
  );
}

function Hero({ page, facts, locale }: { page: Storefront; facts: ProviderFacts; locale: Locale }) {
  const t = useProviderT();
  const s = useServicesT();
  const category = facts.category ? categoryName(facts.category.slug, facts.category.names, locale).text : null;
  const year = new Date(facts.since).getFullYear();
  const tagline = [page.tagline ?? [category, facts.city].filter(Boolean).join(' · '), t('since', { year })].filter(Boolean).join(' · ');
  const tier = dyn(s, `tier_${facts.tier}`, facts.tier);
  return (
    <header className="nl-prov-hero" style={{ background: page.brandColor }}>
      <div className="nl-prov-id">
        <span className="nl-prov-mark" aria-hidden>{page.logoUrl ? <img src={page.logoUrl} alt="" /> : facts.name.trim().charAt(0).toUpperCase()}</span>
        <div className="nl-prov-idtext">
          <h1 className="nl-prov-name">{facts.name}</h1>
          <div className="nl-prov-tagline">{tagline}</div>
        </div>
      </div>
      <span className="tag nl-prov-tier">{t('tierVerified', { tier })}</span>
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

/** Verified facts as the design's credential tags, plus how the business is booked. */
function Credentials({ facts }: { facts: ProviderFacts }) {
  const t = useProviderT();
  const tags = facts.verifiedFacts.map(f => f.startsWith('licence:')
    ? t('fact_licence', { registry: f.slice('licence:'.length) })
    : dyn(t, `fact_${f}`, ''))
    .filter(Boolean);
  return (
    <div className="nl-prov-creds">
      {tags.map(tag => <Tag key={tag} tone="accent">{tag}</Tag>)}
      <Tag tone="neutral">{t(`mode_${facts.kind}`)}</Tag>
    </div>
  );
}

interface SectionProps { kind: string; settings: Record<string, unknown>; page: Storefront; facts: ProviderFacts; slug: string; pageHost: PageHost | null; siteOrigin: string }

function Section({ kind, settings, page, facts, slug, pageHost, siteOrigin }: SectionProps) {
  const t = useProviderT();
  switch (kind) {
    case 'about': {
      const about = page.business.about;
      return about ? <p className="nl-prov-about">{about}</p> : null;
    }
    case 'reviews': return <Reviews facts={facts} slug={slug} pageHost={pageHost} siteOrigin={siteOrigin} />;
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
  return (
    <figure className="nl-prov-review">
      <span className="nl-sr-only">{t('stars', { count: r.rating })}</span>
      {r.text ? <blockquote className="nl-prov-quote">“{r.text}”</blockquote> : null}
      <figcaption className="nl-prov-who">{t('reviewWho', { who: r.author ?? t('reviewAnonymous'), ref: r.refType, when: ago(r.createdAt, locale) })}</figcaption>
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

function CtaCard({ page, facts, showServices, pageHost, siteOrigin }: { page: Storefront; facts: ProviderFacts; showServices: boolean; pageHost: PageHost | null; siteOrigin: string }) {
  const t = useProviderT();
  const s = useServicesT();
  const { locale } = useLocale();
  const family = familyOf(facts.kind, facts.category?.slug ?? '', facts.vehicle);
  const keys = copyKeys(family, facts.kind);
  const book = `/providers/${facts.slug}/book`;
  const bookLink = (className: string, children: ReactNode) => (pageHost
    ? <a href={siteHref(pageHost, siteOrigin, book)} className={className}>{children}</a>
    : <Link to="/providers/$slug/book" params={{ slug: facts.slug }} className={className}>{children}</Link>);
  const note = dyn(s, keys.note);
  return (
    <aside className="nl-prov-cta" aria-labelledby="prov-cta">
      <h2 id="prov-cta" className="nl-prov-cta-title">{t(`ctaTitle_${facts.kind}`, { name: facts.name })}</h2>
      <p className="nl-prov-cta-hint">{dyn(s, keys.ctaHint)}</p>
      {showServices
        ? facts.services.length === 0
          ? <p className="nl-prov-text">{t('noServices')}</p>
          : (
            <ul className="nl-prov-menu">
              {facts.services.map(sv => (
                <li key={sv.id} className="nl-prov-menu-row">
                  <span>{sv.name}<span className="nl-prov-menu-dur">{t('minutes', { count: sv.durationMin })}</span></span>
                  <strong>{price(s, locale, sv.pricingMode, sv.priceCents)}</strong>
                </li>
              ))}
            </ul>
          )
        : null}
      {bookLink('btn btn-primary nl-prov-book', t(`cta_${page.ctaLabel}`))}
      {facts.quoteable && facts.kind !== 'event' ? bookLink('btn btn-secondary nl-prov-quote-btn', t('notSure')) : null}
      <div className="nl-prov-foot">{[t('nextAvailable', { when: nextAvailable(s, locale, facts.nextAvailable) }), note].filter(Boolean).join(' · ')}</div>
    </aside>
  );
}

export function ProviderPageSkeleton() {
  return (
    <div className="nl-page nl-prov" aria-busy="true">
      <Skeleton height={150} radius="var(--radius-md)" style={{ marginTop: 24 }} />
      <div className="nl-prov-split">
        <div className="nl-prov-main">
          <Skeleton width="min(420px, 90%)" height={48} />
          <Skeleton width="min(360px, 80%)" height={24} style={{ marginTop: 16 }} />
          <Skeleton height={80} style={{ marginTop: 18, maxWidth: 560 }} />
          {Array.from({ length: 2 }, (_, i) => <Skeleton key={i} height={60} style={{ marginTop: 16, maxWidth: 560 }} />)}
        </div>
        <Skeleton height={320} radius="var(--radius-md)" />
      </div>
    </div>
  );
}
