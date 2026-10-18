import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { storefront } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import { PublicPageForm } from './PublicPageForm';
import type { Storefront } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const user = () => userEvent.setup({ delay: null });
beforeEach(() => vi.unstubAllGlobals());

function setup(s: Storefront = storefront(), canEdit = true) {
  const calls = mockFetch(call => {
    if (call.url.endsWith('/storefront/sections') && call.method === 'PATCH') {
      const list = (call.body as { sections: { kind: string; enabled: boolean }[] }).sections;
      return { body: { ...s, sections: list.map((x, i) => ({ id: `S${i}`, kind: x.kind, position: i, enabled: x.enabled, required: x.kind === 'hero' || x.kind === 'cta', settings: {} })) } };
    }
    if (call.url.endsWith('/storefront') && call.method === 'PATCH') return { body: { ...s, ...(call.body as object) } };
    if (call.url.includes('/listings')) return { body: { items: [] } };
    return undefined;
  });
  renderWithProviders(<PublicPageForm storefront={s} canEdit={canEdit}><p>publish</p></PublicPageForm>);
  return calls;
}

describe('Public page form (redesign § 3.11, PH:171)', () => {
  it('is a form, not the block builder: no reorder, no custom domain; switching a part off saves the list in its order', async () => {
    const calls = setup();
    await expectNoAxeViolations(document.body);
    expect(screen.queryByRole('button', { name: /Move .* up|Move .* down/ })).toBeNull();
    expect(screen.queryByText(/Custom domain/i)).toBeNull();
    const parts = within(screen.getByRole('region', { name: 'What customers see' }));
    expect(parts.getAllByText('Always on')).toHaveLength(2); // hero + call to action
    await user().click(parts.getByRole('switch', { name: 'Show Work photos' }));
    await waitFor(() => expect(calls.some(c => c.method === 'PATCH' && c.url.endsWith('/storefront/sections'))).toBe(true));
    const body = calls.find(c => c.url.endsWith('/storefront/sections'))!.body as { sections: { kind: string; enabled: boolean }[] };
    expect(body.sections.map(x => x.kind)).toEqual(storefront().sections.map(x => x.kind));
    expect(body.sections.find(x => x.kind === 'gallery')!.enabled).toBe(false);
  });

  it('saves the tagline and the button label, and shows the preview', async () => {
    const calls = setup();
    const tagline = screen.getByLabelText(/Tagline/);
    await user().clear(tagline);
    await user().type(tagline, 'Brakes at your door');
    tagline.blur();
    await waitFor(() => expect(calls.find(c => c.method === 'PATCH' && c.url.endsWith('/storefront'))?.body).toEqual({ tagline: 'Brakes at your door' }));
    await user().click(screen.getByRole('radio', { name: 'Request a quote' }));
    await waitFor(() => expect(calls.some(c => (c.body as { ctaLabel?: string } | undefined)?.ctaLabel === 'request_quote')).toBe(true));
    expect(screen.getByRole('complementary', { name: /Live preview/ })).toBeTruthy();
  });

  it('is read-only for everyone but the owner', () => {
    setup(storefront(), false);
    expect((screen.getByLabelText(/Tagline/) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getAllByRole('switch').every(s => (s as HTMLInputElement).disabled)).toBe(true);
  });
});
