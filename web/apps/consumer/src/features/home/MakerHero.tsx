import type { ReactNode } from 'react';
import { ForkKnife, Wrench } from '@phosphor-icons/react';
import { brand, SiteLink, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { categorySlot, cuisineSlot, departmentSlot } from '@northline/media';
import { Scene } from '../art/Scene';
import type { Card as Kitchen } from '../food/api';
import { useZone } from '../location/regions';
import { imageUrl, itemHref, type SearchItem } from '../search/api';
import type { Landing, Run, ShopCard } from '../shop/api';
import { clock, runWhen, weekday } from '../shop/format';
import { useHomeT } from './messages';

const TIER_RANK: Record<string, number> = { master: 3, trusted: 2, registered: 1 };

/**
 * The maker of the week (DECISIONS 2026-10-23 S-170/S-171): the api has no such feature, so it is derived from the Shop
 * landing's own fields — the highest-tier shop with a photo, one on the next pooled run first, then the one with the
 * most products. The same for everyone in a market (the landing is), so it changes only when the shops do.
 */
export function pickMaker(shops: readonly ShopCard[], run: Run | null | undefined): ShopCard | null {
  const score = (s: ShopCard) => (TIER_RANK[s.tier] ?? 0) * 1000 + (s.imageUrl ? 500 : 0) + (run && s.run?.windowId === run.windowId ? 200 : 0) + Math.min(s.products, 199);
  return [...shops].sort((a, b) => score(b) - score(a) || a.name.localeCompare(b.name))[0] ?? null;
}

/**
 * The home hero (Main Street's maker of the week in Plainsight's type, colours and shapes; design/revamp/b.html): one
 * featured local shop — its photo, kicker, headline and a short story from its own numbers, "Shop <name>" and "More
 * <department>" — and two side tiles: the top-rated service near the visitor and the kitchens open now. The boxes are
 * drawn at their final size from the first (server) render, so nothing moves when the data and photos arrive.
 */
export function MakerHero({ landing, services, kitchens }: { landing: Landing | undefined; services: readonly SearchItem[] | undefined; kitchens: readonly Kitchen[] | undefined }) {
  const t = useHomeT();
  const maker = landing?.served ? pickMaker(landing.shops, landing.run) : null;
  return (
    <section className="nl-hero" aria-labelledby="home-hero-title">
      {landing === undefined ? <HeroSkeleton /> : maker ? <Maker shop={maker} landing={landing} /> : <HeroFallback />}
      <ul className="nl-hero-side" aria-label={t('heroLabel')}>
        <li><ProTile items={services} /></li>
        <li><KitchenTile kitchens={kitchens} /></li>
      </ul>
    </section>
  );
}

function Maker({ shop, landing }: { shop: ShopCard; landing: Landing }) {
  const t = useHomeT();
  const { locale } = useLocale();
  const zone = useZone(landing.market);
  // the market's name (a city), not the visitor's street: "one of Calgary's Master shops"
  const area = landing.market;
  const run = landing.run && shop.run?.windowId === landing.run.windowId ? landing.run : null;
  const when = run ? runWhen(run, zone) : null;
  const tier = t(`tier_${shop.tier === 'master' || shop.tier === 'trusted' ? shop.tier : 'registered'}`);
  const words = { name: shop.name, department: shop.departmentName, products: shop.products, area, tier, brand: brand.name };
  return (
    <>
      <span className="nl-hero-media">
        {shop.imageUrl ? <img src={shop.imageUrl} alt="" decoding="async" fetchPriority="high" />
          : <Scene slot={[departmentSlot(shop.departmentSlug), 'banner/shop']} subject={shop.departmentSlug} crop="card" sizes="(min-width: 900px) 40vw, 100vw" priority />}
      </span>
      <div className="nl-hero-copy">
        <span className="nl-hero-kicker">{t('makerKicker', { department: shop.departmentName })}</span>
        <h2 id="home-hero-title" className="nl-hero-title">
          {run && when ? t('makerTitle', { name: shop.name, when, weekday: weekday(run.startsAt, locale, zone) }) : t('makerTitleNoRun', { name: shop.name, tier })}
        </h2>
        <p className="nl-hero-story">{run ? t('makerStory', { ...words, time: clock(run.orderBy, locale, zone) }) : t('makerStoryNoRun', words)}</p>
        <div className="nl-hero-actions">
          <SiteLink href={`/search?${new URLSearchParams({ scope: 'shop', q: shop.name })}`} className="btn btn-primary">{t('shopMaker', { name: shop.name })}</SiteLink>
          <SiteLink href={`/shop/${shop.departmentSlug}`} className="btn btn-secondary">{t('meetMaker', { department: shop.departmentName.toLowerCase() })}</SiteLink>
        </div>
      </div>
    </>
  );
}

/** No shop to feature (the market isn't served yet): the same frame, a way into Shop. */
function HeroFallback() {
  const t = useHomeT();
  return (
    <>
      <span className="nl-hero-media"><Scene slot="hero/home-main" subject="bakery" crop="card" sizes="(min-width: 900px) 40vw, 100vw" priority /></span>
      <div className="nl-hero-copy">
        <span className="nl-hero-kicker">{t('heroLabel')}</span>
        <h2 id="home-hero-title" className="nl-hero-title">{t('pageTitle', { brand: brand.name })}</h2>
        <div className="nl-hero-actions"><SiteLink href="/shop" className="btn btn-primary">{t('tileShopMore')}</SiteLink><SiteLink href="/services" className="btn btn-secondary">{t('tileServicesMore')}</SiteLink></div>
      </div>
    </>
  );
}

function HeroSkeleton() {
  const t = useHomeT();
  return (
    <>
      <span className="nl-hero-media" aria-hidden />
      <div className="nl-hero-copy" aria-busy="true">
        <span className="nl-sr-only" role="status">{t('loading')}</span>
        <h2 id="home-hero-title" className="nl-sr-only">{t('heroLabel')}</h2>
        <Skeleton width={180} height={12} />
        <Skeleton width="90%" height={30} />
        <Skeleton width="70%" height={30} />
        <Skeleton width="85%" height={16} />
        <Skeleton width="60%" height={16} />
      </div>
    </>
  );
}

function SideTile({ href, title, text, picture }: { href: string; title: string; text: string; picture: ReactNode }) {
  return (
    <SiteLink href={href} className="nl-hero-tile">
      <span className="nl-hero-tile-pic" aria-hidden>{picture}</span>
      <span className="nl-hero-tile-body"><strong>{title}</strong><span>{text}</span></span>
    </SiteLink>
  );
}

/** The top-rated service near the visitor (the services rail sorted by rating), else a way into Services. */
function ProTile({ items }: { items: readonly SearchItem[] | undefined }) {
  const t = useHomeT();
  const { money } = useFormatters();
  const top = items?.find(i => i.kind === 'service');
  if (!top) return <SideTile href="/services" title={t('sideProFallback')} text={t('sideProFallbackText')} picture={<span className="nl-hero-tile-icon"><Wrench size={30} weight="light" /></span>} />;
  const image = imageUrl(top.imageKey);
  return (
    <SideTile href={itemHref(top)} title={t('sideProTitle', { name: top.name })}
      text={top.priceCents != null && top.pricingMode !== 'quote' ? t('sideProText', { price: money(top.priceCents), provider: top.merchant.name }) : t('sideProTextQuote', { provider: top.merchant.name })}
      picture={image ? <img src={image} alt="" loading="lazy" decoding="async" /> : <Scene slot={[categorySlot(top.category?.id)]} subject={top.category?.id ?? top.name} crop="square" sizes="92px" />} />
  );
}

/** The kitchens open now where the visitor is (the Food landing's list), else a way into Food. */
function KitchenTile({ kitchens }: { kitchens: readonly Kitchen[] | undefined }) {
  const t = useHomeT();
  const open = (kitchens ?? []).filter(k => k.open);
  const first = open[0];
  if (!first) return <SideTile href="/food" title={t('sideFoodFallback')} text={t('sideFoodFallbackText')} picture={<span className="nl-hero-tile-icon"><ForkKnife size={30} weight="light" /></span>} />;
  return (
    <SideTile href="/food" title={t('sideFoodTitle', { count: open.length })} text={t('sideFoodText', { name: first.name, from: first.etaFromMin, to: first.etaToMin })}
      picture={<Scene slot={[cuisineSlot(first.cuisines[0]), 'banner/food']} subject={first.cuisines[0] ?? 'food'} crop="square" sizes="92px" />} />
  );
}
