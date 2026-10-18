// Vite plugin for the SPAs' index.html (Studio, console): `%BRAND_NAME%` and `%BRAND_FONTS%` become brand.json's name
// and its palette's Google Fonts stylesheet, so the page title, the boot screen and the fonts follow the brand.
import { readFileSync } from 'node:fs';

const theme = JSON.parse(readFileSync(new URL('./generated/theme.json', import.meta.url), 'utf8'));
const escape = s => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

export function brandHtml() {
  return {
    name: 'northline-brand-html',
    // before Vite's own %ENV% replacement, which would warn about these names
    transformIndexHtml: { order: 'pre', handler: html => html.replaceAll('%BRAND_NAME%', escape(theme.brand.name)).replaceAll('%BRAND_FONTS%', escape(theme.font.href)) },
  };
}
