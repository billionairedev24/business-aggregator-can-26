import { unzlibSync } from 'fflate';

import { signaturePng } from '../src/signature/png';

describe('the signature PNG', () => {
  const strokes = [
    [
      { x: 10, y: 50 },
      { x: 100, y: 20 },
      { x: 200, y: 80 },
    ],
    [
      { x: 50, y: 90 },
      { x: 250, y: 90 },
    ],
  ];

  it('is a PNG the api recognises from its bytes, 600 × 240 grey', () => {
    const png = signaturePng(strokes, 300, 120)!;
    expect([...png.slice(0, 8)]).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    const view = new DataView(png.buffer, png.byteOffset);
    expect(String.fromCharCode(...png.slice(12, 16))).toBe('IHDR');
    expect(view.getUint32(16)).toBe(600);
    expect(view.getUint32(20)).toBe(240);
    expect(png.length).toBeLessThan(5 * 1024 * 1024);
  });

  it('carries the ink where the strokes went', () => {
    const png = signaturePng(strokes, 300, 120)!;
    const len = new DataView(png.buffer, png.byteOffset).getUint32(33);
    expect(String.fromCharCode(...png.slice(37, 41))).toBe('IDAT');
    const raw = unzlibSync(png.slice(41, 41 + len));
    expect(raw.length).toBe(601 * 240); // a filter byte + 600 pixels per row
    const at = (x: number, y: number) => raw[y * 601 + 1 + x];
    expect(at(20, 220)).toBe(255); // blank paper
    expect(at(20, 100)).toBe(0); // where the first stroke starts (10, 50) × 2
    expect(at(300, 180)).toBe(0); // the second stroke, scaled ×2
    expect(raw.filter((b, i) => i % 601 !== 0 && b === 0).length).toBeGreaterThan(500);
  });

  it('is nothing for a tap or an empty pad', () => {
    expect(signaturePng([], 300, 120)).toBeNull();
    expect(signaturePng([[{ x: 5, y: 5 }]], 300, 120)).toBeNull();
  });
});
