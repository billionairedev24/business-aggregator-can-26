import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, screen } from 'expo-router/testing-library';

import { setServices } from '../src/services';
import { start } from './support';

/** S-169: the shared kit's gallery opens in a dev build, in both languages. */
afterEach(async () => {
  setServices(null);
  await AsyncStorage.clear();
});

it('shows the kit gallery at /dev/gallery, in English and French', async () => {
  await start({ signedIn: true, url: '/dev/gallery' });
  expect(await screen.findByTestId('kit-gallery')).toBeTruthy();
  expect(screen.getByRole('header', { name: 'Filter chips' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Remove filter: Tonight' })).toBeTruthy();
  fireEvent.press(screen.getByRole('radio', { name: 'Français' }));
  expect(await screen.findByRole('header', { name: 'Filtres' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Retirer le filtre : Ce soir' })).toBeTruthy();
}, 20_000);
