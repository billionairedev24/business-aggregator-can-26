import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Basket, ClockCounterClockwise, ForkKnife, MagnifyingGlass, Storefront, Wrench } from '@phosphor-icons/react';
import { SearchBar, useFormatters, useLocale, type SearchSuggestion, type SearchSuggestionGroup, type TextRange } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { marketOf, suggestQuery, suggestionHref, type Suggestion } from './api';
import { useSearchT } from './messages';
import { daysAgo, readRecent, rememberSearch, type RecentSearch } from './recent';

const DEBOUNCE_MS = 150;
const RECENT_SHOWN = 2;

const ICONS: Record<Suggestion['type'] | 'recent', ReactNode> = {
  product: <Basket weight="duotone" size={16} />,
  service: <Wrench weight="duotone" size={16} />,
  food: <ForkKnife weight="duotone" size={16} />,
  merchant: <Storefront weight="duotone" size={16} />,
  category: <MagnifyingGlass weight="duotone" size={16} />,
  recent: <ClockCounterClockwise weight="duotone" size={16} />,
};

/** The typed text's first case-insensitive occurrence (for recent searches; the API highlights its own). */
function matchOf(text: string, q: string): TextRange[] {
  const i = text.toLowerCase().indexOf(q.trim().toLowerCase());
  return q.trim() && i >= 0 ? [{ start: i, length: q.trim().length }] : [];
}

function useDebounced(value: string): string {
  const [debounced, set] = useState(value);
  useEffect(() => { const id = setTimeout(() => set(value), DEBOUNCE_MS); return () => clearTimeout(id); }, [value]);
  return debounced;
}

/**
 * The site's search field with predictions (S-48; design 06 header and home hero): as you type, the S-44 suggester's
 * listings, businesses and categories for the visitor's province, then "Your recent" searches of this browser.
 * Enter searches (`/search?q=`); a suggestion opens its page. Everything here runs in the browser.
 */
export function SiteSearch({ variant, placeholder, initial = '' }: { variant: 'hero' | 'header'; placeholder?: string; initial?: string }) {
  const t = useSearchT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const navigate = useNavigate();
  const { location } = useDeliveryLocation();
  const [query, setQuery] = useState(initial);
  useEffect(() => setQuery(initial), [initial]);
  const typed = useDebounced(query);
  const market = location.status === 'locating' ? undefined : marketOf(location.province);
  const suggest = useQuery({ ...suggestQuery(typed, market, locale), enabled: typed.trim().length > 0, placeholderData: keepPreviousData });
  // read after hydration: the server has no recent searches and the first render must match it
  const [recent, setRecent] = useState<RecentSearch[]>([]);
  useEffect(() => setRecent(readRecent()), []);

  const { groups, byId } = useMemo(() => {
    const byId = new Map<string, { href: string; remember?: string }>();
    const kindLabel = (id: string) => (id.startsWith('service.') ? t('s_service') : id.startsWith('shop.') ? t('s_shop') : id.startsWith('food.') ? t('s_food') : t('s_category'));
    const merchantMeta = (s: Suggestion) => [
      s.merchantType ? t(`type_${s.merchantType}` as 'type_seller') : null,
      s.trustTier === 'master' || s.trustTier === 'trusted' ? t(`tier_${s.trustTier}`) : null,
      s.rating ? t('rating', { rating: s.rating.toFixed(1) }) : null,
    ].filter(Boolean).join(' · ');
    const items: SearchSuggestion[] = query.trim() ? (suggest.data?.items ?? []).map((s, i) => {
      const id = `s${i}-${s.type}-${s.id}`;
      byId.set(id, { href: suggestionHref(s), remember: s.text });
      const meta = s.type === 'category' ? kindLabel(s.id)
        : s.type === 'merchant' ? merchantMeta(s)
        : [s.merchantName, s.priceCents != null ? money(s.priceCents) : null].filter(Boolean).join(' · ');
      return { id, text: s.text, highlight: s.highlight, meta, icon: ICONS[s.type] };
    }) : [];
    const mine: SearchSuggestion[] = recent.filter(r => r.q.toLowerCase() !== query.trim().toLowerCase()).slice(0, RECENT_SHOWN).map(r => {
      const id = `r-${r.q}`;
      const days = daysAgo(r.at);
      byId.set(id, { href: `/search?q=${encodeURIComponent(r.q)}`, remember: r.q });
      const text = t('recent', { q: r.q });
      const prefix = text.length - r.q.length;
      return {
        id, text, icon: ICONS.recent,
        highlight: matchOf(r.q, query).map(m => ({ ...m, start: m.start + prefix })),
        meta: days === 0 ? t('searchedToday') : days === 1 ? t('searchedYesterday') : t('searchedDaysAgo', { days }),
      };
    });
    const groups: SearchSuggestionGroup[] = [{ id: 'suggestions', label: t('suggestions'), items }, { id: 'recent', label: t('yourRecent'), items: mine }];
    return { groups, byId };
  }, [query, suggest.data, recent, t, money]);

  const search = (q: string) => {
    if (!q) return;
    rememberSearch(q);
    setRecent(readRecent());
    void navigate({ to: '/search', search: { q } });
  };
  const pick = (s: SearchSuggestion) => {
    const target = byId.get(s.id);
    if (!target) return;
    if (target.remember) rememberSearch(target.remember);
    setRecent(readRecent());
    setQuery(target.remember && target.href.startsWith('/search') ? target.remember : '');
    void navigate({ href: target.href });
  };

  return (
    <SearchBar variant={variant} value={query} onChange={setQuery} placeholder={placeholder}
      suggestions={groups} onPick={pick} onSubmit={search} />
  );
}
