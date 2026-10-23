import { fireEvent, renderRouter, screen } from 'expo-router/testing-library';

import { KitGallery } from '@northline/mobile-kit';

/** S-169: the courier app mounts the same kit gallery as the consumer app (dev builds only). */
it('renders the shared kit gallery with the courier app', async () => {
  renderRouter({ index: () => <KitGallery initialLocale="fr-CA" /> }, { initialUrl: '/' });
  expect(await screen.findByRole('header', { name: 'Boutons' })).toBeTruthy();
  fireEvent.press(screen.getByRole('radio', { name: 'English' }));
  expect(await screen.findByRole('header', { name: 'Buttons' })).toBeTruthy();
});
