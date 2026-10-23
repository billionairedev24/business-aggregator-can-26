import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { I18nProvider } from '@northline/ui';
import { CreditsScreen } from './CreditsScreen';

const page = (ui: React.ReactNode, locale: 'en' | 'fr' = 'en') => render(<I18nProvider initial={locale}>{ui}</I18nProvider>);

describe('photo credits (/credits)', () => {
  it('names each photo, its photographer, site and licence, linked', () => {
    page(<CreditsScreen list={[
      { slot: 'hero/home-main', photographer: 'Jane Doe', photographerUrl: 'https://www.pexels.com/@jane', site: 'pexels', sourceUrl: 'https://www.pexels.com/photo/1/', licence: 'Pexels License', attribution: false },
      { slot: 'banner/shop', title: 'Groceries', photographer: 'Sam Roe', site: 'openverse', source: 'flickr', sourceUrl: 'https://www.flickr.com/photos/x/1', licence: 'CC BY 2.0', licenceUrl: 'https://creativecommons.org/licenses/by/2.0/', attribution: true },
    ]} />);
    expect(screen.getByRole('heading', { name: 'Photo credits' })).toBeTruthy();
    expect(screen.getByText('2 photographs')).toBeTruthy();
    // S-181: the same attribution as data, served with the photos
    expect(screen.getByRole('link', { name: 'credits.json' }).getAttribute('href')).toBe('/photos/credits.json');
    expect(screen.getByRole('link', { name: 'by Jane Doe' })).toHaveAttribute('href', 'https://www.pexels.com/@jane');
    expect(screen.getByRole('link', { name: 'Licence: Pexels License' })).toHaveAttribute('href', 'https://www.pexels.com/license/');
    expect(screen.getByRole('link', { name: 'Licence: CC BY 2.0' })).toHaveAttribute('href', 'https://creativecommons.org/licenses/by/2.0/');
    expect(screen.getByText(/on Openverse · flickr/)).toBeTruthy();
    expect(screen.getAllByRole('link', { name: /Photo page of/ })).toHaveLength(2);
  });

  it('says so while the library has no photo, in French too', () => {
    page(<CreditsScreen list={[]} />, 'fr');
    expect(screen.getByText(/Aucune photo d’archives n’est encore utilisée/)).toBeTruthy();
  });
});
