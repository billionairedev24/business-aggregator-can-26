/**
 * S-109 / redesign 2026-10-18: WCAG contrast of the colour pairs the components use, on every brand palette
 * (web/packages/tokens/palettes/*.json: Plainsight since S-180), resolved by the token build's own code — the same check that fails
 * `pnpm --filter @northline/tokens build` (lib/contrast.mjs, the port of AfroTransact's vet()). Text 4.5:1 (1.4.3);
 * control outlines, focus indicators and icons 3:1 (1.4.11). One light theme per palette; no dark mode (docs/a11y/audit.md).
 */
import { readFileSync, readdirSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { CHROME_ONLY, FILL_ONLY, PAIRS, REJECTED, vet, vetRoles } from '../../tokens/lib/contrast.mjs';
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
  // S-189 Northern Pine: the guards the mockup's table sets stay in force on the new colours
  it('keeps the rejected pairs failing: gold on white, the spruce action on the forest chrome', () => {
    const colours: Record<string, string> = theme.color;
    for (const [fg, bg, min] of REJECTED) expect(contrast(colours[fg]!, colours[bg]!)).toBeLessThan(min);
    expect(Number(contrast(colours['action-primary']!, colours['header-bg']!).toFixed(2))).toBe(2.38);
    expect(vetRoles(colours)).toEqual([]);
  });
  it('refuses gold (a fill, or the hero accent) as the text of a pair on a light surface', () => {
    for (const fg of [...FILL_ONLY, ...CHROME_ONLY]) {
      expect(vetRoles(theme.color as Record<string, string>, [[fg, 'surface', 4.5, 'test']]).map(f => f.fg)).toContain(fg);
      expect(PAIRS.filter(([f, b]) => f === fg && ['surface', 'canvas', 'well', 'commerce-bg'].includes(b))).toEqual([]);
    }
  });
  it('keeps the action spruce apart from the success green (success is a tinted tag, never a solid button)', () => {
    const hue = (h: string) => {
      const [r, g, b] = [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255) as [number, number, number];
      const max = Math.max(r, g, b), d = max - Math.min(r, g, b);
      const x = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
      return (x * 60 + 360) % 360;
    };
    // spruce #0E6B58 is 168°, success #137A3A 142°: a different hue, and apart in lightness on a tag
    expect(Math.abs(hue(theme.color['action-primary']!) - hue(theme.color.success!))).toBeGreaterThanOrEqual(20);
    expect(theme.color['status-success-bg']).not.toBe(theme.color['action-primary']);
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
