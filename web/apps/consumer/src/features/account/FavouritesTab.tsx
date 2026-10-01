import { useQuery } from '@tanstack/react-query';
import { Button, EmptyState, ErrorState, SiteLink, Skeleton, Tag, useLocale } from '@northline/ui';
import { favouritesQuery, useFavourite, type Favourite } from './api';
import { categoryText, shortDate } from './format';
import { useAccountT, type AccountT } from './messages';

const TIER_TONE: Record<string, 'accent' | 'accent-2' | 'neutral'> = { master: 'accent', trusted: 'accent-2' };

/** Where a favourite's button goes and what it says: an open quote first, then the business's own page. */
export function favouriteCta(f: Favourite, t: AccountT): { label: string; href: string } {
  if (f.openQuoteId) return { label: t('cta_quote'), href: `/quotes/${f.openQuoteId}` };
  if (f.type === 'kitchen' && f.slug) return { label: t('cta_order'), href: `/food/${f.slug}` };
  if (f.type === 'seller') return { label: t('cta_shop'), href: `/search?${new URLSearchParams({ q: f.name, scope: 'shop' }).toString()}` };
  return { label: t('cta_book'), href: f.slug ? `/providers/${f.slug}` : `/search?${new URLSearchParams({ q: f.name }).toString()}` };
}

/**
 * Favourite providers & shops (design 06 `at.favourites`): the business, its tier, what the person did with it, a
 * button to go back to it and Remove.
 */
export function FavouritesTab() {
  const t = useAccountT();
  const { locale } = useLocale();
  const list = useQuery(favouritesQuery);
  const change = useFavourite();
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('favouritesTitle')}</h1>
      <p className="nl-acct-lede">{t('favouritesLede')}</p>
      {list.isPending ? (
        <div aria-busy="true"><span className="nl-sr-only">{t('loading')}</span>{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={52} style={{ marginTop: 12, maxWidth: 760 }} />)}</div>
      ) : list.isError ? (
        <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />
      ) : list.data.length === 0 ? (
        <EmptyState action={<SiteLink href="/services" className="btn btn-primary">{t('findProvider')}</SiteLink>}>{t('noFavourites')}</EmptyState>
      ) : (
        <ul className="nl-fav-list" aria-label={t('favouritesTitle')}>
          {list.data.map(f => {
            const cta = favouriteCta(f, t);
            const meta = [
              categoryText(f.categoryId, locale),
              f.visits > 0 ? t(f.type === 'provider' || f.type === 'both' ? 'visitsJobs' : 'visitsOrders', { count: f.visits }) : undefined,
              f.lastAt ? t('lastVisit', { date: shortDate(f.lastAt, locale) }) : undefined,
              f.openQuoteId ? t('quoteOpen') : undefined,
            ].filter(Boolean).join(' · ');
            return (
              <li key={f.merchantId} className="nl-fav">
                <span className="nl-fav-mark" aria-hidden>{f.name.charAt(0)}</span>
                <div className="nl-fav-text">
                  <div className="nl-fav-name"><strong>{f.name}</strong> <Tag tone={TIER_TONE[f.tier] ?? 'neutral'}>{t(`tier_${f.tier}` as Parameters<AccountT>[0])}</Tag></div>
                  {meta ? <div className="nl-small nl-muted">{meta}</div> : null}
                </div>
                <div className="nl-fav-actions">
                  <SiteLink href={cta.href} className="btn btn-secondary">{cta.label}</SiteLink>
                  <Button type="button" variant="ghost" aria-label={t('removeNamed', { name: f.name })} disabled={change.isPending}
                    onClick={() => change.mutate({ merchantId: f.merchantId, on: false })}>{t('remove')}</Button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {change.isError ? <p className="nl-error" role="alert">{t('removeError')}</p> : null}
    </>
  );
}
