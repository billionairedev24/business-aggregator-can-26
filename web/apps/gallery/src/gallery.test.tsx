import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { I18nProvider } from '@northline/ui';
import { Gallery, GALLERY_SECTIONS } from '@northline/ui/gallery';
import { galleryParams } from './params';
import { GALLERY_IDS } from '../visual/sections';

describe('component gallery', () => {
  it.each(['en', 'fr'] as const)('shows every section in %s, each with its heading', locale => {
    render(<I18nProvider initial={locale}><Gallery /></I18nProvider>);
    expect(screen.getAllByRole('heading', { level: 1 })[0]).toHaveTextContent(locale === 'fr' ? 'Galerie des composants' : 'Component gallery');
    for (const s of GALLERY_SECTIONS.filter(x => !x.solo)) {
      const section = document.querySelector<HTMLElement>(`[data-gallery="${s.id}"]`)!;
      expect(section, s.id).not.toBeNull();
      expect(within(section).getAllByRole('heading', { level: 2 })[0]).toHaveAttribute('id', `gallery-${s.id}`);
    }
  });

  it('the back-office shell is shown on its own page (it brings its own banner and main)', () => {
    // jsdom has no matchMedia: a desktop-wide window
    vi.stubGlobal('matchMedia', (media: string) => ({ matches: false, media, addEventListener() {}, removeEventListener() {} }));
    render(<I18nProvider initial="en"><Gallery /></I18nProvider>);
    expect(document.querySelector('[data-gallery="shell"]')).toBeNull();
    render(<I18nProvider initial="en"><Gallery section="shell" /></I18nProvider>);
    const shell = document.querySelector<HTMLElement>('[data-gallery="shell"]')!;
    expect(within(shell).getByRole('navigation', { name: 'Main navigation' })).toBeTruthy();
    expect(within(shell).getByRole('search', { name: 'Search the Studio' })).toBeTruthy();
  });

  it('every choice in the gallery shows which one is chosen', () => {
    render(<I18nProvider initial="en"><Gallery section="choice" /></I18nProvider>);
    expect(screen.getAllByRole('radio', { checked: true }).length).toBeGreaterThanOrEqual(3);
  });

  it('the screenshot tests photograph every section', () => {
    expect([...GALLERY_IDS]).toEqual(GALLERY_SECTIONS.map(s => s.id));
  });

  it('reads the language, the section and the open overlay from the address', () => {
    expect(galleryParams('?lang=fr&section=cards')).toEqual({ locale: 'fr', section: 'cards', open: null });
    expect(galleryParams('?open=dialog')).toEqual({ locale: 'en', section: null, open: 'dialog' });
  });
});
