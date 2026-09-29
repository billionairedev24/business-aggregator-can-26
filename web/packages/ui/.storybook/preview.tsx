import type { Preview } from '@storybook/react-vite';
import '@northline/tokens/tokens.css';
import '../src/styles/index.css';

// Theme switcher: overrides the five base tokens only — proves one-change theming.
const themes: Record<string, Record<string, string>> = {
  spruce: {},
  fern: { '--color-accent': '#3F6B45', '--color-accent-2': '#C0663F', '--color-bg': '#F4EFE6' },
  evergreen: { '--color-accent': '#0F3B2E', '--color-highlight': '#7FD65A', '--color-bg': '#F4F5F0' },
};
const preview: Preview = {
  globalTypes: { theme: { description: 'Theme', toolbar: { icon: 'paintbrush', items: Object.keys(themes) } } },
  initialGlobals: { theme: 'spruce' },
  decorators: [(Story, ctx) => {
    const vars = themes[ctx.globals.theme as string] ?? {};
    return <div data-theme style={{ ...vars, padding: 24, background: 'var(--color-bg)', minHeight: '100vh' } as React.CSSProperties}><Story /></div>;
  }],
  parameters: { a11y: { test: 'error' }, layout: 'fullscreen' },
};
export default preview;
