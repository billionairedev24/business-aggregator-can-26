import { useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate, useRouterState } from '@tanstack/react-router';
import { ForkKnife, Storefront, Wrench } from '@phosphor-icons/react';
import { EmptyState, ErrorState, SearchBar, SiteLink, Skeleton, Tag, useFormatters, useLocale, type TagTone } from '@northline/ui';
import { useAccountSummary } from '../account/api';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { signInHref, useViewer } from '../session/api';
import { useShellT } from '../shell/messages';
import { useHomeSummary, useUpcoming, type HomeSummary, type TrustedProvider } from './api';
import { countOf, CUISINES, DEPARTMENTS, SERVICE_CATEGORIES, type ServiceTile, type Tile } from './catalog';
import { useHomeT } from './messages';

type T = ReturnType<typeof useHomeT>;

/** The hour on the visitor's own clock, read after mount (the server's time zone may disagree about "evening"). */
function useLocalHour(): number | null {
  const [hour, setHour] = useState<number | null>(null);
  useEffect(() => { setHour(new Date().getHours()); }, []);
  return hour;
}

const partOfDay = (hour: number) => (hour >= 5 && hour < 12 ? 'morning' : hour >= 12 && hour < 17 ? 'afternoon' : 'evening');

/**
 * Home (design 06 `home`, S-46): the search-first hero (greeting, "What do you need in … today?", the search pill that
 * lands on /search?q=, Services / Shop / Food chips with the city's numbers), entry tiles for shop departments,
 * cuisines and service categories, the four promises, "Trusted near you" and "Your week". The server renders the
 * layout, names and links (the same for everyone); the numbers, greeting and week load in the browser once the
 * location and session are known.
 */
export function HomeScreen() {
  const t = useHomeT();
  const shell = useShellT();
  const navigate = useNavigate();
  const { user, loading: sessionLoading } = useViewer();
  const { location } = useDeliveryLocation();
  const hour = useLocalHour();
  const [query, setQuery] = useState('');
  const summary = useHomeSummary(location.city);
  const place = location.label?.split(',')[0]?.trim();
  const data = summary.data;
  const empty = data && data.providers + data.shops + data.kitchensOpen === 0 && Object.keys(data.categories).length === 0;

  const greeting = hour !== null && place && !sessionLoading
    ? (user ? t('greetingNamed', { greeting: t(partOfDay(hour)), name: user.firstName, place: location.label! }) : t('greetingGuest', { greeting: t(partOfDay(hour)), place: location.label! }))
    : null;

  return (
    <div className="nl-home">
      <section className="nl-home-hero" aria-labelledby="home-hero-title">
        <p className="nl-home-greeting">{greeting ?? <Skeleton width={240} height={14} radius={4} style={{ opacity: 0.4 }} />}</p>
        <h1 id="home-hero-title" className="nl-home-title">{place ? t('heroQ', { place }) : t('heroQAnywhere')}</h1>
        <div className="nl-home-search">
          <SearchBar variant="hero" value={query} onChange={setQuery} placeholder={shell('search')}
            onSubmit={q => { if (q) void navigate({ to: '/search', search: { q } }); }} />
        </div>
        <nav className="nl-home-scopes" aria-label={t('scopes')}>
          <Scope to="/services" icon={<Wrench weight="duotone" size={18} aria-hidden />} label={t('scopeServices')} meta={data && t('pros', { count: data.providers })} loading={summary.isPending && !!location.city} />
          <Scope to="/shop" icon={<Storefront weight="duotone" size={18} aria-hidden />} label={t('scopeShop')} meta={data && t('shopsCount', { count: data.shops })} loading={summary.isPending && !!location.city} />
          <Scope to="/food" icon={<ForkKnife weight="duotone" size={18} aria-hidden />} label={t('scopeFood')} meta={data && t('openCount', { count: data.kitchensOpen })} loading={summary.isPending && !!location.city} />
        </nav>
      </section>

      {summary.isError ? (
        <div className="nl-home-section"><ErrorState message={t('loadError')} onRetry={() => void summary.refetch()} /></div>
      ) : empty ? (
        <div className="nl-home-section">
          <EmptyState action={<Link to="/location" className="btn btn-primary">{t('changeLocation')}</Link>}>{t('emptyCity', { city: data.city })}</EmptyState>
        </div>
      ) : (
        <>
          <TileSection title={t('byDept')} all={{ to: '/shop', label: t('allShops') }}>
            {DEPARTMENTS.map(tile => <TileLink key={tile.key} tile={tile} count={countOf(tile, data?.categories)} line={n => t('shops', { count: n })} />)}
          </TileSection>
          <TileSection title={t('orderFood')} all={{ to: '/food', label: t('allKitchens') }}>
            {CUISINES.map(tile => <TileLink key={tile.key} tile={tile} count={countOf(tile, data?.cuisines)} line={n => t('openKitchens', { count: n })} />)}
          </TileSection>
          <TileSection title={t('bookSvc')} all={{ to: '/services', label: t('allCats') }}>
            {SERVICE_CATEGORIES.map(tile => <TileLink key={tile.key} tile={tile} count={countOf(tile, data?.categories)} line={n => t('nearby', { count: n, kind: t(`kind_${(tile as ServiceTile).kind}`) })} />)}
          </TileSection>
        </>
      )}

      <section className="nl-home-section nl-home-promises">
        {(['t1', 't2', 't3', 't4'] as const).map(k => (
          <div key={k}><h3>{t(k)}</h3><p>{t(`${k}b`)}</p></div>
        ))}
      </section>

      <section className="nl-home-section nl-home-columns">
        {!empty && !summary.isError && <Trusted t={t} summary={data} loading={!data} city={location.city} />}
        <Week t={t} signedIn={!!user} loading={sessionLoading} />
      </section>
    </div>
  );
}

function Scope({ to, icon, label, meta, loading }: { to: '/services' | '/shop' | '/food'; icon: ReactNode; label: string; meta?: string; loading: boolean }) {
  return (
    <Link to={to} className="nl-home-scope">
      {icon}{label}
      {meta ? <span className="nl-home-scope-meta">{meta}</span> : loading ? <Skeleton width={48} height={12} radius={4} style={{ opacity: 0.4 }} /> : null}
    </Link>
  );
}

function TileSection({ title, all, children }: { title: string; all: { to: '/shop' | '/food' | '/services'; label: string }; children: ReactNode }) {
  return (
    <section className="nl-home-section">
      <div className="nl-home-head"><h2>{title}</h2><Link to={all.to}>{all.label}</Link></div>
      <div className="nl-home-tiles">{children}</div>
    </section>
  );
}

function TileLink({ tile, count, line }: { tile: Tile; count: number | undefined; line: (n: number) => string }) {
  const { locale } = useLocale();
  return (
    <SiteLink href={tile.href} className="nl-home-tile">
      <span className="nl-home-tile-name">{locale === 'fr' ? tile.fr : tile.en}</span>
      <span className="nl-home-tile-meta">{count === undefined ? <Skeleton width={70} height={12} radius={4} /> : line(count)}</span>
    </SiteLink>
  );
}

function Trusted({ t, summary, loading, city }: { t: T; summary?: HomeSummary; loading: boolean; city?: string }) {
  return (
    <div>
      <h2 className="nl-home-h2">{t('trusted')}</h2>
      {loading ? (
        Array.from({ length: 3 }, (_, i) => <div key={i} className="nl-home-row"><Skeleton width={52} height={52} radius={12} /><Skeleton width="60%" height={14} /></div>)
      ) : summary!.trusted.length === 0 ? (
        <p className="nl-home-muted">{t('trustedEmpty', { city: city ?? summary!.city })}</p>
      ) : (
        <ul className="nl-home-list">
          {summary!.trusted.map(p => <li key={p.merchantId}><ProviderRow t={t} p={p} /></li>)}
        </ul>
      )}
    </div>
  );
}

function ProviderRow({ t, p }: { t: T; p: TrustedProvider }) {
  const body = (
    <>
      <span className="nl-home-initial" style={p.brandColor ? { background: p.brandColor } : undefined} aria-hidden>{p.name.charAt(0)}</span>
      <span className="nl-home-row-main">
        <span className="nl-home-row-title"><strong>{p.name}</strong><Tag tone="accent">{t(`tier_${p.tier}` as 'tier_master')}</Tag></span>
        {p.category && <span className="nl-home-row-sub" lang="en">{p.category.name}</span>}
      </span>
      <span className="nl-home-row-end">
        <strong>{p.reviews > 0 ? t('rating', { rating: p.rating.toFixed(1) }) : t('noReviews')}</strong>
        {p.reviews > 0 && <span className="nl-home-row-sub">{t('reviews', { count: p.reviews })}</span>}
      </span>
    </>
  );
  return p.slug
    ? <Link to="/providers/$slug" params={{ slug: p.slug }} className="nl-home-row nl-home-row-link">{body}</Link>
    : <div className="nl-home-row">{body}</div>;
}

const TONE: Record<string, TagTone> = { accent: 'accent', neutral: 'neutral', 'accent-2': 'accent-2' };

function Week({ t, signedIn, loading }: { t: T; signedIn: boolean; loading: boolean }) {
  const href = useRouterState({ select: s => s.location.href });
  const upcoming = useUpcoming(signedIn);
  const account = useAccountSummary(signedIn);
  const { money, number } = useFormatters();
  const points = account.data?.points;
  return (
    <div>
      <h2 className="nl-home-h2">{t('week')}</h2>
      {loading || (signedIn && upcoming.isPending) ? (
        Array.from({ length: 2 }, (_, i) => <div key={i} className="nl-home-row"><Skeleton width="70%" height={14} /></div>)
      ) : !signedIn ? (
        <EmptyState action={<SiteLink href={signInHref(href)} className="btn btn-secondary">{t('signIn')}</SiteLink>}>{t('weekSignIn')}</EmptyState>
      ) : upcoming.isError ? (
        <ErrorState message={t('loadError')} onRetry={() => void upcoming.refetch()} />
      ) : upcoming.data!.length === 0 ? (
        <EmptyState action={<Link to="/services" className="btn btn-secondary">{t('bookSomething')}</Link>}>{t('weekEmpty')}</EmptyState>
      ) : (
        <ul className="nl-home-list">
          {upcoming.data!.map(u => (
            <li key={u.id}>
              <SiteLink href={u.href} className="nl-home-row nl-home-row-link">
                <span className="nl-home-row-main"><strong>{u.title}</strong>{u.subtitle && <span className="nl-home-row-sub">{u.subtitle}</span>}</span>
                <Tag tone={TONE[u.tone] ?? 'neutral'}>{u.state}</Tag>
              </SiteLink>
            </li>
          ))}
        </ul>
      )}
      {points && (
        <div className="nl-home-points">
          <span><strong>{t('points', { points: number(points.balance) })}</strong> · {t('worth', { value: money(points.valueCents) })}</span>
          <SiteLink href="/account?tab=wallet" className="btn btn-ghost">{t('wallet')}</SiteLink>
        </div>
      )}
    </div>
  );
}
