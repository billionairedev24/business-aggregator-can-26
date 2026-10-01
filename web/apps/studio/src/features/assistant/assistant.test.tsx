import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/ops';
import { AssistantButton, AssistantDrawer } from './AssistantDrawer';
import { InsightCard } from './InsightCard';
import { sseFrames } from './api';

const navigate = vi.fn();
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate, useRouterState: () => '/b/m1/orders' }));

const sse = (frames: [string, unknown][]) => frames.map(([e, d]) => `event: ${e}\ndata: ${JSON.stringify(d)}\n\n`).join('');
const answer = (extra: Record<string, unknown> = {}) => ({ content: 'One order to pack: NL-48213.', toolRuns: [{ tool: 'list_orders', summary: 'orders → 1', ok: true }], pending: null, screen: 'orders', model: 'm', usage: null, ...extra });

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
const stream = (text: string) => new Response(text, { status: 200, headers: { 'content-type': 'text/event-stream' } });

afterEach(() => { vi.unstubAllGlobals(); navigate.mockReset(); });

describe('sseFrames', () => {
  it('reassembles frames split across chunks', async () => {
    const enc = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(enc.encode('event: delta\ndata: {"te')); c.enqueue(enc.encode('xt":"Hi"}\n\nevent: done\ndata: {}\n\n')); c.close(); } });
    const out = [];
    for await (const f of sseFrames(body)) out.push(f);
    expect(out).toEqual([{ event: 'delta', data: '{"text":"Hi"}' }, { event: 'done', data: '{}' }]);
  });
});

describe('Assistant drawer', () => {
  it('streams the answer with the tools it used and links to the screen', async () => {
    const calls = stubFetch({ '/assistant/chat/stream': () => stream(sse([['tool', { tool: 'list_orders', summary: 'orders → 1', ok: true }], ['delta', { text: 'One order ' }], ['delta', { text: 'to pack: NL-48213.' }], ['done', answer()]])) });
    renderWithProviders(<AssistantDrawer merchantId="m1" open onClose={() => {}} />);
    await userEvent.type(screen.getByLabelText('Your question'), 'What do I pack?');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText('One order to pack: NL-48213.')).toBeTruthy();
    expect(screen.getByText('Looked at: orders → 1')).toBeTruthy();
    expect(calls[0]!.body).toEqual({ messages: [{ role: 'user', content: 'What do I pack?' }], screen: 'orders' });
    await userEvent.click(screen.getByRole('button', { name: 'Open Orders →' }));
    expect(navigate).toHaveBeenCalledWith({ to: '/b/m1/orders' });
    expect(screen.getByText(/AI-generated\. It can be wrong/)).toBeTruthy();
  });

  it('asks before any change and runs it only once confirmed', async () => {
    const pending = { tool: 'pack_order', arguments: { ref: 'NL-48213' }, preview: 'Mark order NL-48213 as packed' };
    const calls = stubFetch({
      '/assistant/chat/stream': () => stream(sse([['done', answer({ content: '', toolRuns: [{ tool: 'pack_order', summary: 'Mark order NL-48213 as packed', ok: true }], pending })]])),
      '/assistant/actions': () => json({ tool: 'pack_order', summary: 'packed NL-48213', screen: 'orders' }),
    });
    renderWithProviders(<AssistantDrawer merchantId="m1" open onClose={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'What do I have to pack?' }));
    expect(await screen.findByText('Confirm this change?')).toBeTruthy();
    expect(screen.getByText('Mark order NL-48213 as packed', { selector: 'span' })).toBeTruthy();
    expect(calls.some(c => c.url.includes('/actions'))).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Done: packed NL-48213')).toBeTruthy();
    expect(calls.find(c => c.url.includes('/actions'))!.body).toEqual({ tool: 'pack_order', arguments: { ref: 'NL-48213' } });
  });

  it('cancelling changes nothing', async () => {
    const pending = { tool: 'pack_order', arguments: { ref: 'NL-48213' }, preview: 'Mark order NL-48213 as packed' };
    const calls = stubFetch({ '/assistant/chat/stream': () => stream(sse([['done', answer({ content: '', pending })]])) });
    renderWithProviders(<AssistantDrawer merchantId="m1" open onClose={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'What do I have to pack?' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(screen.getByText('Cancelled — nothing changed.')).toBeTruthy();
    expect(calls.some(c => c.url.includes('/actions'))).toBe(false);
  });

  it('explains a 429 and a 503 in plain words', async () => {
    let status = 429;
    stubFetch({ '/assistant/chat/stream': () => json({ code: status === 429 ? 'ai_rate_limited' : 'ai_unavailable', detail: 'x' }, status) });
    renderWithProviders(<AssistantDrawer merchantId="m1" open onClose={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'When is my next payout?' }));
    expect(await screen.findByText('You’ve reached the assistant’s limit for now. Try again later.')).toBeTruthy();
    status = 503;
    await userEvent.type(screen.getByLabelText('Your question'), 'again{Enter}');
    expect(await screen.findByText('The assistant isn’t available right now.')).toBeTruthy();
  });

  it('speaks French', async () => {
    stubFetch({});
    renderWithProviders(<AssistantDrawer merchantId="m1" open onClose={() => {}} />, 'fr');
    expect(screen.getByLabelText('Votre question')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Envoyer' })).toBeTruthy();
    expect(screen.getByText(/Généré par l’IA/)).toBeTruthy();
  });
});

describe('Assistant button', () => {
  it('is hidden when no model is configured', async () => {
    const calls = stubFetch({ '/api/v1/ai/status': () => json({ available: false, provider: 'openrouter' }) });
    renderWithProviders(<AssistantButton merchantId="m1" />);
    await waitFor(() => expect(calls.length).toBe(1));
    expect(screen.queryByRole('button', { name: 'Assistant' })).toBeNull();
  });

  it('opens the drawer and labels the test model', async () => {
    stubFetch({ '/api/v1/ai/status': () => json({ available: true, provider: 'fake' }) });
    renderWithProviders(<AssistantButton merchantId="m1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Assistant' }));
    expect(screen.getByRole('dialog', { name: 'Assistant' })).toBeTruthy();
    expect(screen.getByText('Test model: answers are canned, not real.')).toBeTruthy();
  });
});

describe('Insight card', () => {
  it('asks only when the person clicks, and labels the result AI-generated', async () => {
    const calls = stubFetch({
      '/api/v1/ai/status': () => json({ available: true, provider: 'openrouter' }),
      '/assistant/insights/earnings': () => json({ title: '$2,140.60 releasing Friday', body: '11 jobs are in escrow.', bullets: ['Reply to the dispute'], model: 'm' }),
    });
    renderWithProviders(<InsightCard merchantId="m1" screen="earnings" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Explain this screen' }));
    expect(await screen.findByRole('heading', { name: '$2,140.60 releasing Friday' })).toBeTruthy();
    expect(screen.getByText('Reply to the dispute')).toBeTruthy();
    expect(screen.getByText(/AI-generated from this screen’s data/)).toBeTruthy();
    expect(calls.filter(c => c.url.includes('/insights/')).length).toBe(1);
  });
});
