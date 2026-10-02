/* Copied from mobile/packages/mobile-kit/src/theme/oklch.ts (the native apps' colour-mix): the token contrast test (S-109)
 * evaluates derived.css's color-mix(in oklch, …) ramps with the same maths. Keep the two in step. */

export interface Oklch {
  l: number;
  c: number;
  /** Degrees; NaN when powerless (white, black, greys). */
  h: number;
  alpha: number;
}

const ACHROMATIC = 4e-4;

function parseHex(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) throw new Error(`Not a #rrggbb colour: ${hex}`);
  const n = parseInt(m[1]!, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

const toLinear = (v: number) => {
  const c = v / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};
const fromLinear = (c: number) => {
  const v = c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055;
  return Math.round(Math.min(1, Math.max(0, v)) * 255);
};

export function toOklch(hex: string, alpha = 1): Oklch {
  const [r, g, b] = parseHex(hex).map(toLinear) as [number, number, number];
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
  const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
  const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
  const c = Math.hypot(A, B);
  const h = c < ACHROMATIC ? NaN : ((Math.atan2(B, A) * 180) / Math.PI + 360) % 360;
  return { l: L, c, h, alpha };
}

export function fromOklch({ l: L, c, h, alpha }: Oklch): string {
  const hr = ((Number.isNaN(h) ? 0 : h) * Math.PI) / 180;
  const A = Number.isNaN(h) ? 0 : c * Math.cos(hr);
  const B = Number.isNaN(h) ? 0 : c * Math.sin(hr);
  const l = (L + 0.3963377774 * A + 0.2158037573 * B) ** 3;
  const m = (L - 0.1055613458 * A - 0.0638541728 * B) ** 3;
  const s = (L - 0.0894841775 * A - 1.291485548 * B) ** 3;
  const r = fromLinear(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
  const g = fromLinear(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
  const b = fromLinear(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s);
  const hex = `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`.toUpperCase();
  if (alpha >= 1) return hex;
  return `rgba(${r}, ${g}, ${b}, ${Math.round(alpha * 1000) / 1000})`;
}

/** `color-mix(in oklch, a, b <weightB>)`, weightB in 0…1; `b` may be 'transparent'. */
export function mixOklch(a: string, b: string | 'transparent', weightB: number): string {
  const p = 1 - weightB;
  const x = toOklch(a);
  if (b === 'transparent') {
    // premultiplied interpolation with transparent black: the colour stays, its alpha scales (CSS Color 5 § 3)
    return fromOklch({ ...x, alpha: p });
  }
  const y = toOklch(b);
  let h1 = x.h;
  let h2 = y.h;
  if (Number.isNaN(h1)) h1 = h2;
  if (Number.isNaN(h2)) h2 = h1;
  let h = NaN;
  if (!Number.isNaN(h1) && !Number.isNaN(h2)) {
    let d = h2 - h1;
    if (d > 180) d -= 360;
    else if (d < -180) d += 360;
    h = (h1 + d * weightB + 360) % 360;
  }
  return fromOklch({ l: x.l * p + y.l * weightB, c: x.c * p + y.c * weightB, h, alpha: 1 });
}
