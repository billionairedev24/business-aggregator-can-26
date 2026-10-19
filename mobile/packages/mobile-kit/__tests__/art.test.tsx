import { render, screen } from '@testing-library/react-native';

import { ART_PATHS } from '../src/ui/art.generated';
import { Art, TONES, subjectFor, toneFor } from '../src/ui/Art';

/** The illustrated scenes (light direction): the consumer web's rules, drawn with react-native-svg. */
describe('Art', () => {
  it('picks the subject from the words of a slug or name, as the web does', () => {
    expect(subjectFor('mobile-mechanic')).toEqual({ main: 'Car', side: 'Wrench' });
    expect(subjectFor('cocktail-and-mocktail-bar')).toEqual({ main: 'Martini', side: 'Champagne' });
    expect(subjectFor('barber-and-hair').main).toBe('Scissors'); // "bar" only as a whole word
    expect(subjectFor('Country sourdough').main).toBe('Bread');
    expect(subjectFor('Ribeye, AAA').main).toBe('Cow');
    expect(subjectFor('something unknown')).toEqual({ main: 'Storefront', side: 'Sparkle' });
  });

  it('has the path data of every pictogram a rule names', () => {
    for (const key of ['plumber', 'house-cleaning', 'tutor-k-12', 'real-estate-agent', 'produce', 'courier', 'services']) {
      const s = subjectFor(key);
      expect(ART_PATHS[s.main].length).toBeGreaterThan(0);
      if (s.side) expect(ART_PATHS[s.side].length).toBeGreaterThan(0);
    }
  });

  it('gives a key the same tone every time', () => {
    expect(toneFor('bakery')).toBe(toneFor('bakery'));
    expect(TONES).toContain(toneFor('bakery'));
  });

  it('is decorative: hidden from screen readers (the text beside it names the thing)', () => {
    render(<Art subject="bakery" testID="pic" />);
    expect(screen.queryByTestId('pic')).toBeNull();
    const pic = screen.getByTestId('pic', { includeHiddenElements: true });
    expect(pic.props.accessibilityElementsHidden).toBe(true);
    expect(pic.props.importantForAccessibility).toBe('no-hide-descendants');
  });

  it('draws the contain variant too (wide banners)', () => {
    render(<Art subject="mobile-mechanic" fit="contain" testID="banner" />);
    expect(screen.getByTestId('banner', { includeHiddenElements: true })).toBeTruthy();
  });
});
