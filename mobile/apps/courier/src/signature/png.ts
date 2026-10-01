import { zlibSync } from 'fflate';

/**
 * The signature as a PNG the api accepts as proof (JPG/PNG/WebP, type checked from the bytes): the strokes drawn on
 * the pad are rasterised here, in JS, so no native view-capture module is needed. Black ink on white, 8-bit grey.
 */
export interface Point {
  x: number;
  y: number;
}
export type Stroke = Point[];

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff;
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff]! ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length);
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
  out.set(data, 8);
  view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
}

/** Draws the strokes (in pad coordinates, padW × padH) into a width × height grey bitmap. */
export function rasterise(strokes: readonly Stroke[], padW: number, padH: number, width = 600, height = 240, ink = 3): Uint8Array {
  const pixels = new Uint8Array(width * height).fill(255);
  const sx = width / Math.max(1, padW);
  const sy = height / Math.max(1, padH);
  const r = ink;
  const dot = (cx: number, cy: number) => {
    for (let y = Math.max(0, Math.floor(cy - r)); y <= Math.min(height - 1, Math.ceil(cy + r)); y++) {
      for (let x = Math.max(0, Math.floor(cx - r)); x <= Math.min(width - 1, Math.ceil(cx + r)); x++) {
        if ((x - cx) ** 2 + (y - cy) ** 2 <= r * r) pixels[y * width + x] = 0;
      }
    }
  };
  for (const stroke of strokes) {
    for (let i = 0; i < stroke.length; i++) {
      const a = stroke[i]!;
      const b = stroke[i + 1] ?? a;
      const ax = a.x * sx;
      const ay = a.y * sy;
      const bx = b.x * sx;
      const by = b.y * sy;
      const steps = Math.max(1, Math.ceil(Math.hypot(bx - ax, by - ay) / (r / 2)));
      for (let s = 0; s <= steps; s++) dot(ax + ((bx - ax) * s) / steps, ay + ((by - ay) * s) / steps);
    }
  }
  return pixels;
}

export function encodePng(pixels: Uint8Array, width: number, height: number): Uint8Array {
  const ihdr = new Uint8Array(13);
  const v = new DataView(ihdr.buffer);
  v.setUint32(0, width);
  v.setUint32(4, height);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 0; // greyscale
  const raw = new Uint8Array((width + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0; // filter: none
    raw.set(pixels.subarray(y * width, (y + 1) * width), y * (width + 1) + 1);
  }
  const parts = [
    Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlibSync(raw, { level: 9 })),
    chunk('IEND', new Uint8Array(0)),
  ];
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

/** The signature's PNG; null when nothing was drawn (a tap is not a signature). */
export function signaturePng(strokes: readonly Stroke[], padW: number, padH: number): Uint8Array | null {
  const length = strokes.reduce((n, s) => n + s.slice(1).reduce((m, p, i) => m + Math.hypot(p.x - s[i]!.x, p.y - s[i]!.y), 0), 0);
  if (length < 20) return null;
  const width = 600;
  const height = 240;
  return encodePng(rasterise(strokes, padW, padH, width, height), width, height);
}
