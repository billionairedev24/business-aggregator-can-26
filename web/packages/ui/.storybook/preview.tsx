import type { Preview } from '@storybook/react-vite';
import '@northline/tokens/tokens.css';
import '../src/styles/index.css';
import './preview.css';
import { configurePlatformTimeZone } from '../src/i18n';

// Story data is a Mountain-time launch market (S-134: the apps take the zone from the region model).
configurePlatformTimeZone('America/Edmonton');

// One palette (S-180: the pre-E-15 palettes and the toolbar's palette switch are gone): the stories wear brand.json's.
const preview: Preview = {
  decorators: [Story => <div className="nl-story-frame"><Story /></div>],
  parameters: { a11y: { test: 'error' }, layout: 'fullscreen' },
};
export default preview;
