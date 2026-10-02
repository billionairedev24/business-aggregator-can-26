// Draws the consumer app's icon, Android adaptive foreground and splash mark from the Northline base tokens
// (web/packages/tokens/tokens.json): a honey disc with a spruce location pin ("one tap from the door") on spruce.
// A placeholder until design supplies the store icon (DECISIONS S-97), drawn like the courier app's.
// node scripts/gen-icons.mjs — writes assets/{icon,adaptive-icon,splash-icon}.png. No image library needed.
import { readFileSync, writeFileSync } from 'node:fs';
import { zlibSync } from 'fflate';

const tokens = JSON.parse(readFileSync(new URL('../../../../web/packages/tokens/tokens.json', import.meta.url), 'utf8'));
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const SPRUCE = hex(tokens.base.accent);
const HONEY = hex(tokens.base.highlight);
const PAPER = hex(tokens.base.bg);

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc32 = (b) => {
  let c = 0xffffffff;
  for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
function chunk(type, data) {
  const out = new Uint8Array(12 + data.length);
  const v = new DataView(out.buffer);
  v.setUint32(0, data.length);
  out.set([...type].map((c) => c.charCodeAt(0)), 4);
  out.set(data, 8);
  v.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
}
function png(size, pixel) {
  const raw = new Uint8Array((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) raw.set(pixel(x + 0.5, y + 0.5), y * (size * 4 + 1) + 1 + x * 4);
  }
  const ihdr = new Uint8Array(13);
  const v = new DataView(ihdr.buffer);
  v.setUint32(0, size);
  v.setUint32(4, size);
  ihdr.set([8, 6, 0, 0, 0], 8);
  const parts = [Uint8Array.of(137, 80, 78, 71, 13, 10, 26, 10), chunk('IHDR', ihdr), chunk('IDAT', zlibSync(raw, { level: 9 })), chunk('IEND', new Uint8Array())];
  return Buffer.concat(parts.map((p) => Buffer.from(p)));
}

// the mark in unit coordinates (0..1): a disc and a pin (a ring on a point)
function inPin(u, v) {
  const head = Math.hypot(u - 0.5, v - 0.43);
  if (head < 0.13) return head > 0.055; // the ring, its hole shows the disc
  // the point: a triangle under the head, its tip at (0.5, 0.72)
  if (v < 0.47 || v > 0.72) return false;
  const half = 0.115 * (0.72 - v) / 0.25;
  return Math.abs(u - 0.5) < half;
}
function mark(u, v, scale) {
  const d = Math.hypot(u - 0.5, v - 0.5);
  if (d > 0.3 * scale) return null;
  const [uu, vv] = [(u - 0.5) / scale + 0.5, (v - 0.5) / scale + 0.5];
  return inPin(uu, vv) ? SPRUCE : HONEY;
}

const icon = (size) => png(size, (x, y) => [...(mark(x / size, y / size, 1) ?? SPRUCE), 255]);
const foreground = (size) => png(size, (x, y) => {
  const c = mark(x / size, y / size, 0.62); // the adaptive icon's safe zone
  return c ? [...c, 255] : [0, 0, 0, 0];
});
const splash = (size) => png(size, (x, y) => {
  const c = mark(x / size, y / size, 1.5);
  return c ? [...c, 255] : [...PAPER, 0];
});

const out = new URL('../assets/', import.meta.url);
writeFileSync(new URL('icon.png', out), icon(1024));
writeFileSync(new URL('adaptive-icon.png', out), foreground(1024));
writeFileSync(new URL('splash-icon.png', out), splash(512));
console.log('icons written');
