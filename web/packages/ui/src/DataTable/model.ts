/**
 * Pure table logic — no React, no DOM. Everything here is unit-tested and shared by the
 * client-side row models. A server-driven mode would replace the filtering/sorting parts
 * with query parameters and keep the rest.
 */
import { formatMoney, formatNumber, type Locale } from '../i18n';
import type { DataTableAction, DataTableColumn, DataTableColumnType, DataTablePermissions, DataTableTone, RowKey } from './types';

export interface NormalizedColumn<T> extends DataTableColumn<T> {
  type: DataTableColumnType;
  isNum: boolean;
  filter: 'facet' | 'range' | false;
  editable: boolean;
  primary: boolean;
  priority: number;
}

export function normalizeColumns<T>(columns: readonly DataTableColumn<T>[]): NormalizedColumn<T>[] {
  const explicit = columns.findIndex((c) => c.primary);
  const primaryIndex = explicit >= 0 ? explicit : 0;
  return columns.map((c, i) => {
    const type = c.type ?? 'text';
    const isNum = type === 'num' || type === 'money';
    return {
      ...c,
      type,
      isNum,
      filter: c.filter ?? (type === 'tag' ? 'facet' : isNum ? 'range' : false),
      editable: c.editable !== false,
      primary: i === primaryIndex,
      priority: c.priority ?? 100 - i,
    };
  });
}

export const resolvePermissions = (can?: DataTablePermissions) => ({ create: false, update: false, delete: false, export: true, ...can });

export const valueOf = <T>(row: T, key: string): unknown => (row as Record<string, unknown>)[key];

export const isBlank = (v: unknown) => v == null || v === '';

/** Numeric value of a cell: numbers as-is, strings like "$1,912.40", "−3", "14 min" parsed; otherwise NaN. */
export function parseNumeric(v: unknown): number {
  if (typeof v === 'number') return v;
  if (v == null || typeof v === 'boolean') return NaN;
  const m = String(v)
    .replace(/[,$\s  ]/g, '')
    .replace('−', '-')
    .match(/^-?\d+(\.\d+)?/);
  return m ? parseFloat(m[0]) : NaN;
}

const collator = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });

/** Numeric/money-aware ascending comparator: numbers compare as numbers, everything else with a numeric collator. */
export function compareValues(a: unknown, b: unknown): number {
  const an = parseNumeric(a);
  const bn = parseNumeric(b);
  if (!Number.isNaN(an) && !Number.isNaN(bn)) return an - bn;
  return collator.compare(isBlank(a) ? '' : String(a), isBlank(b) ? '' : String(b));
}

export interface SortEntry { id: string; desc: boolean }

/**
 * Click cycles asc → desc → off. Shift-click adds/cycles a secondary sort and keeps the others;
 * a plain click on a column that is part of a multi-sort collapses to that column.
 */
export function nextSorting(sorting: readonly SortEntry[], id: string, multi: boolean): SortEntry[] {
  const cur = sorting.find((s) => s.id === id);
  if (!cur) return multi ? [...sorting, { id, desc: false }] : [{ id, desc: false }];
  if (!cur.desc) return (multi ? sorting : [cur]).map((s) => (s.id === id ? { id, desc: true } : s));
  return multi ? sorting.filter((s) => s.id !== id) : [];
}

/** Sorts a copy of rows by the given sort entries (used for reports and tests; the table uses TanStack with `compareValues`). */
export function sortRows<T>(rows: readonly T[], sorting: readonly SortEntry[]): T[] {
  if (!sorting.length) return [...rows];
  return [...rows].sort((a, b) => {
    for (const s of sorting) {
      const d = compareValues(valueOf(a, s.id), valueOf(b, s.id));
      if (d) return s.desc ? -d : d;
    }
    return 0;
  });
}

/** Display text for a cell (also what search and reports see). Empty → "—". */
export function cellText<T>(col: DataTableColumn<T> & { type?: DataTableColumnType }, row: T, locale: Locale): string {
  const v = valueOf(row, col.key);
  if (col.format) return col.format(v, row);
  if (isBlank(v)) return '—';
  if (typeof v === 'number') {
    if (col.type === 'money') return formatMoney(v, locale);
    if (col.type === 'num') return formatNumber(v, locale);
  }
  return String(v);
}

export const subText = <T>(col: DataTableColumn<T>, row: T): string => {
  if (!col.sub) return '';
  const v = valueOf(row, col.sub);
  return isBlank(v) ? '' : String(v);
};

/** Global search: case-insensitive substring over every column's display text, raw value and sub value. */
export function matchesSearch<T>(row: T, columns: readonly DataTableColumn<T>[], query: string, locale: Locale): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return columns.some((c) => {
    const raw = valueOf(row, c.key);
    return (
      (!isBlank(raw) && String(raw).toLowerCase().includes(q)) ||
      cellText(c, row, locale).toLowerCase().includes(q) ||
      subText(c, row).toLowerCase().includes(q)
    );
  });
}

export type FacetFilterValue = readonly string[];
export interface RangeFilterValue { min?: string; max?: string }

/** Facet bucket for a value: its string form, "—" for empty. */
export const facetKey = (v: unknown) => (isBlank(v) ? '—' : String(v));

export function passesFacet<T>(row: T, key: string, selected: FacetFilterValue | undefined): boolean {
  if (!selected || selected.length === 0) return true;
  return selected.includes(facetKey(valueOf(row, key)));
}

/** Number used for range filters. Money cents are compared in dollars — what the viewer types. */
export function rangeNumber<T>(row: T, col: { key: string; type?: DataTableColumnType }): number {
  const v = valueOf(row, col.key);
  const n = parseNumeric(v);
  return col.type === 'money' && typeof v === 'number' ? n / 100 : n;
}

export function passesRange<T>(row: T, col: { key: string; type?: DataTableColumnType }, range: RangeFilterValue | undefined): boolean {
  if (!range) return true;
  const n = rangeNumber(row, col);
  const min = range.min?.trim();
  const max = range.max?.trim();
  if (min && !(n >= Number(min))) return false;
  if (max && !(n <= Number(max))) return false;
  return true;
}

export const isRangeActive = (r: RangeFilterValue | undefined) => !!(r && (r.min?.trim() || r.max?.trim()));

/** Facet options: the column's declared `options` order first, then any other values found in the data. */
export function facetOptions<T>(col: DataTableColumn<T>, rows: readonly T[]): string[] {
  const found = new Set(rows.map((r) => facetKey(valueOf(r, col.key))));
  const ordered = (col.options ?? []).filter((o) => found.has(o));
  return [...ordered, ...[...found].filter((v) => !ordered.includes(v))];
}

export function facetCounts<T>(key: string, rows: readonly T[]): Map<string, number> {
  const m = new Map<string, number>();
  rows.forEach((r) => {
    const k = facetKey(valueOf(r, key));
    m.set(k, (m.get(k) ?? 0) + 1);
  });
  return m;
}

export const actionApplies = <T>(a: Pick<DataTableAction<T>, 'when'>, row: T) => !a.when || a.when.in.includes(String(valueOf(row, a.when.key)));

export const actionAllowed = <T>(a: Pick<DataTableAction<T>, 'perm'>, can: ReturnType<typeof resolvePermissions>) => !a.perm || !!can[a.perm];

/** Value→tone map aggregated from per-row tones, so any row with the same value shares its tone. */
export function buildToneMap<T>(rows: readonly T[], columns: readonly DataTableColumn<T>[], rowTones?: (row: T) => Partial<Record<RowKey<T>, DataTableTone>> | undefined) {
  const map = new Map<string, DataTableTone>();
  if (!rowTones) return map;
  rows.forEach((r) => {
    const t = rowTones(r);
    if (!t) return;
    columns.forEach((c) => {
      const tone = t[c.key];
      if (tone && c.type === 'tag') map.set(`${c.key}|${facetKey(valueOf(r, c.key))}`, tone);
    });
  });
  return map;
}

export function resolveTone<T>(col: DataTableColumn<T>, row: T, toneMap: Map<string, DataTableTone>, rowTones?: (row: T) => Partial<Record<RowKey<T>, DataTableTone>> | undefined): DataTableTone {
  const v = facetKey(valueOf(row, col.key));
  return col.tones?.[v] ?? rowTones?.(row)?.[col.key] ?? toneMap.get(`${col.key}|${v}`) ?? 'tag-neutral';
}

// ── responsive layout ────────────────────────────────────────────────────────
export const CARD_BREAKPOINT = 600;
const MIN_TABLE_COLUMNS = 3;

export interface LayoutInput<C extends { key: string; primary: boolean; priority: number }> {
  width: number;
  columns: readonly C[];
  /** Icon-only row actions (view/open, edit, delete). */
  iconActions: number;
  hasInlineAction: boolean;
}

/** Estimated width a table with `n` data columns needs (checkbox + columns + actions + padding). */
export const tableWidthFor = (n: number, iconActions: number, hasInlineAction: boolean) => 56 + n * 112 + iconActions * 40 + (hasInlineAction ? 170 : 0) + 24;

/** Drops the lowest-priority columns until the table fits (keeping at least 3), then falls back to cards. */
export function computeLayout<C extends { key: string; primary: boolean; priority: number }>({ width, columns, iconActions, hasInlineAction }: LayoutInput<C>) {
  let shown = [...columns];
  const autoHidden: C[] = [];
  const dropOrder = columns
    .map((c, i) => ({ c, i }))
    .filter(({ c }) => !c.primary)
    .sort((a, b) => a.c.priority - b.c.priority || b.i - a.i)
    .map(({ c }) => c);
  const fits = () => tableWidthFor(shown.length, iconActions, hasInlineAction) <= width;
  while (shown.length > MIN_TABLE_COLUMNS && !fits() && dropOrder.length) {
    const d = dropOrder.shift()!;
    shown = shown.filter((c) => c !== d);
    autoHidden.push(d);
  }
  const cardMode = width < CARD_BREAKPOINT || !fits();
  return { shown, autoHidden, cardMode };
}

/** English fallback plural — callers pass a localised `plural` for anything else. */
export const pluralize = (entity: string) => (/[^aeiou]y$/i.test(entity) ? entity.slice(0, -1) + 'ies' : /(s|x|ch|sh)$/i.test(entity) ? entity + 'es' : entity + 's');

export const capitalize = (t: string) => (t ? t[0]!.toUpperCase() + t.slice(1) : t);
