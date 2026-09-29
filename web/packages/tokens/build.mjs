// Generates dist/tokens.css + dist/tokens.js from tokens.json.
// The derived layer is copied verbatim from design/theme/northline.css so design and code never drift.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
const t = JSON.parse(readFileSync(new URL('./tokens.json', import.meta.url)));
const derived = readFileSync(new URL('./derived.css', import.meta.url), 'utf8');
const map = { accent: '--color-accent', 'accent-2': '--color-accent-2', highlight: '--color-highlight', bg: '--color-bg', text: '--color-text' };
const lines = [
  ...Object.entries(t.base).map(([k, v]) => `  ${map[k]}: ${v};`),
  ...Object.entries(t.font).map(([k, v]) => `  --font-${k}: ${v};`),
  ...Object.entries(t.radius).map(([k, v]) => `  --radius-${k}: ${v};`),
  ...Object.entries(t.space).map(([k, v]) => `  --space-${k}: ${v};`)
];
mkdirSync(new URL('./dist/', import.meta.url), { recursive: true });
writeFileSync(new URL('./dist/tokens.css', import.meta.url), `/* generated — edit tokens.json */\n:root {\n${lines.join('\n')}\n}\n${derived}`);
writeFileSync(new URL('./dist/tokens.js', import.meta.url), `export const tokens = ${JSON.stringify(t, null, 2)};\nexport const cssVar = (name) => \`var(--\${name})\`;\n`);
console.log('tokens built');
