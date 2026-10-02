// @ts-check
/** Minimal PNG reading (the size) and writing (flat placeholder art) with node:zlib — no image library. */
import { deflateSync } from 'node:zlib';

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** @param {Buffer} buf @returns {{width: number, height: number} | null} the IHDR size, or null when it isn't a PNG */
export function pngSize(buf) {
  if (buf.length < 24 || !buf.subarray(0, 8).equals(SIGNATURE) || buf.toString('latin1', 12, 16) !== 'IHDR') return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

const CRC = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
/** @param {Buffer} b */
const crc32 = (b) => {
  let c = 0xffffffff;
  for (const x of b) c = (CRC[(c ^ x) & 255] ?? 0) ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
/** @param {string} type @param {Buffer} data */
function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'latin1');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}

/** @param {string} hex #rrggbb @returns {number[]} */
export const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));

/**
 * A flat RGB placeholder: `bands` from the top, each {until: fraction of the height, color: [r, g, b]}.
 * Identical rows compress to a few kilobytes even at 1320 × 2868.
 * @param {number} width @param {number} height @param {{until: number, color: number[]}[]} bands
 */
export function placeholderPng(width, height, bands) {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height);
  /** @type {Map<number[], Buffer>} */
  const rows = new Map();
  for (let y = 0; y < height; y++) {
    const band = bands.find((b) => y < b.until * height) ?? bands[bands.length - 1];
    if (!band) throw new Error('placeholderPng needs at least one band');
    let row = rows.get(band.color);
    if (!row) {
      row = Buffer.alloc(stride);
      for (let x = 0; x < width; x++) row.set(band.color, 1 + x * 3);
      rows.set(band.color, row);
    }
    row.copy(raw, y * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr.set([8, 2, 0, 0, 0], 8); // 8-bit RGB, no interlace
  return Buffer.concat([SIGNATURE, chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
