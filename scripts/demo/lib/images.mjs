// Demo pictures drawn at run time — nothing downloaded, nothing hotlinked, nothing stored in the repository: a PNG with
// a white border (the listing rules: ≥ 1000 px, the main image on white), a coloured panel, a simple icon shape and the
// item's name in a 5 × 7 pixel font. Each picture is unique to its text (vetting flags a reused image).
import { deflateSync } from 'node:zlib';

// 5 × 7 glyphs, one 5-bit row per number (bit 4 = left column).
const FONT = {
  A: [14, 17, 17, 31, 17, 17, 17], B: [30, 17, 17, 30, 17, 17, 30], C: [14, 17, 16, 16, 16, 17, 14],
  D: [30, 17, 17, 17, 17, 17, 30], E: [31, 16, 16, 30, 16, 16, 31], F: [31, 16, 16, 30, 16, 16, 16],
  G: [14, 17, 16, 23, 17, 17, 15], H: [17, 17, 17, 31, 17, 17, 17], I: [14, 4, 4, 4, 4, 4, 14],
  J: [7, 2, 2, 2, 2, 18, 12], K: [17, 18, 20, 24, 20, 18, 17], L: [16, 16, 16, 16, 16, 16, 31],
  M: [17, 27, 21, 21, 17, 17, 17], N: [17, 17, 25, 21, 19, 17, 17], O: [14, 17, 17, 17, 17, 17, 14],
  P: [30, 17, 17, 30, 16, 16, 16], Q: [14, 17, 17, 17, 21, 18, 13], R: [30, 17, 17, 30, 20, 18, 17],
  S: [15, 16, 16, 14, 1, 1, 30], T: [31, 4, 4, 4, 4, 4, 4], U: [17, 17, 17, 17, 17, 17, 14],
  V: [17, 17, 17, 17, 17, 10, 4], W: [17, 17, 17, 21, 21, 21, 10], X: [17, 17, 10, 4, 10, 17, 17],
  Y: [17, 17, 10, 4, 4, 4, 4], Z: [31, 1, 2, 4, 8, 16, 31],
  0: [14, 17, 19, 21, 25, 17, 14], 1: [4, 12, 4, 4, 4, 4, 14], 2: [14, 17, 1, 2, 4, 8, 31],
  3: [31, 2, 4, 2, 1, 17, 14], 4: [2, 6, 10, 18, 31, 2, 2], 5: [31, 16, 30, 1, 1, 17, 14],
  6: [6, 8, 16, 30, 17, 17, 14], 7: [31, 1, 2, 4, 8, 8, 8], 8: [14, 17, 17, 14, 17, 17, 14],
  9: [14, 17, 17, 15, 1, 2, 12],
  ' ': [0, 0, 0, 0, 0, 0, 0], '&': [12, 18, 20, 8, 21, 18, 13], "'": [4, 4, 8, 0, 0, 0, 0],
  '-': [0, 0, 0, 31, 0, 0, 0], '.': [0, 0, 0, 0, 0, 12, 12], ',': [0, 0, 0, 0, 12, 4, 8],
  '/': [1, 1, 2, 4, 8, 16, 16], '+': [0, 4, 4, 31, 4, 4, 0], '%': [24, 25, 2, 4, 8, 19, 3],
  '$': [4, 15, 20, 14, 5, 30, 4], '(': [2, 4, 8, 8, 8, 4, 2], ')': [8, 4, 2, 2, 2, 4, 8],
  ':': [0, 12, 12, 0, 12, 12, 0], '!': [4, 4, 4, 4, 4, 0, 4], '?': [14, 17, 1, 2, 4, 0, 4],
  '#': [10, 10, 31, 10, 31, 10, 10], '×': [0, 17, 10, 4, 10, 17, 0],
};

const plain = s => s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[·•–—]/g, '-').replace(/[«»"“”]/g, "'").toUpperCase();

class Canvas {
  constructor(w, h, [r, g, b] = [255, 255, 255]) {
    this.w = w; this.h = h;
    this.px = Buffer.alloc(w * h * 3);
    for (let i = 0; i < w * h; i++) { this.px[i * 3] = r; this.px[i * 3 + 1] = g; this.px[i * 3 + 2] = b; }
  }
  set(x, y, [r, g, b]) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 3;
    this.px[i] = r; this.px[i + 1] = g; this.px[i + 2] = b;
  }
  rect(x0, y0, w, h, c, radius = 0) {
    for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) {
      const dx = Math.max(x0 + radius - x, 0, x - (x0 + w - 1 - radius));
      const dy = Math.max(y0 + radius - y, 0, y - (y0 + h - 1 - radius));
      if (radius && dx * dx + dy * dy > radius * radius) continue;
      this.set(x, y, c);
    }
  }
  circle(cx, cy, r, c) {
    for (let y = cy - r; y <= cy + r; y++) for (let x = cx - r; x <= cx + r; x++) if ((x - cx) ** 2 + (y - cy) ** 2 <= r * r) this.set(x, y, c);
  }
  text(line, cx, y, scale, c) {
    const width = line.length * 6 * scale - scale;
    let x = Math.round(cx - width / 2);
    for (const ch of line) {
      const glyph = FONT[ch] ?? FONT['?'];
      glyph.forEach((row, gy) => {
        for (let gx = 0; gx < 5; gx++) if (row & (16 >> gx)) this.rect(x + gx * scale, y + gy * scale, scale, scale, c);
      });
      x += 6 * scale;
    }
  }
  png() {
    const raw = Buffer.alloc((this.w * 3 + 1) * this.h);
    for (let y = 0; y < this.h; y++) {
      raw[y * (this.w * 3 + 1)] = 0;
      this.px.copy(raw, y * (this.w * 3 + 1) + 1, y * this.w * 3, (y + 1) * this.w * 3);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(this.w, 0); ihdr.writeUInt32BE(this.h, 4); ihdr[8] = 8; ihdr[9] = 2;
    return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
  }
}

const CRC = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  let c = 0xffffffff;
  for (const x of td) c = CRC[(c ^ x) & 0xff] ^ (c >>> 8);
  const crc = Buffer.alloc(4); crc.writeUInt32BE((c ^ 0xffffffff) >>> 0);
  return Buffer.concat([len, td, crc]);
}

/** Words into lines of at most `max` characters. */
function wrap(text, max) {
  const lines = [];
  for (const word of plain(text).split(/\s+/).filter(Boolean)) {
    const last = lines.at(-1);
    if (last !== undefined && (last + ' ' + word).length <= max) lines[lines.length - 1] = `${last} ${word}`;
    else lines.push(word.slice(0, max));
  }
  return lines.slice(0, 4);
}

const hex = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const mix = (c, t) => c.map(v => Math.round(v + (255 - v) * t));

/** A listing picture: 1000 × 1000, white border, the panel in the business's colour, the item name and its maker. */
export function listingPicture(title, maker, colour) {
  const c = new Canvas(1000, 1000);
  const base = hex(colour);
  c.rect(90, 90, 820, 820, mix(base, 0.82), 48);
  c.circle(500, 330, 150, base);
  c.circle(500, 330, 96, mix(base, 0.55));
  const lines = wrap(title, 13);
  const scale = lines.some(l => l.length > 10) ? 9 : 11;
  lines.forEach((line, i) => c.text(line, 500, 540 + i * (scale * 9), scale, base.map(v => Math.round(v * 0.45))));
  c.text(plain(maker).slice(0, 26), 500, 860, 4, base.map(v => Math.round(v * 0.6)));
  return new Blob([c.png()], { type: 'image/png' });
}

/** A business logo: 512 × 512, its initials on its colour. */
export function logoPicture(name, colour) {
  const c = new Canvas(512, 512, hex(colour));
  const initials = plain(name).split(/[\s&]+/).filter(w => /^[A-Z0-9]/.test(w)).slice(0, 2).map(w => w[0]).join('');
  c.circle(256, 256, 200, mix(hex(colour), 0.88));
  c.text(initials, 256, 256 - 70, 20, hex(colour).map(v => Math.round(v * 0.55)));
  return new Blob([c.png()], { type: 'image/png' });
}

/** A one-page PDF for the onboarding documents the checklist asks for (marked as fake). */
export function demoPdf(title) {
  const text = `DEMO DOCUMENT - NOT REAL - ${plain(title).replace(/[()\\]/g, '')}`;
  const stream = `BT /F1 14 Tf 60 740 Td (${text}) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let body = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((o, i) => { offsets.push(body.length); body += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = body.length;
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map(o => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new Blob([body], { type: 'application/pdf' });
}
