import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MERCHANT, storefront } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import { SectionList } from './SectionList';
import { PageBuilder } from './PageBuilder';
import { storefrontQuery, type Storefront } from './api';
import { DEFAULT_ORDER } from './sections';

/** user-event with no timer between keystrokes: typing costs one pass, not a macrotask per character. */
const user = () => userEvent.setup({ delay: null });

beforeEach(() => vi.unstubAllGlobals());

function renderBuilder(s: Storefront = storefront(), canEdit = true) {
  const calls = mockFetch(call => {
    if (call.url.endsWith('/storefront/sections') && call.method === 'PATCH') {
      const list = (call.body as { sections: { kind: string; enabled: boolean }[] }).sections;
      return { body: { ...s, sections: list.map((x, i) => ({ id: `S${i}`, kind: x.kind, position: i, enabled: x.enabled, required: x.kind === 'hero' || x.kind === 'cta', settings: {} })) } };
    }
    if (call.url.endsWith('/storefront') && call.method === 'PATCH') {
      const body = call.body as Record<string, string>;
      if (body.customDomain === 'taken.example.ca') return { status: 422, body: { errors: [{ field: 'customDomain', rule: 'unique', message: 'That domain is already connected to another page.' }] } };
      return { body: { ...s, ...body } };
    }
    if (call.url.includes('/listings')) return { body: { items: [] } };
    return undefined;
  });
  const r = renderWithProviders(<PageBuilder storefront={s} canEdit={canEdit} variant="onboarding" />);
  r.client.setQueryData(storefrontQuery(MERCHANT).queryKey, s);
  return { ...r, calls };
}

describe('SectionList', () => {
  const sections = storefront().sections;

  it('moves a section down with the arrow and emits the full ordered list', async () => {
    const onChange = vi.fn();
    renderWithProviders(<SectionList sections={sections} selected="hero" onSelect={() => {}} onChange={onChange} />);
    await user().click(screen.getByRole('button', { name: 'Move down · About' }));
    expect(onChange).toHaveBeenCalledWith(
      ['hero', 'services', 'about', 'reviews', 'area', 'gallery', 'faq', 'cta'].map(kind => ({ kind, enabled: true })),
      { kind: 'about', to: 2 },
    );
  });

  it('keeps the header and the Book / order button always on; others toggle', async () => {
    const onChange = vi.fn();
    renderWithProviders(<SectionList sections={sections} selected="hero" onSelect={() => {}} onChange={onChange} />);
    expect((screen.getByRole('switch', { name: 'Header & verified badges' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('switch', { name: 'Book / order button' }) as HTMLButtonElement).disabled).toBe(true);
    await user().click(screen.getByRole('switch', { name: 'Work photos' }));
    expect(onChange.mock.calls[0]![0].find((s: { kind: string }) => s.kind === 'gallery')).toEqual({ kind: 'gallery', enabled: false });
  });

  it('first row cannot move up, last cannot move down', () => {
    renderWithProviders(<SectionList sections={sections} selected="hero" onSelect={() => {}} onChange={() => {}} />);
    expect((screen.getByRole('button', { name: 'Move up · Header & verified badges' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Move down · Book / order button' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('reorders by drag and drop', () => {
    const onChange = vi.fn();
    renderWithProviders(<SectionList sections={sections} selected="hero" onSelect={() => {}} onChange={onChange} />);
    const rows = screen.getAllByRole('listitem');
    const dataTransfer = { effectAllowed: '', setData: () => {} };
    fireEvent.dragStart(rows[6]!, { dataTransfer });
    fireEvent.dragOver(rows[1]!, { dataTransfer });
    fireEvent.drop(rows[1]!, { dataTransfer });
    expect(onChange.mock.calls[0]![0].map((s: { kind: string }) => s.kind)).toEqual(['hero', 'faq', 'about', 'services', 'reviews', 'area', 'gallery', 'cta']);
  });
});

describe('PageBuilder', () => {
  it('shows the spec texts of the selected section and the CTA label options', async () => {
    renderBuilder();
    await user().click(screen.getByRole('button', { name: /^Book \/ order button/ }));
    expect(screen.getByText('A single primary button that follows the customer as they scroll.')).toBeTruthy();
    const labels = screen.getByRole('radiogroup', { name: 'Button label' });
    expect(within(labels).getAllByRole('radio').map(r => r.textContent)).toEqual(['Book a visit', 'Request a quote', 'Order now', 'Reserve']);
  });

  it('saves a swatch at once and reports the contrast', async () => {
    const { calls } = renderBuilder();
    expect(screen.getByText('White text contrast 7.6:1 · passes AA')).toBeTruthy();
    await user().click(screen.getByRole('radio', { name: 'Rust' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PATCH')?.body).toEqual({ brandColor: '#9a4a1f' }));
  });

  it('reorders with one PATCH of the full list, and resets to the recommended order', async () => {
    const { calls } = renderBuilder(storefront({ sections: storefront().sections.map(s => (s.kind === 'faq' ? { ...s, enabled: false } : s)) }));
    await user().click(screen.getByRole('button', { name: 'Reset to recommended order' }));
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/storefront/sections'))).toBe(true));
    const body = calls.find(c => c.url.endsWith('/storefront/sections'))!.body as { sections: { kind: string; enabled: boolean }[] };
    expect(body.sections).toEqual(DEFAULT_ORDER.provider.map(kind => ({ kind, enabled: true })));
  });

  it('validates the tagline (≤ 80) before saving', async () => {
    const { calls } = renderBuilder();
    const input = screen.getByRole('textbox', { name: /Tagline/ });
    await user().clear(input);
    await user().type(input, 'x'.repeat(81));
    fireEvent.blur(input);
    expect(screen.getByRole('alert').textContent).toContain('At most 80 characters.');
    expect(calls.filter(c => c.method === 'PATCH')).toHaveLength(0);
  });

  it('maps a server 422 onto the custom domain field', async () => {
    renderBuilder();
    const input = screen.getByRole('textbox', { name: 'Custom domain (optional)' });
    await user().type(input, 'taken.example.ca');
    fireEvent.blur(input);
    expect(await screen.findByText('That domain is already connected to another page.')).toBeTruthy();
  });

  it('is read-only for staff', () => {
    renderBuilder(storefront(), false);
    expect((screen.getByRole('radio', { name: 'Forest' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Reset to recommended order' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('switch', { name: 'About' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('previews only enabled sections, in order, with the CTA label', () => {
    renderBuilder(storefront({ sections: storefront().sections.map(s => (s.kind === 'gallery' ? { ...s, enabled: false } : s)), ctaLabel: 'request_quote' }));
    const preview = screen.getByRole('region', { name: 'Live preview · what customers see' });
    expect(within(preview).queryByText('Work photos')).toBeNull();
    expect(within(preview).getByText('Request a quote')).toBeTruthy();
    expect(within(preview).getByText('AMVIC')).toBeTruthy();
  });
});
