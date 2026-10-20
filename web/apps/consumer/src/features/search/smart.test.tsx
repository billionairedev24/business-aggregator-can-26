import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSearch } from '@tanstack/react-router';
import { mockFetch, renderApp, type Call } from '../../test/render';
import { applyProposals, looksNatural, proposals, SearchParams, type Interpretation } from './api';
import { SearchResults } from './SearchResults';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const READING: Interpretation = {
  q: 'bakery', kind: ['product'], minPrice: null, maxPrice: 2499, minRating: null, tier: [], instantBook: null, openNow: true, delivery: null,
  dietary: ['gluten_free'], allergenFree: ['peanuts'], radiusKm: null, sort: null, explanation: 'gluten-free bakeries open now', aiAssisted: true,
};
type Reply = { status?: number; body?: unknown } | undefined;
let api: (call: Call) => Reply;
const server = (call: Call): Reply => {
  if (call.url === '/bff/session') return { body: { user: null, guestId: 'g_x' } };
  if (call.url.startsWith('/api/v1/geo/markets')) return { body: { items: [], fallback: null } };
  if (call.url === '/api/v1/ai/status') return { body: { available: true, provider: 'fake' } };
  if (call.url.startsWith('/api/v1/search?')) return { body: { items: [], total: 0, facets: null, next: null } };
  return api(call);
};
function Route() {
  return <SearchResults params={SearchParams.parse(useSearch({ strict: false }))} />;
}
const open = (path: string, locale: 'en' | 'fr' = 'en') => {
  const calls = mockFetch(server);
  const view = renderApp(path, { locale, routes: { search: () => <Route /> } });
  return { calls, ...view };
};
const searches = (calls: Call[]) => calls.filter(c => c.url.startsWith('/api/v1/search?')).map(c => new URL(c.url, 'http://x').searchParams);

beforeEach(() => { localStorage.clear(); sessionStorage.clear(); api = c => (c.url === '/api/v1/search/interpret' ? { body: READING } : undefined); });
afterEach(() => vi.unstubAllGlobals());

describe('natural-language search (S-158 on S-132)', () => {
  it('shows the AI’s filters as removable chips and applies them only when asked', async () => {
    const { calls } = open('/search?q=gluten-free%20bakery%20open%20now%20near%20me');
    const panel = await screen.findByRole('region', { name: 'Suggested filters' });
    expect(within(panel).getByText('Searching for: gluten-free bakeries open now')).toBeTruthy();
    for (const chip of ['Words: “bakery”', 'Shop', 'Up to $24.99', 'Open now', 'Gluten-free', 'No peanuts']) expect(within(panel).getByText(chip)).toBeTruthy();
    // not applied on its own: the search so far is the words
    expect(searches(calls).every(p => !p.has('dietary') && !p.has('openNow'))).toBe(true);
    expect(calls.find(c => c.url === '/api/v1/search/interpret')?.body).toEqual({ text: 'gluten-free bakery open now near me', hasLocation: false, lang: 'en' });
    await expectNoAxeViolations(document.body);
    const u = userEvent.setup({ delay: null });
    await u.click(within(panel).getByRole('button', { name: 'Remove No peanuts' }));
    await u.click(within(panel).getByRole('button', { name: 'Apply these filters' }));
    await waitFor(() => {
      const last = searches(calls).at(-1)!;
      expect(Object.fromEntries(last)).toMatchObject({ q: 'bakery', kind: 'product', maxPrice: '2499', openNow: 'true', dietary: 'gluten_free' });
      expect(last.has('allergenFree')).toBe(false);
    });
  });

  it('falls back to the words when AI is unavailable (503), in French', async () => {
    api = c => (c.url === '/api/v1/search/interpret' ? { status: 503, body: { code: 'ai_unavailable' } } : undefined);
    open('/search?q=pain%20sans%20gluten%20ce%20soir', 'fr');
    expect(await screen.findByText('La recherche intelligente n’est pas disponible pour l’instant. Voici les résultats pour vos mots.')).toBeTruthy();
  });

  it('asks nothing for a short query, when AI is off, or once filters are on', async () => {
    const short = open('/search?q=sourdough');
    await screen.findByRole('heading', { level: 1 });
    expect(short.calls.some(c => c.url === '/api/v1/search/interpret')).toBe(false);
    vi.unstubAllGlobals();
    const filtered = open('/search?q=gluten-free%20bakery%20open%20now&openNow=true');
    await screen.findAllByRole('heading', { level: 1 });
    expect(filtered.calls.some(c => c.url === '/api/v1/search/interpret')).toBe(false);
  });

  it('maps the reading to the page’s own parameters', () => {
    expect(looksNatural('vegan brunch tonight')).toBe(true);
    expect(looksNatural('pho')).toBe(false);
    const chips = proposals({ ...READING, kind: ['product', 'service'], sort: 'relevance', tier: ['platinum'], radiusKm: 4.6 });
    expect(chips.map(c => c.key)).toEqual(['q', 'maxPrice', 'openNow', 'dietary', 'allergenFree', 'radiusKm']);
    expect(applyProposals(chips.filter(c => c.key !== 'q'), 'gluten free bakery')).toMatchObject({ q: 'gluten free bakery', radiusKm: 5, dietary: 'gluten_free' });
  });
});
