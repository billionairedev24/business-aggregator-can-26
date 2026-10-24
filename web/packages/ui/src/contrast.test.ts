/**
 * S-109 / redesign 2026-10-18: WCAG contrast of the colour pairs the components use, on every brand palette
 * (web/packages/tokens/palettes/*.json: Plainsight since S-180), resolved by the token build's own code — the same check that fails
 * `pnpm --filter @northline/tokens build` (lib/contrast.mjs, the port of AfroTransact's vet()). Text 4.5:1 (1.4.3);
 * control outlines, focus indicators and icons 3:1 (1.4.11). One light theme per palette; no dark mode (docs/a11y/audit.md).
 */
import { readFileSync, readdirSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { PAIRS, vet } from '../../tokens/lib/contrast.mjs';
import { resolveTheme } from '../../tokens/lib/theme.mjs';
import { contrast } from '../../tokens/lib/oklch.mjs';
import { theme } from '@northline/tokens';

const dir = new URL('../../tokens/', import.meta.url);
const json = (p: string) => JSON.parse(readFileSync(new URL(p, dir), 'utf8'));
const brand = json('brand.json'), scale = json('scale.json');
const palettes = readdirSync(new URL('palettes/', dir)).filter(f => f.endsWith('.json')).map(f => f.replace(/\.json$/, ''));

describe.each(palettes)('token contrast (%s)', name => {
  const t = resolveTheme({ ...brand, palette: name }, json(`palettes/${name}.json`), scale);
  it.each(PAIRS.map(([fg, bg, min, where]) => ({ fg, bg, min, where })))('$fg on $bg ≥ $min:1 ($where)', ({ fg, bg, min }) => {
    expect(Number(contrast(t.color[fg]!, t.color[bg]!).toFixed(2))).toBeGreaterThanOrEqual(min);
  });
});

describe('the contrast gate', () => {
  it('agrees with known WCAG ratios', () => {
    expect(contrast('#000000', '#FFFFFF')).toBeCloseTo(21, 1);
    expect(contrast('#777777', '#FFFFFF')).toBeCloseTo(4.48, 2);
  });
  it('fails an unreadable brand colour instead of adjusting it', () => {
    const p = json(`palettes/${brand.palette}.json`);
    const t = resolveTheme(brand, { ...p, color: { ...p.color, primaryText: '#2A4F86' } }, scale);
    expect(vet(t.color).map(f => `${f.fg}/${f.bg}`)).toContain('action-primary-text/action-primary');
  });
  it('the generated theme is brand.json’s palette, its brand colours as given', () => {
    const p = json(`palettes/${brand.palette}.json`);
    expect(theme.brand.palette).toBe(brand.palette);
    expect(theme.brand.name).toBe(brand.identity.name);
    expect(theme.color['action-primary']).toBe(p.color.primary);
    // light chrome (brand.json, 2026-10-19): white pages, the palette's ground for sunken areas; dark: the ground
    expect(theme.color.canvas).toBe((brand as { chrome?: string }).chrome === 'dark' ? p.color.ground : p.color.surface);
    expect(theme.color['surface-sunken']).toBe(p.color.ground);
    expect(theme.color.text).toBe(p.color.ink);
  });
});
