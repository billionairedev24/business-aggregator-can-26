// The token build (redesign 2026-10-18, docs/design/redesign-spec.md § 5). Reads brand.json (the brand: identity,
// palette choice, radii), palettes/<palette>.json (its colours and fonts) and scale.json (the fixed rhythm), then:
//   - fails when any text pair of any palette drops below WCAG AA (lib/contrast.mjs) — every palette ships valid;
//   - writes dist/tokens.css (primitive ramps, semantic roles, scale, store theme, the legacy aliases) and
//     dist/palettes.css (each palette under [data-palette], for Storybook's palette switch);
//   - writes generated/theme.json (the resolved theme: what TypeScript and the native apps import) and the favicon,
//     committed so that the native apps and type checks need no build; and copies the favicon into the web apps.
// `node build.mjs --check` (the package's test) writes nothing and fails when a committed file is out of date.
import { copyFileSync, mkdirSync, readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { vet } from './lib/contrast.mjs';
import { markSvg, resolveTheme } from './lib/theme.mjs';

const here = p => new URL(p, import.meta.url);
const json = p => JSON.parse(readFileSync(here(p), 'utf8'));
const check = process.argv.includes('--check');

const brand = json('./brand.json');
const scale = json('./scale.json');
const palettes = Object.fromEntries(readdirSync(here('./palettes/')).filter(f => f.endsWith('.json')).map(f => [f.replace(/\.json$/, ''), json(`./palettes/${f}`)]));
if (!palettes[brand.palette]) throw new Error(`brand.json: no palette "${brand.palette}" (palettes/: ${Object.keys(palettes).join(', ')})`);
const themes = Object.fromEntries(Object.entries(palettes).map(([k, p]) => [k, resolveTheme({ ...brand, palette: k }, p, scale)]));
const theme = themes[brand.palette];

// ── the contrast gate ─────────────────────────────────────────────────────────────────────────────────────────────
const failures = Object.entries(themes).flatMap(([k, t]) => vet(t.color).map(f => ({ palette: k, ...f })));
if (failures.length) {
  console.error('Contrast gate: these colour pairs are below WCAG AA (palettes/*.json; docs/design/redesign-spec.md § 5):');
  for (const f of failures) console.error(`  ${f.palette}: --color-${f.fg} on --color-${f.bg} = ${f.ratio}:1, needs ${f.min}:1 (${f.where})${f.reason ? ` — ${f.reason}` : ` [${f.a} on ${f.b}]`}`);
  process.exit(1);
}

// ── fonts, self-hosted ────────────────────────────────────────────────────────────────────────────────────────────
// The palette's families from their @fontsource packages: the latin and latin-ext faces of each weight (English and
// French), woff2 only, copied into dist/fonts/ and declared at the top of tokens.css. Nothing loads from Google.
const require = createRequire(import.meta.url);
const SUBSETS = ['latin-ext', 'latin'];
function fontFaces(t) {
  const faces = [], files = [];
  for (const f of t.font.families) {
    const dir = dirname(require.resolve(`${f.package}/package.json`));
    for (const w of f.weights) {
      const css = readFileSync(join(dir, `${w}.css`), 'utf8');
      for (const subset of SUBSETS) {
        const file = `${f.package.split('/')[1]}-${subset}-${w}-normal.woff2`;
        const block = css.split('@font-face').find(b => b.includes(`/${file}`));
        const range = /unicode-range:\s*([^;]+);/.exec(block ?? '')?.[1];
        if (!range) throw new Error(`fonts: no ${subset} ${w} face in ${f.package}`);
        files.push([join(dir, 'files', file), file]);
        faces.push(`@font-face { font-family: '${f.family}'; font-style: normal; font-display: swap; font-weight: ${w}; src: url(./fonts/${file}) format('woff2'); unicode-range: ${range}; }`);
      }
    }
  }
  return { css: faces.join('\n'), files };
}
const fonts = fontFaces(theme);

// ── CSS ───────────────────────────────────────────────────────────────────────────────────────────────────────────
const px = v => (typeof v === 'number' && v !== 0 ? `${v}px` : String(v));
const decl = (name, value) => `  ${name}: ${value};`;

/** Layer 1 + 2 + fonts of one theme: what changes with the palette. */
function paletteVars(t) {
  return [
    ...Object.entries(t.ramp).flatMap(([name, steps]) => Object.entries(steps).map(([k, v]) => decl(`--${name}-${k}`, v))),
    ...Object.entries(t.color).map(([k, v]) => decl(`--color-${k}`, v)),
    decl('--color-overlay', 'rgb(0 0 0 / 0.55)'),
    decl('--font-sans', t.font.sans), decl('--font-display', t.font.display), decl('--font-heading', t.font.heading),
    ...Object.entries(t.shadow).map(([k, v]) => decl(`--shadow-${k}`, v.css)),
    decl('--shadow-focus', '0 0 0 3px var(--color-focus-ring)'),
  ];
}

const scaleVars = [
  ...Object.entries(scale.space).map(([k, v]) => decl(`--space-${k}`, px(v))),
  ...Object.entries(scale.text).map(([k, v]) => decl(`--text-${k}`, px(v))),
  ...Object.entries(scale.weight).map(([k, v]) => decl(`--weight-${k}`, v)),
  ...Object.entries(scale.leading).map(([k, v]) => decl(`--leading-${k}`, v)),
  ...Object.entries(scale.tracking).map(([k, v]) => decl(`--tracking-${k}`, v)),
  ...Object.entries(scale.size).map(([k, v]) => decl(`--size-${k}`, px(v))),
  decl('--page-max', px(scale.page.max)), decl('--page-gutter', scale.page.gutter),
  ...Object.entries(brand.radius).map(([k, v]) => decl(`--radius-${k}`, px(v))),
  ...Object.entries(scale.z).map(([k, v]) => decl(`--z-${k}`, v)),
  decl('--duration-fast', scale.motion.fast), decl('--duration', scale.motion.base), decl('--ease', scale.motion.ease),
];

// Layer 3: a merchant's vetted brand colour overrides these inside their own page only (BrandMark, the page builder).
const storeVars = [
  decl('--store-primary', 'var(--color-action-primary)'), decl('--store-primary-hover', 'var(--color-action-primary-hover)'),
  decl('--store-on-primary', 'var(--color-action-primary-text)'), decl('--store-accent', 'var(--color-action-accent)'),
  decl('--store-on-accent', 'var(--color-action-accent-text)'), decl('--store-ink', 'var(--color-link)'),
  decl('--store-tint', 'var(--color-surface-brand)'), decl('--store-surface', 'var(--color-surface)'), decl('--store-text', 'var(--color-text)'),
];

// Deprecated: the "Spruce & Honey" names, pointed at the semantic roles so screens not yet rebuilt (phases 1–5) wear
// the new brand meanwhile. New code uses the semantic names; the lint rule in web/scripts keeps the shells off these.
const LEGACY = {
  // (--color-text and --color-surface are the semantic names themselves: an alias `--x: var(--x)` is a cycle that makes
  // the property invalid everywhere — 2026-10-19, why cards and text lost their colours)
  '--color-bg': 'canvas', '--color-surface-2': 'surface-muted', '--color-divider': 'border',
  '--color-accent': 'action-primary', '--color-on-accent': 'action-primary-text', '--color-accent-100': 'surface-brand', '--color-accent-200': '@primary-100',
  '--color-accent-300': '@primary-300', '--color-accent-400': '@primary-400', '--color-accent-500': '@primary-500', '--color-accent-600': 'action-primary-hover',
  '--color-accent-700': '@primary-700', '--color-accent-800': '@primary-800', '--color-accent-900': '@primary-900',
  '--color-accent-2': 'danger-border', '--color-accent-2-100': 'danger-bg', '--color-accent-2-200': '@danger-100', '--color-accent-2-300': '@danger-300',
  '--color-accent-2-400': '@danger-400', '--color-accent-2-500': '@danger-500', '--color-accent-2-600': 'action-danger-hover', '--color-accent-2-700': 'danger',
  '--color-accent-2-800': '@danger-800', '--color-accent-2-900': '@danger-900',
  '--color-highlight': 'action-accent', '--color-on-highlight': 'action-accent-text', '--color-highlight-100': 'badge-accent-bg', '--color-highlight-300': '@accent-300',
  '--color-process-yellow': 'action-accent',
  '--color-neutral-100': 'surface-muted', '--color-neutral-200': '@neutral-100', '--color-neutral-300': 'border-strong', '--color-neutral-400': '@neutral-300',
  '--color-neutral-500': '@neutral-400', '--color-neutral-600': 'border-control', '--color-neutral-700': 'text-muted', '--color-neutral-800': '@neutral-800',
  '--color-neutral-900': '@neutral-900', '--color-ink': 'text',
  '--font-body': '#var(--font-sans)', '--font-heading-weight': '#700',
};
// a guard: never alias a semantic role to itself
for (const [name, to] of Object.entries(LEGACY)) if (name === `--color-${to}`) throw new Error(`tokens: ${name} aliases itself`);
const legacyVars = Object.entries(LEGACY).map(([name, to]) => decl(name, to.startsWith('#') ? to.slice(1) : to.startsWith('@') ? `var(--${to.slice(1)})` : `var(--color-${to})`));

const header = `/* generated by web/packages/tokens/build.mjs — edit brand.json, palettes/${brand.palette}.json or scale.json */\n`;
const tokensCss = `${header}/* the palette's fonts, self-hosted (dist/fonts/, from @fontsource) */
${fonts.css}
:root {
  /* palette: ${theme.brand.paletteName} (${theme.brand.direction}) — layer 1 primitives, layer 2 semantic roles */
${paletteVars(theme).join('\n')}
  /* scale (fixed rhythm) and brand radii */
${scaleVars.join('\n')}
  /* layer 3: store theme */
${storeVars.join('\n')}
  /* deprecated aliases (pre-redesign names) */
${legacyVars.join('\n')}
}

/* Web-only responsive layer: display sizes scale fluidly, the gutter clears a notch, touch screens get 44px controls
   and 12px minimum text (WCAG 2.5.5). The native apps read generated/theme.json, not this. */
:root {
${Object.entries(scale.fluid).map(([k, v]) => decl(`--text-${k}`, v)).join('\n')}
  --page-gutter: max(${scale.page.gutter}, env(safe-area-inset-left, 0px), env(safe-area-inset-right, 0px));
}
@media (pointer: coarse) {
  :root { --text-2xs: 12px; --size-control: 44px; --size-control-sm: 44px; }
}
`;
const palettesCss = `${header}/* Every palette, scoped: <div data-palette="evergreen"> previews direction B (Storybook toolbar). */\n${Object.entries(themes).map(([k, t]) => `[data-palette='${k}'] {\n${paletteVars(t).join('\n')}\n}`).join('\n')}\n`;

// ── outputs ───────────────────────────────────────────────────────────────────────────────────────────────────────
const themeJson = `${JSON.stringify(theme, null, 2)}\n`;
const favicon = markSvg(theme);
const committed = [
  ['./generated/theme.json', themeJson],
  ['./generated/favicon.svg', favicon],
  // the web apps serve the mark as their favicon (the docs site under static/img)
  ...['consumer', 'studio', 'console'].map(app => [`../../apps/${app}/public/favicon.svg`, favicon]),
  ['../../apps/docs/static/img/favicon.svg', favicon],
];

if (check) {
  const stale = committed.filter(([p, content]) => !existsSync(here(p)) || readFileSync(here(p), 'utf8') !== content).map(([p]) => p);
  if (stale.length) {
    console.error(`Out of date (run \`pnpm --filter @northline/tokens build\` and commit): ${stale.join(', ')}`);
    process.exit(1);
  }
  console.log(`tokens: contrast gate passed for ${Object.keys(themes).join(', ')}; generated files up to date (${brand.palette})`);
} else {
  mkdirSync(here('./dist/'), { recursive: true });
  mkdirSync(here('./generated/'), { recursive: true });
  mkdirSync(here('./dist/fonts/'), { recursive: true });
  for (const [from, file] of fonts.files) copyFileSync(from, here(`./dist/fonts/${file}`));
  writeFileSync(here('./dist/tokens.css'), tokensCss);
  writeFileSync(here('./dist/palettes.css'), palettesCss);
  for (const [p, content] of committed) writeFileSync(here(p), content);
  console.log(`tokens built: ${theme.brand.name}, palette ${theme.brand.paletteName} (${theme.brand.direction}); contrast gate passed for ${Object.keys(themes).join(', ')}`);
}
