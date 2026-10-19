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

describe('Scene: a library photo, the drawing only while the slot has none', () => {
  it('shows the photo (decorative beside its heading) in the crop asked for, a banner wide', () => {
    const { container } = render(<I18nProvider initial="en"><Scene slot="x/y" photo={bakery} subject="bakery" crop="square" /></I18nProvider>);
    expect(container.querySelector('.nl-photo img')).toHaveAttribute('alt', '');
    expect(container.querySelector('.nl-photo img')!.getAttribute('src')).toMatch(/square-640\.abcdef0123\.jpg$/);
    expect(container.querySelector('.nl-art')).toBeNull();
    const banner = render(<I18nProvider initial="en"><Scene slot="x/y" photo={bakery} subject="bakery" fit="contain" /></I18nProvider>);
    expect(banner.container.querySelector('.nl-photo img')!.getAttribute('src')).toMatch(/\/wide-640\./);
  });

  it('draws the subject while no slot has a photo', () => {
    const { container } = render(<I18nProvider initial="en"><Scene slot={['category/no.such.slot', null]} subject="plumber" fit="contain" /></I18nProvider>);
    expect(container.querySelector('.nl-photo')).toBeNull();
    expect(container.querySelector('.nl-art.nl-art-contain')).not.toBeNull();
  });
});
