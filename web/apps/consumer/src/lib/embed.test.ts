import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { waitFor } from '@testing-library/react';

// S-76: public/embed.js, the script a business pastes on its own website.
const source = readFileSync(resolve(__dirname, '../../public/embed.js'), 'utf8');
const page = { slug: 'prairie-wrench', pageKind: 'business_page', name: 'Prairie Wrench', brandColor: '#2f5d3a', ctaLabel: 'book_visit', path: '/providers/prairie-wrench' };

function snippet(attrs: Record<string, string>) {
  const host = document.createElement('div');
  const script = document.createElement('script');
  script.setAttribute('src', 'https://site.example/embed.js');
  for (const [k, v] of Object.entries(attrs)) script.setAttribute(k, v);
  host.appendChild(script);
  document.body.appendChild(host);
  return host;
}

function serve(body: unknown, status = 200) {
  const fetch = vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status }));
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

const run = () => new Function(source)();

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; document.documentElement.lang = ''; });

describe('website embed script (S-76)', () => {
  it('asks for the page with the key, without cookies, and shows its button linking to the page', async () => {
    const fetch = serve(page);
    const host = snippet({ 'data-store': 'prairie-wrench', 'data-key': 'pk_live_abc' });
    run();
    await waitFor(() => expect(host.querySelector('a.northline-embed')).not.toBeNull());
    expect(fetch).toHaveBeenCalledWith('https://site.example/api/v1/public/embed?key=pk_live_abc&store=prairie-wrench', expect.objectContaining({ credentials: 'omit' }));
    const a = host.querySelector('a.northline-embed') as HTMLAnchorElement;
    expect(a.href).toBe('https://site.example/providers/prairie-wrench');
    expect(a.textContent).toBe('Book a visit');
    expect(a.title).toBe('Prairie Wrench on Northline');
    expect(a.rel).toBe('noopener');
    expect(a.style.background).toContain('rgb(47, 93, 58)');
    expect(a.style.color).toBe('rgb(255, 255, 255)');
  });

  it('follows the page language and the call to action', async () => {
    serve({ ...page, ctaLabel: 'order_now', brandColor: '#f2d16b', path: '/food/prairie-wrench' });
    document.documentElement.lang = 'fr-CA';
    const host = snippet({ 'data-store': 'prairie-wrench', 'data-key': 'pk_live_abc' });
    run();
    await waitFor(() => expect(host.querySelector('a')).not.toBeNull());
    expect(host.querySelector('a')!.textContent).toBe('Commander');
    expect(host.querySelector('a')!.title).toBe('Prairie Wrench sur Northline');
    expect(host.querySelector('a')!.style.color).toBe('rgb(17, 17, 17)'); // dark text on a light brand colour
  });

  it('shows nothing for an inactive key or another website, and mounts each snippet once', async () => {
    const fetch = serve({ detail: 'nope' }, 404);
    const host = snippet({ 'data-store': 'prairie-wrench', 'data-key': 'pk_live_old' });
    run();
    run();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    await new Promise(r => setTimeout(r, 0));
    expect(host.querySelector('a')).toBeNull();
  });

  it('ignores a tag without a key', () => {
    const fetch = serve(page);
    snippet({ 'data-store': 'prairie-wrench' });
    run();
    expect(fetch).not.toHaveBeenCalled();
  });
});
