import type { Preview } from '@storybook/react-vite';
import '@northline/tokens/tokens.css';
import '@northline/tokens/palettes.css';
import '../src/styles/index.css';
import { configurePlatformTimeZone } from '../src/i18n';
import { theme } from '@northline/tokens';

// Story data is a Mountain-time launch market (S-134: the apps take the zone from the region model).
configurePlatformTimeZone('America/Edmonton');

// Palette switch (redesign 2026-10-18): every palette of web/packages/tokens/palettes, scoped by [data-palette]
// (dist/palettes.css). The apps wear brand.json's palette; this only previews the others. Fonts of the other
// palettes are not loaded here (their stacks fall back to the system sans).
const palettes = ['plainsight', 'lakeshore', 'evergreen', 'dusk'];
const preview: Preview = {
  globalTypes: { palette: { description: 'Brand palette', toolbar: { icon: 'paintbrush', items: palettes } } },
  initialGlobals: { palette: theme.brand.palette },
  decorators: [(Story, ctx) => (
    <div data-palette={ctx.globals.palette as string} style={{ padding: 24, background: 'var(--color-canvas)', color: 'var(--color-text)', fontFamily: 'var(--font-sans)', minHeight: '100vh' }}><Story /></div>
  )],
  parameters: { a11y: { test: 'error' }, layout: 'fullscreen' },
};
export default preview;
