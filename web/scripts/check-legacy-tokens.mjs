#!/usr/bin/env node
// Lint (redesign 2026-10-18): the shared components and the three app shells use the semantic tokens only
// (--color-action-primary, --color-text-muted, …; docs/design/redesign-spec.md § 5). The "Spruce & Honey" names
// (--color-accent*, --color-highlight*, --color-neutral-*, --color-bg, --font-body, …) are deprecated aliases kept in
// dist/tokens.css for the screens later phases rebuild; this keeps them out of what is already rebuilt. Add a
// directory here once its screens are rebuilt.
//
//   node scripts/check-legacy-tokens.mjs        (from web/, or `pnpm lint:tokens`)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const roots = ['packages/ui/src', 'apps/consumer/src/features/shell',
  // redesign phase 1: the consumer demo path (§ 4)
  'apps/consumer/src/features/home', 'apps/consumer/src/features/search', 'apps/consumer/src/features/product',
  'apps/consumer/src/features/cart', 'apps/consumer/src/features/checkout', 'apps/consumer/src/features/orders',
  'apps/consumer/src/features/aftercare', 'apps/consumer/src/features/tracking', 'apps/consumer/src/features/account',
  'apps/consumer/src/features/services', 'apps/consumer/src/features/provider', 'apps/consumer/src/features/booking',
  // the light foundation (2026-10-19): the location dialog
  'apps/consumer/src/features/location',
  // phase B (the consumer pages): the illustrated scenes and the shared page parts
  'apps/consumer/src/features/art', 'apps/consumer/src/features/pages',
  ...['shop', 'quotes', 'food', 'help', 'sell', 'auth', 'promotions'].map(f => `apps/consumer/src/features/${f}`),
  'apps/studio/src/features/shell', 'apps/console/src/features/shell', 'apps/studio/src/studio.css', 'apps/console/src/console.css',
  // every Studio screen (the Studio pages redesign, 2026-10-19)
  'apps/studio/src/features',
  // every console screen (the console pages redesign, 2026-10-19)
  'apps/console/src/features'];
const LEGACY = /var\(\s*--(?:color-(?:accent(?:-2)?(?:-\d{3})?|on-accent|highlight(?:-\d{3})?|on-highlight|process-yellow|bg|surface-2|divider|neutral-\d{3}|ink)|font-body|font-heading-weight)\s*[,)]/g;
const skip = [/\.test\.tsx?$/, /(^|\/)test\//];

function* walk(p) {
  if (statSync(p).isDirectory()) for (const n of readdirSync(p)) yield* walk(path.join(p, n));
  else if (/\.(tsx?|css)$/.test(p)) yield p;
}

const problems = [];
for (const root of roots) {
  for (const file of walk(path.join(webRoot, root))) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (skip.some(r => r.test(rel))) continue;
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      for (const m of line.matchAll(LEGACY)) problems.push(`${rel}:${i + 1}  ${m[0]}`);
    });
  }
}
// S-181: a listing without a photo of its own shows the designed fallback (art/PhotoWell: the dashed well with the
// category icon), never a stock photo through Scene — that repeated one category photo across a page of cards.
const LISTING_FALLBACK = /\b(?:fallback|mark)=\{\s*<Scene\b/g;
const photoProblems = [];
for (const file of walk(path.join(webRoot, 'apps/consumer/src'))) {
  const rel = path.relative(webRoot, file).split(path.sep).join('/');
  if (skip.some(r => r.test(rel)) || !rel.endsWith('.tsx')) continue;
  readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
    for (const m of line.matchAll(LISTING_FALLBACK)) photoProblems.push(`${rel}:${i + 1}  ${m[0]}`);
  });
}
if (photoProblems.length) {
  console.error(`A listing's missing photo must be the designed <PhotoWell> (features/art/PhotoWell.tsx), not a stock photo:\n${photoProblems.map(p => '  ' + p).join('\n')}`);
  process.exit(1);
}
// ── tokens v2 (S-167/S-168): no raw values in the component library and the app shells ──────────────────────────
// Font sizes and spacing (padding, margin, gap) come from the scale (`var(--text-*)`, `var(--type-*)`, `var(--space-*)`),
// colours from the semantic roles: a raw `13px`, `#fff`, `rgb(…)` or `white` there is a look patched past the tokens.
// Corners come from the radius roles (S-170: no raw `999px` pills either). Hairline nudges (0–2px, e.g.
// `margin-bottom: -1px` under a tab row) stay allowed. In TSX the same goes for inline
// `style` objects (`fontSize: 13`, `padding: 6`, `color: 'white'`).
const S175 = ['orders', 'catalogue', 'appointments', 'kitchen'].map(f => `apps/studio/src/features/${f}`);
// S-176: the Studio's money, messaging, storefront, settings, account and onboarding screens — with S-175, every Studio folder
const S176 = ['finance', 'messages', 'storefront', 'reviews'].map(f => `apps/studio/src/features/${f}`);
const strictRoots = ['packages/ui/src', 'apps/consumer/src/features/shell', 'apps/studio/src/features/shell', 'apps/console/src/features/shell',
  // S-170/S-171: the consumer discovery pages (home, results, the Services / Shop / Food landings and their shared parts)
  'apps/consumer/src/features/home', 'apps/consumer/src/features/search', 'apps/consumer/src/features/pages', 'apps/consumer/src/features/shop',
  'apps/consumer/src/features/services/services.css', 'apps/consumer/src/features/services/ServicesLanding.tsx', 'apps/consumer/src/features/services/ServiceCategory.tsx',
  'apps/consumer/src/features/food/FoodScreen.tsx',
  // S-174: the Studio dashboard and the storefront's live preview (audit #7 and #10)
  'apps/studio/src/features/dashboard', 'apps/studio/src/features/storefront/StorefrontPreview.tsx', 'apps/studio/src/features/storefront/StorefrontPreview.css',
  // S-175: the Studio work screens (orders, catalogue and editors, bookings and quotes, the kitchen display and menu)
  ...S175,
  // S-176: the rest of the Studio
  ...S176];
// S-174: no inline `style` at all in these (a class with the scale tokens instead; the Studio dashboard had a dozen).
// S-175: the work screens too — except the calendar, whose entries are placed by custom properties from data (--top, --h).
const noStyleRoots = ['apps/studio/src/features/shell', 'apps/studio/src/features/dashboard', ...S175.filter(r => !r.endsWith('/appointments')), ...S176];
// (the server-rendered error page and the printable report are standalone documents with their own fallbacks)
const strictSkip = [...skip, /\.stories\.tsx$/, /\.fixtures\.ts$/, /\.mdx$/, /(^|\/)server\//, /DataTable\/report\.ts$/];
const PX = /(-?\d*\.?\d+)px/g;
const tooBig = v => Math.abs(parseFloat(v)) > 2;
const CSS_SIZE = /(?:^|[\s;{])(font-size|font|padding(?:-[a-z-]+)?|margin(?:-[a-z-]+)?|gap|row-gap|column-gap|border(?:-[a-z]+)*-radius)\s*:\s*([^;}]+)/g;
const COLOUR_PROPS = /(?:^|[\s;{])((?:background(?:-color)?|color|border(?:-[a-z-]+)?|outline(?:-color)?|box-shadow|fill|stroke|caret-color|text-decoration(?:-color)?|accent-color))\s*:\s*([^;}]+)/g;
const RAW_COLOUR = /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|oklch|lab|lch)\(|\b(?:white|black|red|green|blue|gray|grey|silver|orange|yellow|purple|pink)\b/;
const TSX_SIZE = /\b(fontSize|padding(?:Top|Right|Bottom|Left|Inline|Block)?|margin(?:Top|Right|Bottom|Left|Inline|Block)?|gap|rowGap|columnGap)\s*:\s*(['"`]?)(-?\d*\.?\d+)(px)?\2(?=\s*[,}])/g;
const TSX_COLOUR = /\b(color|background(?:Color)?|borderColor|fill|stroke)\s*:\s*['"`](#[0-9a-fA-F]{3,8}|(?:rgba?|hsla?)\([^'"`]*\)|white|black)['"`]/g;
const raw = [];
for (const root of strictRoots) {
  for (const file of walk(path.join(webRoot, root))) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (strictSkip.some(r => r.test(rel))) continue;
    const css = rel.endsWith('.css');
    // comments never count (a rule's reason may name a value)
    const text = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '));
    text.split('\n').forEach((line, i) => {
      const at = `${rel}:${i + 1}`;
      if (css) {
        for (const m of line.matchAll(CSS_SIZE)) {
          if (m[1] === 'font' && !/\dpx/.test(m[2])) continue;
          const bad = [...m[2].matchAll(PX)].map(x => x[0]).filter(tooBig);
          if (bad.length) raw.push(`${at}  ${m[1]}: ${bad.join(' ')} (use ${m[1].endsWith('radius') ? 'var(--radius-control / panel / image / round): never pills' : 'var(--space-*) / var(--text-*) / var(--type-*)'})`);
        }
        for (const m of line.matchAll(COLOUR_PROPS)) {
          const c = RAW_COLOUR.exec(m[2].replace(/var\([^)]*\)/g, ''));
          if (c) raw.push(`${at}  ${m[1]}: ${c[0]} (use a var(--color-*) role)`);
        }
      } else {
        for (const m of line.matchAll(TSX_SIZE)) if (tooBig(m[3])) raw.push(`${at}  ${m[1]}: ${m[3]}${m[4] ?? ''} inline (use a class with the scale tokens)`);
        for (const m of line.matchAll(TSX_COLOUR)) raw.push(`${at}  ${m[1]}: ${m[2]} inline (use a var(--color-*) role)`);
      }
    });
  }
}

const DATA_PROP = /\sstyle=\{\{\s*\[['"]--[\w-]+['"] as string\]:\s*[\w.]+\s*\}\}/;
for (const root of noStyleRoots) {
  for (const file of walk(path.join(webRoot, root))) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (!rel.endsWith('.tsx') || strictSkip.some(r => r.test(rel))) continue;
    // S-176: a custom property carrying merchant data (the business's colour as --nl-brand / --nl-swatch) is not presentation
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => { if (/\sstyle=\{/.test(line) && !DATA_PROP.test(line)) raw.push(`${rel}:${i + 1}  inline style (use a class with the scale tokens)`); });
  }
}

// ── S-175: one chip, tab, status and price style in the Studio work screens ─────────────────────────────────────
// No hand-made tab strip (role="tab" outside @northline/ui: UnderlineTabs), no old tag tones for a status (tag-accent,
// tag-accent-2 and tag-highlight are the action and commerce colours; a status is a StatusTag or tag-success/warning/
// danger/info/neutral), no app-made chip class (the shared Chip / FilterChip), and prices through formatPrice / Price /
// `price` (formatMoney's options made the "+$3" style; `money(` was the old formatter's name in these screens).
const ONE_OFF = [
  [/role=["']tab["']/, 'a hand-made tab (use UnderlineTabs)'],
  [/\btag-(?:accent(?:-2)?|highlight)\b/, 'an old tag tone (use StatusTag or a status tone)'],
  [/className=["'`][^"'`]*\bnl-chip\b/, 'an app-made chip (use Chip / FilterChip)'],
  [/\bformatMoney\(|\bmoney\(/, 'a one-off price format (use Price, formatPrice or useFormatters().price)'],
];
for (const root of [...S175, ...S176]) {
  for (const file of walk(path.join(webRoot, root))) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (!/\.tsx?$/.test(rel) || strictSkip.some(r => r.test(rel))) continue;
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      for (const [re, what] of ONE_OFF) if (re.test(line)) raw.push(`${rel}:${i + 1}  ${what}`);
    });
  }
}

if (problems.length || raw.length) {
  if (problems.length) console.error(`Deprecated token names in rebuilt code (use the semantic roles of @northline/tokens):\n${problems.map(p => '  ' + p).join('\n')}`);
  if (raw.length) console.error(`Raw font sizes, spacing or colours in the component library or a shell (tokens only, docs/design/revamp-epic.md):\n${raw.map(p => '  ' + p).join('\n')}`);
  process.exit(1);
}
console.log(`No deprecated token names in ${roots.join(', ')}; no raw sizes, spacing or colours in ${strictRoots.join(', ')}.`);
