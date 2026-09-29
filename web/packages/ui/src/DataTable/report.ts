/**
 * Report builders — pure functions from rows + columns to file contents. No DOM; see `download.ts`.
 * CSV (UTF-8 with BOM), Excel (.xlsx, Office Open XML in a stored ZIP — no dependency), print HTML.
 */
import { TIME_ZONE, type Locale } from '../i18n';
import { cellText, isBlank, subText, valueOf, type NormalizedColumn } from './model';

export interface ReportCell {
  /** Display text (CSV, print). */
  text: string;
  /** Numeric value for spreadsheets (money in dollars). */
  num?: number;
  money?: boolean;
}

export interface ReportMatrix {
  head: string[];
  body: ReportCell[][];
}

/** One report column per selected column, plus one for its `sub` field. */
export function buildReport<T>(
  rows: readonly T[],
  columns: readonly NormalizedColumn<T>[],
  locale: Locale,
  /** Header for a `sub` column without its own `subLabel`. */
  subLabel: (col: NormalizedColumn<T>) => string,
): ReportMatrix {
  const head: string[] = [];
  columns.forEach((c) => {
    head.push(c.label);
    if (c.sub) head.push(c.subLabel ?? subLabel(c));
  });
  const body = rows.map((r) => {
    const out: ReportCell[] = [];
    columns.forEach((c) => {
      const v = valueOf(r, c.key);
      const cell: ReportCell = { text: cellText(c, r, locale) };
      if (c.isNum && typeof v === 'number' && !c.format) {
        cell.num = c.type === 'money' ? v / 100 : v;
        cell.money = c.type === 'money';
      }
      out.push(cell);
      if (c.sub) out.push({ text: subText(c, r) });
    });
    return out;
  });
  return { head, body };
}

// ── CSV ──────────────────────────────────────────────────────────────────────
/** Neutralises spreadsheet formula injection (=, +, @, tab, CR, or "-" not followed by a number). */
const guardFormula = (t: string) => (/^[=+@\t\r]/.test(t) || /^-(?![\d.])/.test(t) ? `'${t}` : t);

export function csvEscape(value: string): string {
  const t = guardFormula(value);
  return /[",\r\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
}

/** RFC 4180 CSV with a UTF-8 BOM so Excel opens accents correctly. */
export function toCsv({ head, body }: ReportMatrix): string {
  const lines = [head, ...body.map((r) => r.map((c) => c.text))].map((r) => r.map(csvEscape).join(','));
  return '﻿' + lines.join('\r\n') + '\r\n';
}

// ── XLSX ─────────────────────────────────────────────────────────────────────
const xmlEscape = (t: string) =>
  t
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')
    .replace(/[&<>"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[ch]!);

export function columnLetter(index: number): string {
  let n = index + 1;
  let s = '';
  while (n > 0) {
    const m = (n - 1) % 26;
    s = String.fromCharCode(65 + m) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

/** Excel sheet names: ≤ 31 chars, none of : \ / ? * [ ]. */
export const sheetName = (t: string) => (t.replace(/[:\\/?*[\]]/g, ' ').trim().slice(0, 31) || 'Report');

const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';

function sheetXml({ head, body }: ReportMatrix): string {
  const strCell = (ref: string, t: string, s: number) => `<c r="${ref}" t="inlineStr"${s ? ` s="${s}"` : ''}><is><t xml:space="preserve">${xmlEscape(t)}</t></is></c>`;
  const rows: string[] = [];
  rows.push(`<row r="1">${head.map((h, i) => strCell(`${columnLetter(i)}1`, h, 1)).join('')}</row>`);
  body.forEach((r, ri) => {
    const n = ri + 2;
    const cells = r.map((c, ci) => {
      const ref = `${columnLetter(ci)}${n}`;
      if (c.num != null && Number.isFinite(c.num)) return `<c r="${ref}"${c.money ? ' s="2"' : ''}><v>${c.num}</v></c>`;
      if (c.text === '' ) return '';
      return strCell(ref, c.text, 0);
    });
    rows.push(`<row r="${n}">${cells.join('')}</row>`);
  });
  const widths = head.map((h, i) => {
    const longest = Math.max(h.length, ...body.map((r) => r[i]?.text.length ?? 0));
    return `<col min="${i + 1}" max="${i + 1}" width="${Math.min(60, Math.max(8, longest + 2))}" customWidth="1"/>`;
  });
  return (
    XML +
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>' +
    (widths.length ? `<cols>${widths.join('')}</cols>` : '') +
    `<sheetData>${rows.join('')}</sheetData></worksheet>`
  );
}

const STYLES =
  XML +
  '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
  '<numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;$&quot;#,##0.00"/></numFmts>' +
  '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>' +
  '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>' +
  '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>' +
  '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
  '<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>' +
  '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>' +
  '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>' +
  '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
  '</styleSheet>';

/** A real .xlsx workbook with one sheet: bold frozen header row, numbers as numbers, money as "$"#,##0.00. */
export function toXlsx(matrix: ReportMatrix, name: string): Uint8Array<ArrayBuffer> {
  const files: [string, string][] = [
    [
      '[Content_Types].xml',
      XML +
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
        '<Default Extension="xml" ContentType="application/xml"/>' +
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
        '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
        '</Types>',
    ],
    [
      '_rels/.rels',
      XML +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
        '</Relationships>',
    ],
    [
      'xl/workbook.xml',
      XML +
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
        `<sheets><sheet name="${xmlEscape(sheetName(name))}" sheetId="1" r:id="rId1"/></sheets></workbook>`,
    ],
    [
      'xl/_rels/workbook.xml.rels',
      XML +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' +
        '</Relationships>',
    ],
    ['xl/styles.xml', STYLES],
    ['xl/worksheets/sheet1.xml', sheetXml(matrix)],
  ];
  const enc = new TextEncoder();
  return zipStore(files.map(([path, body]) => ({ path, data: enc.encode(body) })));
}

// ── ZIP (stored, no compression) ────────────────────────────────────────────
let crcTable: Uint32Array | undefined;
export function crc32(data: Uint8Array): number {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c >>> 0;
    }
  }
  let crc = 0xffffffff;
  for (let i = 0; i < data.length; i++) crc = crcTable[(crc ^ data[i]!) & 0xff]! ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

export function zipStore(entries: { path: string; data: Uint8Array }[], date = new Date(2026, 0, 1)): Uint8Array<ArrayBuffer> {
  const enc = new TextEncoder();
  const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
  const dosDate = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;
  entries.forEach(({ path, data }) => {
    const name = enc.encode(path);
    const crc = crc32(data);
    const local = new Uint8Array(30 + name.length + data.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true); // UTF-8 names
    lv.setUint16(8, 0, true); // stored
    lv.setUint16(10, dosTime, true);
    lv.setUint16(12, dosDate, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, data.length, true);
    lv.setUint32(22, data.length, true);
    lv.setUint16(26, name.length, true);
    lv.setUint16(28, 0, true);
    local.set(name, 30);
    local.set(data, 30 + name.length);

    const central = new Uint8Array(46 + name.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, dosTime, true);
    cv.setUint16(14, dosDate, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, data.length, true);
    cv.setUint32(24, data.length, true);
    cv.setUint16(28, name.length, true);
    cv.setUint32(42, offset, true);
    central.set(name, 46);

    locals.push(local);
    centrals.push(central);
    offset += local.length;
  });
  const cdSize = centrals.reduce((s, c) => s + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, cdSize, true);
  ev.setUint32(16, offset, true);
  const out = new Uint8Array(offset + cdSize + end.length);
  let p = 0;
  [...locals, ...centrals, end].forEach((part) => {
    out.set(part, p);
    p += part.length;
  });
  return out;
}

// ── Print / PDF ──────────────────────────────────────────────────────────────
export interface PrintOptions {
  title: string;
  heading: string;
  /** Extra line under the heading (e.g. active filters). */
  note?: string;
  lang: string;
  /** Resolved base token values (`--color-text`, `--font-body`, …) from the host page. */
  cssVars?: Record<string, string>;
  /** Auto-open the print dialog when loaded. */
  autoPrint?: boolean;
}

const htmlEscape = (t: string) => t.replace(/[&<>"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[ch]!);

/** A standalone, print-styled HTML document (the viewer prints or saves it as PDF). */
export function toPrintHtml({ head, body }: ReportMatrix, o: PrintOptions): string {
  const vars = Object.entries(o.cssVars ?? {})
    .filter(([k, v]) => /^--[a-z0-9-]+$/.test(k) && v && !/[<>{};]/.test(v))
    .map(([k, v]) => `${k}:${v}`)
    .join(';');
  const numericCol = head.map((_, i) => body.length > 0 && body.every((r) => r[i]?.num != null || isBlank(r[i]?.text) || r[i]?.text === '—'));
  const th = head.map((h, i) => `<th${numericCol[i] ? ' class="n"' : ''}>${htmlEscape(h)}</th>`).join('');
  const trs = body.map((r) => `<tr>${r.map((c, i) => `<td${numericCol[i] ? ' class="n"' : ''}>${htmlEscape(c.text)}</td>`).join('')}</tr>`).join('');
  return `<!doctype html><html lang="${htmlEscape(o.lang)}"><head><meta charset="utf-8"><title>${htmlEscape(o.title)}</title><style>
:root{${vars}}
body{font:13px/1.45 var(--font-body,system-ui,sans-serif);color:var(--color-text,CanvasText);margin:32px}
h1{font-family:var(--font-heading,Georgia,serif);font-weight:500;font-size:22px;margin:0 0 4px}
p{margin:0 0 16px;color:color-mix(in oklch,var(--color-text,CanvasText) 70%,transparent)}
table{border-collapse:collapse;width:100%}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid color-mix(in oklch,var(--color-text,CanvasText) 14%,transparent);vertical-align:top}
th{background:color-mix(in oklch,var(--color-text,CanvasText) 5%,transparent);font-size:12px}
.n{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
thead{display:table-header-group}tr{break-inside:avoid}
@page{margin:16mm}
</style></head><body><h1>${htmlEscape(o.heading)}</h1>${o.note ? `<p>${htmlEscape(o.note)}</p>` : ''}<table><thead><tr>${th}</tr></thead><tbody>${trs}</tbody></table>${
    o.autoPrint ? '<script>addEventListener("load",function(){focus();print()})</script>' : ''
  }</body></html>`;
}

// ── file naming ──────────────────────────────────────────────────────────────
/** YYYY-MM-DD in America/Edmonton. */
export const reportDate = (d = new Date()) => new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);

export function reportFileName(stem: string, ext: string, d = new Date()): string {
  const slug =
    stem
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '') || 'report';
  return `${slug}-${reportDate(d)}.${ext}`;
}

export const REPORT_MIME = {
  csv: 'text/csv;charset=utf-8',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pdf: 'text/html;charset=utf-8',
} as const;
