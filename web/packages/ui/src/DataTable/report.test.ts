import { describe, expect, it } from 'vitest';
import { configurePlatformTimeZone } from '../i18n';
import { normalizeColumns } from './model';
import { buildReport, columnLetter, crc32, csvEscape, reportFileName, sheetName, toCsv, toPrintHtml, toXlsx, zipStore, type ReportMatrix } from './report';
import type { DataTableColumn } from './types';

interface Row { id: string; name: string; sku: string; price: number; qty: number | null; note: string }
const rows: Row[] = [
  { id: '1', name: 'Oil & filter', sku: 'SVC-OF', price: 7900, qty: 28, note: 'Says "hi", then\nleaves' },
  { id: '2', name: '=HYPERLINK("x")', sku: '', price: 191240, qty: null, note: '-3 credit' },
];
const cols = normalizeColumns<Row>([
  { key: 'name', label: 'Service', sub: 'sku', subLabel: 'SKU' },
  { key: 'price', label: 'Price', type: 'money' },
  { key: 'qty', label: 'Qty', type: 'num' },
  { key: 'note', label: 'Note' },
] satisfies DataTableColumn<Row>[]);
const matrix = buildReport(rows, cols, 'en', (c) => `${c.label} detail`);
const dec = new TextDecoder();

describe('buildReport', () => {
  it('adds a column for each sub field and keeps display text + numeric values', () => {
    expect(matrix.head).toEqual(['Service', 'SKU', 'Price', 'Qty', 'Note']);
    expect(matrix.body[0]!.map((c) => c.text)).toEqual(['Oil & filter', 'SVC-OF', '$79.00', '28', 'Says "hi", then\nleaves']);
    expect(matrix.body[0]![2]).toMatchObject({ num: 79, money: true });
    expect(matrix.body[1]![3]).toEqual({ text: '—' });
  });
  it('localises display text', () => {
    const fr = buildReport(rows, cols, 'fr', (c) => c.label);
    expect(fr.body[1]![2]!.text).toMatch(/^1\s912,40\s\$$/);
  });
});

describe('CSV', () => {
  it('starts with a BOM, uses CRLF and RFC 4180 quoting', () => {
    const csv = toCsv(matrix);
    expect(csv.charCodeAt(0)).toBe(0xfeff);
    const lines = csv.slice(1).split('\r\n');
    expect(lines[0]).toBe('Service,SKU,Price,Qty,Note');
    expect(lines[1]).toBe('Oil & filter,SVC-OF,$79.00,28,"Says ""hi"", then\nleaves"');
  });
  it('neutralises formula injection but not negative numbers', () => {
    expect(csvEscape('=HYPERLINK("x")')).toBe(`"'=HYPERLINK(""x"")"`);
    expect(csvEscape('@SUM(A1)')).toBe("'@SUM(A1)");
    expect(csvEscape('-cmd')).toBe("'-cmd");
    expect(csvEscape('-3.50')).toBe('-3.50');
    expect(csvEscape('-.75')).toBe('-.75');
    expect(csvEscape('-$1,079.00')).toBe('"-$1,079.00"');
    expect(csvEscape('-79,00\u00a0$')).toBe('"-79,00\u00a0$"');
    expect(csvEscape('-3 credit')).toBe("'-3 credit");
    expect(csvEscape("-2+cmd|' /C calc'!A0")).toBe("'-2+cmd|' /C calc'!A0"); // S-104
    expect(csvEscape('+1')).toBe("'+1");
  });
});

/** Minimal ZIP reader for stored entries — enough to check our writer. */
function unzip(buf: Uint8Array): Record<string, string> {
  const v = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const eocd = buf.length - 22;
  expect(v.getUint32(eocd, true)).toBe(0x06054b50);
  const n = v.getUint16(eocd + 10, true);
  let p = v.getUint32(eocd + 16, true);
  const out: Record<string, string> = {};
  for (let i = 0; i < n; i++) {
    expect(v.getUint32(p, true)).toBe(0x02014b50);
    const crc = v.getUint32(p + 16, true);
    const size = v.getUint32(p + 20, true);
    const nameLen = v.getUint16(p + 28, true);
    const off = v.getUint32(p + 42, true);
    const name = dec.decode(buf.subarray(p + 46, p + 46 + nameLen));
    expect(v.getUint32(off, true)).toBe(0x04034b50);
    const dataStart = off + 30 + v.getUint16(off + 26, true);
    const data = buf.subarray(dataStart, dataStart + size);
    expect(crc32(data)).toBe(crc);
    out[name] = dec.decode(data);
    p += 46 + nameLen;
  }
  return out;
}

describe('XLSX', () => {
  it('crc32 matches the reference check value', () => {
    expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926);
  });
  it('zipStore writes readable stored entries', () => {
    const z = zipStore([{ path: 'a.txt', data: new TextEncoder().encode('hello') }, { path: 'dir/é.xml', data: new TextEncoder().encode('<x/>') }]);
    expect(unzip(z)).toEqual({ 'a.txt': 'hello', 'dir/é.xml': '<x/>' });
  });
  it('produces a workbook with typed cells, styles and a safe sheet name', () => {
    const files = unzip(toXlsx(matrix, 'Services: [all]'));
    expect(Object.keys(files).sort()).toEqual(['[Content_Types].xml', '_rels/.rels', 'xl/_rels/workbook.xml.rels', 'xl/styles.xml', 'xl/workbook.xml', 'xl/worksheets/sheet1.xml']);
    expect(files['xl/workbook.xml']).toContain('<sheet name="Services   all" sheetId="1" r:id="rId1"/>');
    const sheet = files['xl/worksheets/sheet1.xml']!;
    expect(sheet).toContain('<c r="A1" t="inlineStr" s="1"><is><t xml:space="preserve">Service</t></is></c>');
    expect(sheet).toContain('<c r="A2" t="inlineStr"><is><t xml:space="preserve">Oil &amp; filter</t></is></c>');
    expect(sheet).toContain('<c r="C2" s="2"><v>79</v></c>');
    expect(sheet).toContain('<c r="D2"><v>28</v></c>');
    expect(sheet).toContain('<c r="C3" s="2"><v>1912.4</v></c>');
    expect(sheet).toContain('<c r="D3" t="inlineStr"><is><t xml:space="preserve">—</t></is></c>');
    expect(sheet).not.toContain('r="B3"'); // empty sub value → no cell
    expect(files['xl/styles.xml']).toContain('formatCode="&quot;$&quot;#,##0.00"');
  });
  it('column letters and sheet names', () => {
    expect([0, 25, 26, 701, 702].map(columnLetter)).toEqual(['A', 'Z', 'AA', 'ZZ', 'AAA']);
    expect(sheetName('a'.repeat(40))).toHaveLength(31);
    expect(sheetName('   ')).toBe('Report');
  });
});

describe('print HTML', () => {
  it('escapes content, right-aligns numeric columns and embeds safe theme vars', () => {
    const m: ReportMatrix = { head: ['Name <b>', 'Amount'], body: [[{ text: '<script>x</script>' }, { text: '$1.00', num: 1, money: true }]] };
    const html = toPrintHtml(m, { title: 'Orders report', heading: 'Orders · 1 row', note: 'Status: Live', lang: 'fr-CA', cssVars: { '--color-text': 'oklch(0.2 0 0)', '--bad': 'x}</style><script>', 'not-var': 'y' }, autoPrint: true });
    expect(html).toContain('<html lang="fr-CA">');
    expect(html).toContain('<th>Name &lt;b&gt;</th><th class="n">Amount</th>');
    expect(html).toContain('<td>&lt;script&gt;x&lt;/script&gt;</td><td class="n">$1.00</td>');
    expect(html).toContain(':root{--color-text:oklch(0.2 0 0)}');
    expect(html).not.toContain('--bad');
    expect(html).toContain('<p>Status: Live</p>');
    expect(html).toContain('print()');
  });
});

it('reportFileName slugs accents and stamps the platform date', () => {
  configurePlatformTimeZone('America/Edmonton'); // test data: a Mountain-time platform zone
  // 2026-09-30 03:00 UTC is still Sep 29 in Edmonton (UTC−6).
  expect(reportFileName('Écritures du grand livre', 'csv', new Date('2026-09-30T03:00:00Z'))).toBe('ecritures-du-grand-livre-2026-09-29.csv');
  expect(reportFileName('***', 'xlsx', new Date('2026-01-05T18:00:00Z'))).toBe('report-2026-01-05.xlsx');
});
