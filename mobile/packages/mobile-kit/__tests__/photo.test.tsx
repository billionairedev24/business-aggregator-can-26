import { render, screen } from '@testing-library/react-native';
import { Image } from 'react-native';

import { configurePhotoBase, photoFor, slugSlot, type ResolvedPhoto } from '../src/photos/library';
import { Photo, Scene } from '../src/ui/Photo';

const bread: ResolvedPhoto = {
  slot: 'category/shop.food-and-grocery.bakery',
  alt: { en: 'Loaves on a counter', fr: 'Miches sur un comptoir' },
  placeholder: '#806040',
  sources: [
    { uri: 'https://cdn.example.org/photos/x/card-320.0123456789.jpg', width: 320, height: 240 },
    { uri: 'https://cdn.example.org/photos/x/card-640.0123456789.jpg', width: 640, height: 480 },
  ],
};

/** Real photos (2026-10-19): the library's stock photos, the drawing only while a slot has none. */
describe('Photo', () => {
  it('gives Image every width of the crop, on the placeholder colour, decorative unless labelled', () => {
    render(<Photo slot="ignored" photo={bread} testID="pic" />);
    const box = screen.getByTestId('pic', { includeHiddenElements: true });
    expect(box.props.accessibilityElementsHidden).toBe(true);
    expect(screen.UNSAFE_getByType(Image).props.source).toEqual(bread.sources);
    render(<Photo slot="ignored" photo={bread} label="Loaves" testID="labelled" />);
    expect(screen.getByLabelText('Loaves')).toBeTruthy();
  });

  it('draws the scene while no slot has a photo', () => {
    render(<Scene slot={['category/no.such.slot', null]} subject="bakery" testID="scene" />);
    expect(screen.getByTestId('scene', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.UNSAFE_queryByType(Image)).toBeNull();
    expect(photoFor('category/no.such.slot')).toBeNull();
  });

  it('knows the catalogue by slug and serves from the configured base', () => {
    expect(slugSlot('service', 'plumber')).toBe('category/service.home-trades.plumber');
    expect(slugSlot('shop', 'bakery')).toBe('category/shop.food-and-grocery.bakery');
    expect(slugSlot('shop', 'nope')).toBeNull();
    configurePhotoBase('https://cdn.example.org/photos');
    const any = photoFor(['hero/home-main', 'category/shop.food-and-grocery.bakery', 'banner/shop']);
    if (any) expect(any.sources[0]!.uri.startsWith('https://cdn.example.org/photos/')).toBe(true);
  });
});
