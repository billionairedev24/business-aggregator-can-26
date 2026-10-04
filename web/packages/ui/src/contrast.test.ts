/**
 * S-109: WCAG contrast of the colour pairs the components use, computed from the tokens themselves —
 * packages/tokens/tokens.json (the five base colours) and derived.css (the color-mix(in oklch) ramps) — so a change to
 * either is checked. Text needs 4.5:1 (1.4.3), large text, icons, borders of controls and focus rings 3:1 (1.4.11).
 *
 * Northline has one (light) theme; there is no dark mode to check (see docs/a11y/audit.md). A future theme only has to
 * be added to THEMES.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { mixOklch } from './test/oklch';

const tokensDir = new URL('../../tokens/', import.meta.url);
const base = (JSON.parse(readFileSync(new URL('tokens.json', tokensDir), 'utf8')) as { base: Record<string, string> }).base;
const derived = readFileSync(new URL('derived.css', tokensDir), 'utf8');

const NAMED: Record<string, string> = { white: '#FFFFFF', black: '#000000' };
const BASE_VARS: Record<string, string> = { accent: '--color-accent', 'accent-2': '--color-accent-2', highlight: '--color-highlight', bg: '--color-bg', text: '--color-text' };

/** Every `--color-*` of derived.css's :root block, as #rrggbb (or rgba() for the translucent ones). */
function resolveTheme(baseColours: Record<string, string>): Record<string, string> {
  const defs = new Map<string, string>();
  const root = /:root, \[data-theme\] \{([\s\S]*?)\n\}/.exec(derived)?.[1] ?? '';
  for (const m of root.matchAll(/(--color-[\w-]+):\s*([^;]+);/g)) defs.set(m[1]!, m[2]!.trim());
  for (const [k, v] of Object.entries(baseColours)) defs.set(BASE_VARS[k]!, v);
  const out: Record<string, string> = {};
  const colour = (expr: string): string => {
    const e = expr.trim();
    if (NAMED[e]) return NAMED[e]!;
    if (e === 'transparent') return e;
    if (/^#[0-9a-f]{6}$/i.test(e)) return e.toUpperCase();
    const v = /^var\((--[\w-]+)(?:,[^)]*)?\)$/.exec(e);
    if (v) return get(v[1]!);
    const mix = /^color-mix\(in oklch,\s*(.+)\)$/.exec(e);
    if (!mix) throw new Error(`Unsupported colour expression: ${e}`);
    const [a, b] = splitArgs(mix[1]!).map(arg => { const p = /^(.*?)\s+(\d+(?:\.\d+)?)%$/.exec(arg); return p ? { c: p[1]!, w: Number(p[2]) / 100 } : { c: arg, w: undefined }; });
    const wB = b!.w ?? (a!.w !== undefined ? 1 - a!.w : 0.5);
    return mixOklch(colour(a!.c), colour(b!.c) as string, wB);
  };
  const get = (name: string): string => {
    if (out[name]) return out[name]!;
    const def = defs.get(name);
    if (!def) throw new Error(`Unknown token ${name}`);
    return (out[name] = colour(def));
  };
  for (const name of defs.keys()) get(name);
  return out;
}

function splitArgs(s: string): string[] {
  const parts: string[] = [];
  let depth = 0, cur = '';
  for (const ch of s) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { parts.push(cur.trim()); cur = ''; } else cur += ch;
  }
  parts.push(cur.trim());
  return parts;
}

const lum = (hex: string) => {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map(v => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
};
const contrast = (a: string, b: string) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x! + 0.05) / (y! + 0.05); };

type Pair = [fg: string, bg: string, min: number, where: string];
/** Text 4.5:1 unless noted; non-text 3:1. Names are tokens without `--color-`. */
const PAIRS: Pair[] = [
  ['text', 'bg', 4.5, 'body text'], ['text', 'surface', 4.5, 'text on panels, inputs, menus'],
  ['neutral-700', 'bg', 4.5, 'muted text, hints, kickers'], ['neutral-700', 'surface', 4.5, 'muted text on panels'], ['neutral-700', 'neutral-100', 4.5, 'table headers'],
  ['neutral-800', 'bg', 4.5, 'field labels'], ['neutral-800', 'surface-2', 4.5, 'segmented control'],
  ['neutral-700', 'surface', 4.5, 'input placeholders'], ['neutral-700', 'bg', 4.5, 'input placeholders in the Studio (surface = bg)'],
  ['accent', 'bg', 4.5, 'links, ghost buttons'], ['accent', 'surface', 4.5, 'links on panels'], ['accent-700', 'accent-100', 4.5, 'ghost button hover'],
  ['accent-2-700', 'bg', 4.5, 'inline errors'], ['accent-2-700', 'surface', 4.5, 'inline errors in panels and dialogs'],
  ['on-accent', 'accent', 4.5, 'primary buttons, selected chips'], ['on-accent', 'accent-600', 4.5, 'primary hover'], ['on-accent', 'accent-2', 4.5, 'danger buttons'],
  ['on-highlight', 'highlight', 4.5, 'highlight buttons, cart count'], ['on-highlight', 'highlight-100', 4.5, 'highlight tags and alerts'],
  ['accent-800', 'accent-100', 4.5, 'accent tags, current nav item'], ['accent-2-800', 'accent-2-100', 4.5, 'rosehip tags'], ['neutral-900', 'neutral-200', 4.5, 'neutral tags, nav badges'],
  ['accent-700', 'bg', 4.5, 'outline tags'], ['accent-900', 'accent-100', 4.5, 'info alerts, account card'], ['accent-2-900', 'accent-2-100', 4.5, 'error alerts, error states'],
  ['bg', 'text', 4.5, 'toasts'], ['bg', 'accent', 4.5, 'selected chip text'],
  // non-text (1.4.11)
  ['accent', 'bg', 3, 'focus ring'], ['accent', 'surface', 3, 'focus ring on panels'], ['on-accent', 'accent', 3, 'focus ring on the selection bar'],
  ['accent-2', 'bg', 3, 'error border of inputs, stars'],
  ['neutral-600', 'surface', 3, 'input borders'], ['neutral-600', 'bg', 3, 'input borders in the Studio (surface = bg)'],
  ['neutral-600', 'surface', 3, 'checkbox and radio borders'], ['surface', 'neutral-600', 3, 'switch knob on the off track'], ['surface', 'accent', 3, 'switch knob on the on track'],
  ['neutral-500', 'surface', 3, 'Data Table checkbox ring'],
  ['neutral-600', 'surface', 3, 'step bars: steps still to do, in the auth cards (S-144)'], ['neutral-600', 'bg', 3, 'step bars: steps still to do, on the page (S-144)'], ['highlight', 'accent', 3, 'hero search focus ring on the spruce hero'],
];

const THEMES: Record<string, Record<string, string>> = { light: base };

describe.each(Object.keys(THEMES))('token contrast (%s theme)', name => {
  const theme = resolveTheme(THEMES[name]!);
  const c = (token: string) => { const v = theme[`--color-${token}`]; if (!v || !v.startsWith('#')) throw new Error(`--color-${token} is not opaque: ${v}`); return v; };
  it.each(PAIRS.map(([fg, bg, min, where]) => ({ fg, bg, min, where })))('$fg on $bg ≥ $min:1 ($where)', ({ fg, bg, min }) => {
    expect(Number(contrast(c(fg), c(bg)).toFixed(2))).toBeGreaterThanOrEqual(min);
  });
});

describe('the contrast maths', () => {
  it('agrees with known WCAG ratios', () => {
    expect(contrast('#000000', '#FFFFFF')).toBeCloseTo(21, 1);
    expect(contrast('#777777', '#FFFFFF')).toBeCloseTo(4.48, 2);
  });
  it('resolves the derived ramps (the on-highlight brown of design/theme/northline.css)', () => {
    const theme = resolveTheme(base);
    expect(theme['--color-on-accent']).toBe('#FFFFFF');
    expect(theme['--color-process-yellow']).toBe(base.highlight!.toUpperCase());
    expect(theme['--color-divider']).toMatch(/^rgba\(/);
  });
});
