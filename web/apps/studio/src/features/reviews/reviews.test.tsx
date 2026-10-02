import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ago, D, M, renderScreen, stubFetch } from '../messages/testing';

const shell = vi.hoisted(() => ({ role: 'owner' }));
vi.mock('../shell/api', () => ({
  useMerchantId: () => '01J9ZD3V00000000000000PWM1',
  useMerchant: () => ({ id: '01J9ZD3V00000000000000PWM1', displayName: 'Prairie Wrench', type: 'provider', tier: 'master', status: 'active', role: shell.role }),
  useRole: () => shell.role,
}));

import { ReviewsScreen } from './ReviewsScreen';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

const base = `/api/v1/merchants/${M}/reviews`;
const SUMMARY = {
  average: 4.9, count: 312,
  distribution: [{ stars: 5, count: 279, percent: 89 }, { stars: 4, count: 24, percent: 8 }, { stars: 3, count: 6, percent: 2 }, { stars: 2, count: 2, percent: 1 }, { stars: 1, count: 1, percent: 0 }],
  praise: [{ tag: 'on_time', percent: 88 }, { tag: 'clear_explanation', percent: 81 }, { tag: 'fair_price', percent: 76 }],
};
const review = (id: string, over: Record<string, unknown>) => ({ id, rating: 5, authorName: 'X', jobLabel: 'job', refType: 'booking', text: null, tags: [], createdAt: ago(3 * D), reply: null, replyAt: null, reportedAt: null, reportReason: null, ...over });
const DANA = review('r1', { authorName: 'Dana K.', jobLabel: 'alternator', text: 'Showed up at 7 am in −22°, fixed the alternator in the parkade, receipt in the app before he left.', reply: 'Thanks Dana — see you for the pads in spring.' });
const TRAN = review('r2', { rating: 4, authorName: 'M. Tran', jobLabel: 'diagnostic', createdAt: ago(7 * D), text: 'Thorough, but arrived 20 minutes late. Explained everything clearly.' });
const BOUCHARD = review('r3', { authorName: 'S. Bouchard', jobLabel: 'oil & filter', createdAt: ago(14 * D), text: 'Fair price, parts at cost as promised. Booked the winter tire swap on the spot.' });
const routes = (extra: Record<string, unknown> = {}) => ({ [`GET ${base}/summary`]: SUMMARY, [`GET ${base}`]: { items: [DANA, TRAN, BOUCHARD], nextOffset: 3 }, ...extra });

beforeEach(() => { shell.role = 'owner'; });
afterEach(() => vi.unstubAllGlobals());

describe('ReviewsScreen', () => {
  it('shows the design summary and reviews', async () => {
    stubFetch(routes());
    renderScreen(<ReviewsScreen />);
    expect(await screen.findByRole('heading', { name: '4.9 from 312 verified customers' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    expect(screen.getByText('Praise tags: On time 88% · Clear explanation 81% · Fair price 76%')).toBeTruthy();
    expect(screen.getByText('5 stars: 279')).toBeTruthy();
    expect(screen.getByText(/Dana K\. · verified alternator/)).toBeTruthy();
    expect(screen.getByText('3 d')).toBeTruthy();
    expect(screen.getByText('1 w')).toBeTruthy();
    expect(screen.getByText('You replied: “Thanks Dana — see you for the pads in spring.”')).toBeTruthy();
    expect(screen.getAllByPlaceholderText('Reply publicly (customers see this)')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Show more reviews' })).toBeTruthy();
  });

  it('reply: rule after submit, then a public reply', async () => {
    const calls = stubFetch(routes({ [`POST ${base}/r2/reply`]: (body: unknown) => ({ json: { ...TRAN, reply: (body as { text: string }).text, replyAt: ago(0) } }) }));
    renderScreen(<ReviewsScreen />);
    const box = await screen.findByRole('textbox', { name: 'Public reply to M. Tran' });
    const form = box.closest('form')!;
    await user().click(within(form).getByRole('button', { name: 'Reply' }));
    expect(within(form).getByRole('alert').textContent).toBe('Write a reply before sending.');
    await user().type(box, 'Sorry about the wait — thanks for the patience.');
    await user().click(within(form).getByRole('button', { name: 'Reply' }));
    expect(await screen.findByText('You replied: “Sorry about the wait — thanks for the patience.”')).toBeTruthy();
    expect(calls.find(c => c.key === `POST ${base}/r2/reply`)?.body).toEqual({ text: 'Sorry about the wait — thanks for the patience.' });
  });

  it('report: reason and note rules with the summary, then the review shows as reported', async () => {
    const calls = stubFetch(routes({ [`POST ${base}/r3/report`]: () => ({ json: { ...BOUCHARD, reportedAt: ago(0), reportReason: 'other' } }) }));
    renderScreen(<ReviewsScreen />);
    await screen.findByText(/S\. Bouchard/);
    await user().click(screen.getAllByRole('button', { name: 'Report' })[2]!);
    const dialog = await screen.findByRole('dialog');
    await user().click(within(dialog).getByRole('button', { name: 'Send report' }));
    expect(within(dialog).getByText('1 thing needs attention.')).toBeTruthy();
    expect(within(dialog).getByText('Choose a reason.')).toBeTruthy();
    await user().click(within(dialog).getByRole('button', { name: 'Something else' }));
    expect(within(dialog).getByText("Tell us what's wrong with this review.")).toBeTruthy();
    await user().type(within(dialog).getByRole('textbox'), 'This customer never booked us.');
    await user().click(within(dialog).getByRole('button', { name: 'Send report' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByText('Reported · under review')).toBeTruthy();
    expect(calls.find(c => c.key === `POST ${base}/r3/report`)?.body).toEqual({ reason: 'other', note: 'This customer never booked us.' });
  });

  it('bookkeepers read only', async () => {
    shell.role = 'bookkeeper';
    stubFetch(routes());
    renderScreen(<ReviewsScreen />);
    expect(await screen.findByText('View only · Bookkeeper')).toBeTruthy();
    expect(screen.queryByPlaceholderText('Reply publicly (customers see this)')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Report' })).toBeNull();
  });

  it('empty and error states', async () => {
    stubFetch({ [`GET ${base}/summary`]: { average: 0, count: 0, distribution: [], praise: [] }, [`GET ${base}`]: { items: [], nextOffset: null } });
    const { unmount } = renderScreen(<ReviewsScreen />);
    expect(await screen.findByRole('heading', { name: 'No reviews yet' })).toBeTruthy();
    unmount();
    stubFetch({ [`GET ${base}/summary`]: () => ({ status: 500, json: {} }), [`GET ${base}`]: { items: [] } });
    renderScreen(<ReviewsScreen />);
    expect(await screen.findByText("We couldn't load your reviews.")).toBeTruthy();
  });

  it('French: decimal comma', async () => {
    stubFetch(routes());
    renderScreen(<ReviewsScreen />, 'fr');
    expect(await screen.findByRole('heading', { name: '4,9 de 312 clients vérifiés' })).toBeTruthy();
  });
});
