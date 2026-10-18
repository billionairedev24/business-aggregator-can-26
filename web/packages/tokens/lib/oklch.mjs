// `color-mix(in oklch, …)` computed ahead of time (CSS Color 4/5: OKLab via linear sRGB, shorter-hue interpolation, a
// powerless hue for achromatic colours, out-of-gamut results clipped) and the WCAG 2 contrast ratio. The token build
// resolves every derived colour with it, so the CSS, the native apps and the contrast gate all see the same values.

const ACHROMATIC = 4e-4;

function parseHex(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex).trim());
  if (!m) throw new Error(`Not a #rrggbb colour: ${hex}`);
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

const toLinear = v => { const c = v / 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const fromLinear = c => { const v = c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055; return Math.round(Math.min(1, Math.max(0, v)) * 255); };

export function toOklch(hex) {
  const [r, g, b] = parseHex(hex).map(toLinear);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
  const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
  const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
  const c = Math.hypot(A, B);
  return { l: L, c, h: c < ACHROMATIC ? NaN : ((Math.atan2(B, A) * 180) / Math.PI + 360) % 360 };
}

export function fromOklch({ l: L, c, h }) {
  const hr = ((Number.isNaN(h) ? 0 : h) * Math.PI) / 180;
  const A = Number.isNaN(h) ? 0 : c * Math.cos(hr);
  const B = Number.isNaN(h) ? 0 : c * Math.sin(hr);
  const l = (L + 0.3963377774 * A + 0.2158037573 * B) ** 3;
  const m = (L - 0.1055613458 * A - 0.0638541728 * B) ** 3;
  const s = (L - 0.0894841775 * A - 1.291485548 * B) ** 3;
  const rgb = [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ].map(fromLinear);
  return `#${rgb.map(v => v.toString(16).padStart(2, '0')).join('')}`.toUpperCase();
}

/** `color-mix(in oklch, a, b <weightB>)` with weightB in 0…1. */
export function mix(a, b, weightB) {
  const p = 1 - weightB, x = toOklch(a), y = toOklch(b);
  let h1 = x.h, h2 = y.h;
  if (Number.isNaN(h1)) h1 = h2;
  if (Number.isNaN(h2)) h2 = h1;
  let h = NaN;
  if (!Number.isNaN(h1) && !Number.isNaN(h2)) {
    let d = h2 - h1;
    if (d > 180) d -= 360; else if (d < -180) d += 360;
    h = (h1 + d * weightB + 360) % 360;
  }
  return fromOklch({ l: x.l * p + y.l * weightB, c: x.c * p + y.c * weightB, h });
}

const luminance = hex => { const [r, g, b] = parseHex(hex).map(toLinear); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };

/** WCAG 2 contrast ratio of two opaque colours. */
export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

/** "#17407A" → "23 64 122" (for `rgb(<channels> / <alpha>)`). */
export const channels = hex => parseHex(hex).join(' ');

/**
 * The first of `fg` mixed 0, 2, 4 … 100 % towards `toward` (black or white) that reaches `min`:1 against `bg` — how a
 * derived text colour (a status text on its tint, the rating number) is made readable on any brand. Brand-supplied
 * colours are never adjusted: the contrast gate fails the build instead.
 */
export function readable(fg, bg, min, toward = '#000000') {
  for (let w = 0; w <= 1.0001; w += 0.02) {
    const c = mix(fg, toward, Math.min(1, w));
    if (contrast(c, bg) >= min) return c;
  }
  return toward;
}
