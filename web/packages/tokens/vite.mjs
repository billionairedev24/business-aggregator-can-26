// Vite plugin for the SPAs' index.html (Studio, console): `%BRAND_NAME%` becomes brand.json's name, so the page title
// and the boot screen follow the brand. The fonts are self-hosted and declared by tokens.css (dist/fonts/).
import { readFileSync } from 'node:fs';

const theme = JSON.parse(readFileSync(new URL('./generated/theme.json', import.meta.url), 'utf8'));
const escape = s => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

export function brandHtml() {
  return {
    name: 'northline-brand-html',
    // before Vite's own %ENV% replacement, which would warn about these names
    transformIndexHtml: { order: 'pre', handler: html => html.replaceAll('%BRAND_NAME%', escape(theme.brand.name)) },
  };
}
