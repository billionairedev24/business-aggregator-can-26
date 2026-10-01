import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/ops';
import { ListingCopyButton, QuoteLineSuggestions, ReplySuggestions, ReviewSummaryDraftPanel } from './WritingHelp';

function stubFetch(routes: Record<string, (body: unknown) => Response>) {
  const calls: { url: string; body: unknown }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : undefined;
    calls.push({ url, body });
    const key = Object.keys(routes).find(k => url.includes(k));
    return key ? routes[key]!(body) : new Response('{"detail":"not mocked"}', { status: 404 });
  }));
  return calls;
}
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } });
const on = { '/api/v1/ai/status': () => json({ available: true, provider: 'openrouter' }) };

afterEach(() => vi.unstubAllGlobals());

describe('Listing copy', () => {
  const draft = { en: { title: 'Brake inspection', description: 'A full check.', bullets: ['Written report'] }, fr: { title: 'Inspection des freins', description: 'Une vérification complète.', bullets: ['Rapport écrit'] }, aiAssisted: true, model: 'm' };

  it('shows both drafts as AI-assisted and fills the fields only with the one picked', async () => {
    const calls = stubFetch({ ...on, '/listing-copy': () => json(draft) });
    const onUse = vi.fn();
    renderWithProviders(<ListingCopyButton merchantId="m1" facts={{ kind: 'service', name: 'Brake inspection', categoryId: '', included: ' ' }} onUse={onUse} />);
    await userEvent.click(await screen.findByRole('button', { name: /Draft with AI/ }));
    expect(await screen.findByRole('dialog', { name: 'AI-assisted draft' })).toBeTruthy();
    expect(screen.getByText(/every listing is still vetted by Northline/)).toBeTruthy();
    expect(screen.getByText('Inspection des freins')).toBeTruthy();
    expect(onUse).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Use the French draft' }));
    expect(onUse).toHaveBeenCalledWith(draft.fr);
    expect(calls.find(c => c.url.includes('/listing-copy'))!.body).toEqual({ kind: 'service', name: 'Brake inspection' });
  });

  it('shows the server’s 422 message', async () => {
    stubFetch({ ...on, '/listing-copy': () => json({ errors: [{ field: 'name', rule: 'required', message: 'Add a name or a category first.' }] }, 422) });
    renderWithProviders(<ListingCopyButton merchantId="m1" facts={{ kind: 'service' }} onUse={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: /Draft with AI/ }));
    expect(await screen.findByText('Add a name or a category first.')).toBeTruthy();
  });

  it('is hidden when AI is off', async () => {
    stubFetch({ '/api/v1/ai/status': () => json({ available: false, provider: 'openrouter' }) });
    renderWithProviders(<ListingCopyButton merchantId="m1" facts={{ kind: 'service' }} onUse={() => {}} />);
    await new Promise(r => setTimeout(r, 20));
    expect(screen.queryByRole('button', { name: /Draft with AI/ })).toBeNull();
  });
});

describe('Quote lines', () => {
  it('adds the suggested lines without prices and shows the questions', async () => {
    stubFetch({ ...on, '/line-suggestions': () => json({ lines: [{ kind: 'labour', description: 'Brake inspection', qty: 1 }, { kind: 'part', description: 'Front pads', qty: 1 }], questions: ['Front or back?'], aiAssisted: true }) });
    const onAdd = vi.fn();
    renderWithProviders(<QuoteLineSuggestions merchantId="m1" requestId="r1" onAdd={onAdd} />);
    await userEvent.click(await screen.findByRole('button', { name: /Suggest lines/ }));
    expect(await screen.findByText(/2 AI-suggested lines added — set the prices/)).toBeTruthy();
    expect(screen.getByText('Front or back?')).toBeTruthy();
    expect(onAdd).toHaveBeenCalledWith([{ kind: 'labour', description: 'Brake inspection', qty: 1 }, { kind: 'part', description: 'Front pads', qty: 1 }]);
  });
});

describe('Reply suggestions', () => {
  it('a pick only fills the box', async () => {
    const calls = stubFetch({ ...on, '/reply-suggestions': () => json({ replies: ['Payments go through Northline.', 'We’ll check and get back to you.'], aiAssisted: true }) });
    const onPick = vi.fn();
    renderWithProviders(<ReplySuggestions merchantId="m1" threadId="t1" onPick={onPick} />);
    await userEvent.click(await screen.findByRole('button', { name: /Suggest replies/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Payments go through Northline.' }));
    expect(onPick).toHaveBeenCalledWith('Payments go through Northline.');
    expect(calls.filter(c => c.url.includes('/messages')).length).toBe(0);
  });

  it('explains a 429', async () => {
    stubFetch({ ...on, '/reply-suggestions': () => json({ code: 'ai_rate_limited' }, 429) });
    renderWithProviders(<ReplySuggestions merchantId="m1" threadId="t1" onPick={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: /Suggest replies/ }));
    expect(await screen.findByText('You’ve reached the AI limit for now. Try again later.')).toBeTruthy();
  });
});

describe('Review summary', () => {
  it('shows both languages as an AI-assisted draft, in French too', async () => {
    stubFetch({ ...on, '/summary-draft': () => json({ en: { summary: 'Customers praise clear explanations.', themes: ['clear'] }, fr: { summary: 'Les clients apprécient les explications claires.', themes: ['clair'] }, reviews: 12, aiAssisted: true }) });
    renderWithProviders(<ReviewSummaryDraftPanel merchantId="m1" />, 'fr');
    await userEvent.click(await screen.findByRole('button', { name: /Résumer les avis/ }));
    expect(await screen.findByText('Résumé assisté par l’IA')).toBeTruthy();
    expect(screen.getByText(/Rédigé à partir de vos 12 derniers avis vérifiés/)).toBeTruthy();
    expect(screen.getByText('Les clients apprécient les explications claires.')).toBeTruthy();
  });
});
