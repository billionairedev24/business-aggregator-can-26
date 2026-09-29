import { toCsv, toXlsx, type ReportMatrix } from '@northline/ui';
import type { ImportBatch, ImportTemplate } from './api';
import type { Portal } from './model';

/**
 * Bulk-upload templates — the header row matches the server's ImportTemplate columns (attribute columns are the
 * category's attribute keys in snake_case). One example row each. Generated in the browser as .xlsx.
 */
export const TEMPLATES: Record<ImportTemplate, { columns: string[]; example: (string | number)[] }> = {
  auto_parts: {
    columns: ['sku', 'title', 'gtin', 'brand', 'mpn', 'category_id', 'price', 'stock', 'part_type', 'length', 'position'],
    example: ['WB-22', 'Bosch Icon 22" Beam Wiper Blade · all-season', '028851200226', 'Bosch', '22A', 'shop.hardware-and-auto.auto-parts', 19, 22, 'Wiper blades', '22 in', 'Front'],
  },
  groceries: {
    columns: ['sku', 'title', 'gtin', 'brand', 'category_id', 'price', 'stock', 'volume', 'storage'],
    example: ['OAT-1L', 'Oat milk · barista · 1 L', '', 'Prairie Oat Co.', 'shop.food-and-grocery.groceries', 4.49, 30, '1 L', 'Refrigerated'],
  },
  clothing: {
    columns: ['parent_sku', 'sku', 'title', 'gtin', 'brand', 'category_id', 'price', 'stock', 'department', 'material', 'size', 'colour'],
    example: ['PARKA-01', 'PARKA-01-M-BLK', 'Down parka · winter', '', 'North Pass', 'shop.apparel.clothing', 289, 4, 'Unisex', 'Blend', 'M', 'Black'],
  },
  services: {
    columns: ['sku', 'name', 'category_id', 'pricing_mode', 'price', 'duration_min', 'buffer_min', 'included', 'instant_book'],
    example: ['SVC-BI', 'Brake inspection', 'service.automotive.brakes-and-suspension', 'fixed', 89, 60, 20, 'Pads, rotors and calipers on all four wheels', 'yes'],
  },
  price_stock: { columns: ['sku', 'price', 'stock'], example: ['WB-22', 19, 22] },
};

/** Templates offered per portal (sellers: products only; providers: services only). */
export const templatesFor = (portal: Portal): ImportTemplate[] =>
  portal === 'seller' ? ['auto_parts', 'groceries', 'clothing', 'price_stock']
  : portal === 'provider' ? ['services', 'price_stock']
  : ['auto_parts', 'groceries', 'clothing', 'services', 'price_stock'];

const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

export function download(data: BlobPart, type: string, fileName: string) {
  const url = URL.createObjectURL(new Blob([data], { type }));
  const a = document.createElement('a');
  a.href = url; a.download = fileName; a.rel = 'noopener';
  document.body.appendChild(a); a.click();
  setTimeout(() => { URL.revokeObjectURL(url); a.remove(); }, 1000);
}

export function templateMatrix(template: ImportTemplate): ReportMatrix {
  const { columns, example } = TEMPLATES[template];
  return { head: columns, body: [example.map(v => (typeof v === 'number' ? { text: String(v), num: v } : { text: v }))] };
}

export function downloadTemplate(template: ImportTemplate) {
  download(toXlsx(templateMatrix(template), 'Listings'), XLSX_MIME, `northline-${template.replace('_', '-')}-template.xlsx`);
}

export function downloadErrorReport(batch: ImportBatch, head: [string, string, string]) {
  const matrix: ReportMatrix = { head, body: batch.errors.map(e => [{ text: String(e.row), num: e.row }, { text: e.sku ?? '—' }, { text: e.error }]) };
  download(toCsv(matrix), 'text/csv;charset=utf-8', `${batch.fileName.replace(/\.[^.]+$/, '')}-errors.csv`);
}
