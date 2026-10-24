#!/usr/bin/env node
// Lint (`pnpm lint:tokens`): the component library and every app use the design system's tokens only — tokens v2,
// Plainsight (docs/design/revamp-epic.md, CLAUDE.md § Design system). S-180 made it strict everywhere: every folder of
// @northline/ui, @northline/auth-kit and the consumer, Studio, console and gallery apps (the docs site is Docusaurus,
// with its own theme). It fails on
//   - the retired "Spruce & Honey" token names (--color-accent*, --color-highlight*, --color-neutral-*, --color-bg,
//     --font-body, …): S-180 removed their aliases from dist/tokens.css, so they would resolve to nothing;
//   - raw px font sizes, spacing and corner radii (0–2 px nudges allowed) and raw colours in CSS and inline styles;
//   - an inline `style` that sets a CSS property (a custom property carrying data is fine: `['--pct' as string]: …`);
//   - the per-app rules the E-15 stories added (one tab, status, chip and price style; the console's colour roles).
//
//   node scripts/check-legacy-tokens.mjs        (from web/, or `pnpm lint:tokens`)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ALL = ['packages/ui/src', 'packages/auth-kit/src', 'apps/consumer/src', 'apps/studio/src', 'apps/console/src', 'apps/gallery/src'];
const roots = ALL;
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
const S176 = ['auth', 'assistant', 'writing', 'pilot', 'finance', 'messages', 'storefront', 'reviews', 'settings', 'compliance', 'standing', 'help', 'availability', 'onboarding'].map(f => `apps/studio/src/features/${f}`);
// S-180: everything (it grew folder by folder through S-167–S-177)
const strictRoots = ALL;
const noStyleRoots = ALL;
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

// An inline style may carry data as custom properties (`style={{ ['--pct' as string]: `${n}%` }}`, a merchant's colour,
// a calendar entry's place from a helper), or pass a caller's `style` prop on (the library's `style={style}`); it may not
// set a CSS property (`style={{ marginTop: 16 }}`, `{ width: … }`): that is a look, and belongs in a class.
const styleExpr = (text, from) => {
  let depth = 0, i = from;
  for (; i < text.length; i++) { if (text[i] === '{') depth++; else if (text[i] === '}' && --depth === 0) break; }
  return text.slice(from + 1, i);
};
const SETS_PROPERTY = /(?:^|[{,]\s*)([A-Za-z_$][\w$]*)\s*:(?!:)/;
for (const root of noStyleRoots) {
  for (const file of walk(path.join(webRoot, root))) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (!rel.endsWith('.tsx') || strictSkip.some(r => r.test(rel))) continue;
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(/\sstyle=\{/g)) {
      const expr = styleExpr(text, m.index + m[0].length - 1).replace(/`[^`]*`|'[^']*'|"[^"]*"/g, '""');
      const hit = SETS_PROPERTY.exec(expr);
      if (hit) raw.push(`${rel}:${text.slice(0, m.index).split('\n').length}  inline style sets ${hit[1]} (use a class with the scale tokens; data goes in a custom property)`);
    }
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
  // S-176: the old `Tag` (its accent / highlight tones are the action and commerce colours) and the commerce-coloured alert
  [/<Tag\b/, 'the old Tag (use StatusTag with a status tone)'],
  [/<Alert\b[^>]*\btone=["']highlight["']/, 'a commerce-coloured alert (use tone="info" or "neutral")'],
];
// S-180: the whole Studio (the shell and the dashboard too)
for (const root of ['apps/studio/src']) {
  for (const file of walk(path.join(webRoot, root))) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (!/\.tsx?$/.test(rel) || strictSkip.some(r => r.test(rel))) continue;
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      for (const [re, what] of ONE_OFF) if (re.test(line)) raw.push(`${rel}:${i + 1}  ${what}`);
    });
  }
}
// ── S-177: the console's colour roles and money (audit console-sellers / console-seller) ─────────────────────────────
// A status is a semantic pair (`status-*` / `tag-success`… / StatusTag), never the action or commerce colour: the old
// accent / highlight tones made every status chip blue, magenta or grey. Money goes through the shared formatters
// (`formatPrice` / `fmt.price`, `formatCompactMoney` / `fmt.compactMoney` for a KPI), never `formatMoney` or a whole-dollar
// variant, so one amount reads the same on every screen.
const CONSOLE_ROLE = /\btag-(?:accent(?:-2)?|highlight)\b|\btone=(?:"|\{\s*')(?:accent(?:-2)?|highlight|brand|commerce)\b|\b(?:formatMoney|fmt\.money)\(|variant="(?:ghost|danger|accent|highlight)"/g;
const consoleProblems = [];
for (const file of walk(path.join(webRoot, 'apps/console/src'))) {
  const rel = path.relative(webRoot, file).split(path.sep).join('/');
  if (!rel.endsWith('.tsx') && !rel.endsWith('.ts')) continue;
  if (skip.some(r => r.test(rel))) continue;
  readFileSync(file, 'utf8').split('\n').forEach((line, i) => { for (const m of line.matchAll(CONSOLE_ROLE)) consoleProblems.push(`${rel}:${i + 1}  ${m[0]}`); });
}
if (consoleProblems.length) {
  console.error(`Console colour roles and money (S-177): statuses take a status tone, prices formatPrice / formatCompactMoney, buttons primary | secondary | tertiary | destructive:\n${consoleProblems.map(p => '  ' + p).join('\n')}`);
  process.exit(1);
}

// ── S-173: one price format on the consumer site ($89.00, never "$89" because it is round) ───────────────────────
// `whole` is for amounts that are choices or ranges (a tip box, "Under $10"), never a price made round when it can be.
for (const file of walk(path.join(webRoot, 'apps/consumer/src'))) {
  const rel = path.relative(webRoot, file).split(path.sep).join('/');
  if (skip.some(r => r.test(rel)) || !/\.tsx?$/.test(rel)) continue;
  readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
    if (/whole:\s*[^,}]*%\s*100/.test(line)) raw.push(`${rel}:${i + 1}  a price drawn without cents when round (use formatPrice / Price: $89.00)`);
  });
}

if (problems.length || raw.length) {
  if (problems.length) console.error(`Deprecated token names in rebuilt code (use the semantic roles of @northline/tokens):\n${problems.map(p => '  ' + p).join('\n')}`);
  if (raw.length) console.error(`Raw font sizes, spacing, radii or colours, or a presentational inline style (tokens only, CLAUDE.md § Design system):\n${raw.map(p => '  ' + p).join('\n')}`);
  process.exit(1);
}
console.log(`lint:tokens: no retired token names, raw sizes, spacing, radii or colours, or presentational inline styles in ${ALL.join(', ')}.`);
