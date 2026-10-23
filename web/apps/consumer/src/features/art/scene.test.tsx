import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { photoOf } from '@northline/media';
import { I18nProvider } from '@northline/ui';
import { Scene } from './Scene';

const bakery = photoOf('category/shop.food-and-grocery.bakery', {
  file: 'source/category/shop.food-and-grocery.bakery.jpg', alt: { en: 'Bread', fr: 'Pain' }, focal: { x: 0.5, y: 0.5 },
  site: 'pexels', photographer: 'P', sourceUrl: 'https://www.pexels.com/photo/1/', licence: 'Pexels License', downloaded: '2026-10-20',
  width: 1600, height: 1200, hash: 'abcdef0123', placeholder: '#806040',
});

describe('Scene: a library photo, the designed well only while no slot has one (S-181)', () => {
  it('shows the photo (decorative beside its heading) in the crop asked for, a banner wide', () => {
    const { container } = render(<I18nProvider initial="en"><Scene slot="x/y" photo={bakery} subject="bakery" crop="square" /></I18nProvider>);
    expect(container.querySelector('.nl-photo img')).toHaveAttribute('alt', '');
    expect(container.querySelector('.nl-photo img')!.getAttribute('src')).toMatch(/square-640\.abcdef0123\.jpg$/);
    expect(container.querySelector('.nl-well')).toBeNull();
    const banner = render(<I18nProvider initial="en"><Scene slot="x/y" photo={bakery} subject="bakery" fit="contain" /></I18nProvider>);
    expect(banner.container.querySelector('.nl-photo img')!.getAttribute('src')).toMatch(/\/wide-640\./);
  });

  it('never stretches a photo past its widest variant: the box is capped at that width', () => {
    const { container } = render(<I18nProvider initial="en"><Scene slot="x/y" photo={bakery} subject="bakery" fit="contain" /></I18nProvider>);
    // 1600 × 1200 → its 16:9 crop is 1600 wide; the widest variant built is 1440
    expect(container.querySelector<HTMLElement>('.nl-scene')!.style.getPropertyValue('--nl-scene-max')).toBe('1440px');
  });

  it('shows the dashed well with the subject’s icon while no slot has a photo — no drawing, no text', () => {
    const { container } = render(<I18nProvider initial="en"><Scene slot={['category/no.such.slot', null]} subject="plumber" fit="contain" /></I18nProvider>);
    expect(container.querySelector('.nl-photo')).toBeNull();
    const well = container.querySelector('.nl-well')!;
    expect(well).toHaveAttribute('aria-hidden');
    expect(well.querySelector('svg.nl-well-icon')).not.toBeNull();
    expect(well.textContent).toBe('');
  });
});
