import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, screen, waitFor, within } from 'expo-router/testing-library';

import { setServices } from '../src/services';
import { chipLabel, looksNatural, proposals, withProposals } from '../src/shop/smartSearch';
import { start } from './support';

afterEach(async () => {
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

const HOME = { label: '1204 Example Ave, Sampleville', city: 'Sampleville', province: 'XA', street: '1204 Example Ave', postalCode: 'A1A 1A1', marketId: 'mkt-sampleville' };
const saved = { 'nl.location': JSON.stringify(HOME) };
const spy = (urls: string[]) => (f: typeof fetch) => (async (i: RequestInfo | URL, init?: RequestInit) => (urls.push(String(i)), f(i, init))) as typeof fetch;

describe('S-158 natural-language search on the phone', () => {
  it('shows the AI’s filters as chips, applies them only on request, and keeps them removable', async () => {
    const urls: string[] = [];
    await start({ welcomed: true, store: saved, url: '/search?q=halal%20ribeye%20tonight', wrap: spy(urls) });
    const box = await screen.findByTestId('smart-filters');
    expect(within(box).getByText('Searching for: halal, ribeye, on tonight’s run')).toBeTruthy();
    expect(within(box).getByText('Words: “ribeye”')).toBeTruthy();
    expect(within(box).getByText('Halal')).toBeTruthy();
    expect(within(box).getByText('On tonight’s run')).toBeTruthy();
    // nothing applied yet: the search is the words
    expect(urls.filter((u) => u.includes('/search?')).every((u) => !new URL(u).searchParams.has('dietary'))).toBe(true);
    fireEvent.press(within(box).getByRole('button', { name: 'Remove filter: On tonight’s run' }));
    fireEvent.press(screen.getByTestId('smart-apply'));
    await waitFor(() => {
      const last = new URL(urls.filter((u) => u.includes('/search?')).at(-1)!);
      expect(Object.fromEntries(last.searchParams)).toMatchObject({ q: 'ribeye', dietary: 'halal' });
      expect(last.searchParams.has('delivery')).toBe(false);
    });
    expect(await screen.findByText('Ribeye, AAA')).toBeTruthy();
    const applied = screen.getByTestId('smart-applied');
    fireEvent.press(within(applied).getByRole('button', { name: 'Remove filter: Halal' }));
    await waitFor(() => expect(new URL(urls.filter((u) => u.includes('/search?')).at(-1)!).searchParams.has('dietary')).toBe(false));
  });

  it('says so when AI is unavailable (503) and searches the words', async () => {
    const down = (f: typeof fetch) =>
      (async (i: RequestInfo | URL, init?: RequestInit) =>
        String(i).includes('/search/interpret')
          ? new Response(JSON.stringify({ code: 'ai_unavailable' }), { status: 503, headers: { 'content-type': 'application/json' } })
          : f(i, init)) as typeof fetch;
    await start({ welcomed: true, store: saved, url: '/search?q=halal%20ribeye%20tonight', wrap: down });
    expect(await screen.findByTestId('smart-unavailable')).toBeTruthy();
  });

  it('pure helpers', () => {
    expect(looksNatural('gluten-free bread tonight')).toBe(true);
    expect(looksNatural('bread')).toBe(false);
    const chips = proposals({ q: 'bread', kind: ['product'], maxPrice: 999, dietary: ['gluten_free'], sort: 'distance', explanation: 'x' });
    expect(chips.map((c) => c.key)).toEqual(['q', 'kind', 'maxPrice', 'dietary']);
    expect(withProposals({ q: 'gluten free bread', sort: 'relevance' }, chips)).toMatchObject({ q: 'bread', kind: 'product', maxPrice: 999, dietary: ['gluten_free'] });
    const t = ((k: string, v?: Record<string, unknown>) => `${k}${v ? JSON.stringify(v) : ''}`) as never;
    expect(chipLabel({ id: 'a', key: 'allergenFree', value: 'tree_nuts' }, t, (c) => `$${c / 100}`)).toBe('ai.chip.free{"allergen":"tree nuts"}');
  });
});
