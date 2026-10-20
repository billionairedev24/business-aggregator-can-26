import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { configurePhotoBase } from '@northline/media';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { I18nProvider, type Locale } from './i18n';
import { Photo } from './Photo';
import { BAKERY_PHOTO } from './Photo.fixtures';

const page = (ui: React.ReactNode, locale: Locale = 'en') => render(<I18nProvider initial={locale}>{ui}</I18nProvider>);

describe('Photo', () => {
  it('serves AVIF, WebP and JPEG sets of the crop, lazily, around the focal point, on the placeholder colour', async () => {
    const { container } = page(<Photo photo={BAKERY_PHOTO} crop="square" sizes="200px" ratio />);
    const sources = [...container.querySelectorAll('source')];
    expect(sources.map(s => s.getAttribute('type'))).toEqual(['image/avif', 'image/webp']);
    expect(sources[0]!.getAttribute('srcset')).toMatch(/^\/photos\/category\/shop\.food-and-grocery\.bakery\/square-320\.0123456789\.avif 320w, /);
    expect(sources[0]!.getAttribute('srcset')).not.toMatch(/1440w/); // the square of a 2000 × 1333 photo is 1333 px
    const img = screen.getByRole('img', { name: 'Loaves of sourdough on a wooden counter' });
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('sizes', '200px');
    expect(img.getAttribute('src')).toMatch(/square-640\.0123456789\.jpg$/);
    expect(img.style.objectPosition).toBe('50% 40%');
    const box = container.querySelector<HTMLElement>('.nl-photo')!;
    expect(box.style.getPropertyValue('--nl-photo-bg')).toBe('#b08a5a');
    expect(box.style.getPropertyValue('--nl-photo-ratio')).toBe('1 / 1');
    await expectNoAxeViolations(container);
  });

  it('describes itself in the page language, or not at all when decorative; a hero loads at once', () => {
    page(<Photo photo={BAKERY_PHOTO} priority />, 'fr');
    const img = screen.getByRole('img', { name: /Miches de pain/ });
    expect(img).toHaveAttribute('loading', 'eager');
    expect(img).toHaveAttribute('fetchpriority', 'high');
    const { container } = page(<Photo photo={BAKERY_PHOTO} decorative />);
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
    expect(container.querySelector('.nl-photo')).toHaveAttribute('aria-hidden', 'true');
  });

  it('shows the fallback while a slot has no photo, and serves from a CDN when configured', () => {
    page(<Photo slot="category/no.such.slot" fallback={<span>drawn</span>} />);
    expect(screen.getByText('drawn')).toBeInTheDocument();
    configurePhotoBase('https://cdn.example.org/photos');
    try {
      const { container } = page(<Photo photo={BAKERY_PHOTO} />);
      expect(container.querySelector('img')!.getAttribute('src')).toMatch(/^https:\/\/cdn\.example\.org\/photos\/category\//);
    } finally {
      configurePhotoBase('/photos/');
    }
  });
});
