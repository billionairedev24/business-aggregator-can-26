import {
  ArrowSquareOut,
  ArrowsDownUp,
  CaretDown,
  CaretLeft,
  CaretRight,
  CaretUp,
  Check,
  CheckCircle,
  Columns,
  Eye,
  FileArrowDown,
  FileCsv,
  FilePdf,
  Funnel,
  Lightning,
  LockSimple,
  MagnifyingGlass,
  MagnifyingGlassMinus,
  MicrosoftExcelLogo,
  PencilSimple,
  Plus,
  Trash,
  WarningCircle,
  X,
  type Icon,
} from '@phosphor-icons/react';
import {
  getCoreRowModel,
  getFacetedRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type ColumnFiltersState,
  type FilterFn,
  type PaginationState,
  type RowSelectionState,
} from '@tanstack/react-table';
import clsx from 'clsx';
import { useCallback, useEffect, useId, useMemo, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { useLocale } from '../i18n';
import { downloadBlob, openPrintWindow, readThemeVars } from './download';
import { buildFieldSpecs, initialValues, RecordForm } from './internal/RecordForm';
import { Chip, ChipRadioGroup, DataTableCheckbox, DataTableDialog, useContainerWidth, useToast } from './internal/primitives';
import { useDataTableMessages, type DataTableT } from './messages';
import {
  actionAllowed,
  actionApplies,
  buildToneMap,
  capitalize,
  cellText,
  compareValues,
  computeLayout,
  facetCounts,
  facetOptions,
  isBlank,
  isRangeActive,
  matchesSearch,
  nextSorting,
  normalizeColumns,
  passesFacet,
  passesRange,
  pluralize,
  resolvePermissions,
  resolveTone,
  subText,
  valueOf,
  type NormalizedColumn,
  type RangeFilterValue,
  type SortEntry,
} from './model';
import { buildReport, REPORT_MIME, reportDate, reportFileName, toCsv, toPrintHtml, toXlsx } from './report';
import type { DataTableAction, DataTableProps, ReportFormat, ReportScope } from './types';
import './DataTable.css';

export const PAGE_SIZES = [5, 10, 25, 50] as const;

type Dialog =
  | { mode: 'view'; id: string }
  | { mode: 'create' }
  | { mode: 'edit'; id: string }
  | { mode: 'delete'; ids: string[] }
  | { mode: 'report'; scope: ReportScope; format: ReportFormat; cols: Record<string, boolean> };

interface RowAct {
  key: string;
  label: string;
  icon: Icon;
  run: () => void;
  danger?: boolean;
  showLabel?: boolean;
  busy?: boolean;
}

const defaultRowId = (row: object) => String((row as { id?: unknown }).id ?? '');
const errMessage = (e: unknown) => (e instanceof Error ? e.message : typeof e === 'string' ? e : '');
const isInteractive = (el: EventTarget | null) => el instanceof Element && !!el.closest('button, a, input, label, select, textarea');

/**
 * The shared record list: search, facets, ranges, multi-sort, selection, column visibility,
 * pagination, reports, role-gated CRUD and custom actions. Mutations are delegated to the caller.
 */
export function DataTable<T extends object>(props: DataTableProps<T>) {
  const { entity, columns, rows, actions, loading, error, onRetry, onOpen, roleName } = props;
  const t = useDataTableMessages();
  const { locale } = useLocale();
  const plural = props.plural ?? pluralize(entity);
  const noun = (n: number) => (n === 1 ? entity : plural);
  const getRowId = props.getRowId ?? (defaultRowId as (row: T) => string);
  const can = resolvePermissions(props.can);
  const cols = useMemo(() => normalizeColumns(columns), [columns]);
  const primary = cols.find((c) => c.primary);
  const rowTones = props.rowTones;
  const toneMap = useMemo(() => buildToneMap(rows, cols, rowTones), [rows, cols, rowTones]);
  const toneOf = (c: NormalizedColumn<T>, r: T) => resolveTone(c, r, toneMap, rowTones);

  const canCreate = !!can.create && !!(props.onCreateClick || props.onCreate);
  const canUpdate = !!can.update && !!(props.onEditClick || props.onUpdate);
  const canDelete = !!can.delete && !!props.onDelete;
  const canExport = can.export !== false;
  const readOnly = !!roleName && !can.create && !can.update && !can.delete;
  const custom = useMemo(() => (props.onAction ? (actions ?? []).filter((a) => actionAllowed(a, can)) : []), [actions, props.onAction, can.create, can.update, can.delete]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── view state ────────────────────────────────────────────────────────────
  const [query, setQuery] = useState('');
  const [facets, setFacets] = useState<Record<string, string[]>>({});
  const [ranges, setRanges] = useState<Record<string, RangeFilterValue>>({});
  const [sorting, setSorting] = useState<SortEntry[]>([]);
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: props.pageSize ?? 10 });
  const [selection, setSelection] = useState<RowSelectionState>({});
  const [userHidden, setUserHidden] = useState<Record<string, boolean> | null>(null);
  const [panel, setPanel] = useState<'filters' | 'cols' | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [busy, setBusy] = useState(false);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [toast, flash] = useToast();
  const [boxRef, width] = useContainerWidth<HTMLDivElement>();
  const uid = useId();
  const filtersBtn = useRef<HTMLButtonElement>(null);
  const colsBtn = useRef<HTMLButtonElement>(null);
  const firstPage = () => setPagination((p) => ({ ...p, pageIndex: 0 }));

  const hiddenMap = useMemo(() => userHidden ?? Object.fromEntries(cols.filter((c) => c.hidden).map((c) => [c.key, true])), [userHidden, cols]);
  const visible = cols.filter((c) => c.primary || !hiddenMap[c.key]);

  // ── TanStack row models (client-side; a server mode would switch these to manual*) ──
  const columnFilters = useMemo<ColumnFiltersState>(
    () => [
      ...Object.entries(facets).filter(([, v]) => v.length).map(([id, value]) => ({ id, value })),
      ...Object.entries(ranges).filter(([, v]) => isRangeActive(v)).map(([id, value]) => ({ id, value })),
    ],
    [facets, ranges],
  );
  const columnDefs = useMemo<ColumnDef<T>[]>(
    () =>
      cols.map((c) => ({
        id: c.key,
        accessorFn: (row: T) => valueOf(row, c.key) ?? null,
        sortingFn: (a, b, id) => compareValues(a.getValue(id), b.getValue(id)),
        sortDescFirst: false,
        filterFn: (c.filter === 'range'
          ? (row, _id, v: RangeFilterValue) => passesRange(row.original, c, v)
          : (row, _id, v: string[]) => passesFacet(row.original, c.key, v)) as FilterFn<T>,
        meta: c,
      })),
    [cols],
  );
  const globalFilterFn = useCallback<FilterFn<T>>((row, id, q: string) => {
    const c = cols.find((x) => x.key === id);
    return !!c && matchesSearch(row.original, [c], q, locale);
  }, [cols, locale]);

  const table = useReactTable<T>({
    data: rows as T[],
    columns: columnDefs,
    getRowId: (r) => getRowId(r),
    state: { sorting, columnFilters, globalFilter: query, pagination, rowSelection: selection },
    onPaginationChange: setPagination,
    onRowSelectionChange: setSelection,
    globalFilterFn,
    getColumnCanGlobalFilter: () => true,
    enableMultiSort: true,
    autoResetPageIndex: false,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getFacetedRowModel: getFacetedRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });

  const filtered = table.getPrePaginationRowModel().rows.map((r) => r.original);
  const pageRows = table.getRowModel().rows.map((r) => r.original);
  const rowById = useMemo(() => new Map(rows.map((r) => [getRowId(r), r])), [rows, getRowId]);
  const { pageIndex, pageSize } = pagination;
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
  useEffect(() => {
    if (pageIndex > pageCount - 1) setPagination((p) => ({ ...p, pageIndex: pageCount - 1 }));
  }, [pageIndex, pageCount]);
  const page = Math.min(pageIndex, pageCount - 1);

  // ── selection ─────────────────────────────────────────────────────────────
  const isSel = (r: T) => !!selection[getRowId(r)];
  const selRows = rows.filter(isSel);
  const selCount = selRows.length;
  const selInFiltered = filtered.filter(isSel).length;
  const allOn = filtered.length > 0 && selInFiltered === filtered.length;
  const someOn = selInFiltered > 0 && !allOn;
  const setSel = (ids: string[], on: boolean) =>
    setSelection((s) => {
      const n = { ...s };
      ids.forEach((id) => (on ? (n[id] = true) : delete n[id]));
      return n;
    });
  const toggleAll = () => setSel(filtered.map(getRowId), !allOn);

  // ── sorting ───────────────────────────────────────────────────────────────
  const onSort = (key: string, multi: boolean) => {
    setSorting((s) => nextSorting(s, key, multi));
    firstPage();
  };

  // ── filters ───────────────────────────────────────────────────────────────
  const toggleFacet = (key: string, v: string) => {
    setFacets((f) => {
      const cur = f[key] ?? [];
      return { ...f, [key]: cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v] };
    });
    firstPage();
  };
  const setRange = (key: string, bound: 'min' | 'max', v: string) => {
    setRanges((r) => ({ ...r, [key]: { ...r[key], [bound]: v } }));
    firstPage();
  };
  const clearAll = () => {
    setFacets({});
    setRanges({});
    setQuery('');
    firstPage();
  };
  const colByKey = (k: string) => cols.find((c) => c.key === k);
  const chips: { key: string; label: string; remove: () => void }[] = [];
  Object.entries(facets).forEach(([k, vals]) =>
    vals.forEach((v) => chips.push({ key: `f:${k}:${v}`, label: t('chipFacet', { label: colByKey(k)?.label ?? k, value: v }), remove: () => toggleFacet(k, v) })),
  );
  Object.entries(ranges).forEach(([k, rg]) =>
    (['min', 'max'] as const).forEach((m) => {
      const v = rg[m]?.trim();
      if (v) chips.push({ key: `r:${k}:${m}`, label: t(m === 'min' ? 'chipMin' : 'chipMax', { label: colByKey(k)?.label ?? k, value: v }), remove: () => setRange(k, m, '') });
    }),
  );

  // ── actions ───────────────────────────────────────────────────────────────
  const runAction = async (a: DataTableAction<T>, targets: T[]) => {
    const hit = targets.filter((r) => actionApplies(a, r));
    if (!hit.length) {
      flash(t('toastNothing', { action: a.label.toLowerCase() }));
      return;
    }
    setPendingAction(a.id);
    try {
      await props.onAction?.(a, hit);
      flash(t('toastAction', { action: a.label, count: hit.length, noun: noun(hit.length) }));
    } catch (e) {
      flash(t('actionFailed', { action: a.label, message: errMessage(e) || t('genericError') }), 'error');
    } finally {
      setPendingAction(null);
    }
  };
  const openCreate = () => (props.onCreateClick ? props.onCreateClick() : setDialog({ mode: 'create' }));
  const openEdit = (r: T) => {
    if (props.onEditClick) {
      setDialog(null);
      props.onEditClick(r);
    } else setDialog({ mode: 'edit', id: getRowId(r) });
  };
  const openReport = (scope: ReportScope) => {
    setPanel(null);
    setDialog({ mode: 'report', scope, format: 'csv', cols: Object.fromEntries(visible.map((c) => [c.key, true])) });
  };
  const rowActs = (r: T): RowAct[] => {
    const out: RowAct[] = [];
    if (onOpen) out.push({ key: 'open', label: props.openLabel ?? t('open'), icon: ArrowSquareOut, run: () => onOpen(r) });
    else out.push({ key: 'view', label: t('view'), icon: Eye, run: () => setDialog({ mode: 'view', id: getRowId(r) }) });
    custom
      .filter((a) => a.inline && actionApplies(a, r))
      .forEach((a) => out.push({ key: `a:${a.id}`, label: a.label, icon: a.icon ?? Lightning, showLabel: true, busy: pendingAction === a.id, run: () => void runAction(a, [r]) }));
    if (canUpdate) out.push({ key: 'edit', label: t('edit'), icon: PencilSimple, run: () => openEdit(r) });
    if (canDelete) out.push({ key: 'delete', label: t('delete'), icon: Trash, danger: true, run: () => setDialog({ mode: 'delete', ids: [getRowId(r)] }) });
    return out;
  };

  // ── responsive layout ─────────────────────────────────────────────────────
  const layout = computeLayout({ width, columns: visible, iconActions: 1 + (canUpdate ? 1 : 0) + (canDelete ? 1 : 0), hasInlineAction: custom.some((a) => a.inline) });
  const cardMode = layout.cardMode;
  const shown = layout.shown;
  const firstTag = cols.find((c) => c.type === 'tag' && !c.primary && !hiddenMap[c.key]);

  const sortState = (key: string) => {
    const i = sorting.findIndex((s) => s.id === key);
    const cur = sorting[i];
    return { cur, rank: cur && sorting.length > 1 ? i + 1 : 0 };
  };

  // ── cells ─────────────────────────────────────────────────────────────────
  const renderValue = (c: NormalizedColumn<T>, r: T, where: 'cell' | 'field') => {
    const v = valueOf(r, c.key);
    if (c.type === 'tag' && !isBlank(v)) return <span className={clsx('tag', toneOf(c, r), where === 'field' && 'nl-dt-tag-wrap')}>{cellText(c, r, locale)}</span>;
    const sub = subText(c, r);
    if (where === 'field') return <div className="nl-dt-field-value">{sub ? `${cellText(c, r, locale)} · ${sub}` : cellText(c, r, locale)}</div>;
    return (
      <>
        <span className={clsx(c.primary && 'nl-dt-strong')}>{cellText(c, r, locale)}</span>
        {sub && <span className="nl-dt-sub">{sub}</span>}
      </>
    );
  };

  const range = filtered.length
    ? t('range', { start: page * pageSize + 1, end: Math.min(filtered.length, (page + 1) * pageSize), total: filtered.length, noun: noun(filtered.length) })
    : t('rangeEmpty', { plural, noun: plural });
  const tableLabel = props['aria-label'] ?? capitalize(plural);

  // ── dialog helpers ────────────────────────────────────────────────────────
  const closeDialog = () => {
    setBusy(false);
    setDialog(null);
  };
  const dRow = dialog && (dialog.mode === 'view' || dialog.mode === 'edit') ? rowById.get(dialog.id) : undefined;
  useEffect(() => {
    // The row vanished (deleted elsewhere / refetched) while its dialog was open.
    if (dialog && (dialog.mode === 'view' || dialog.mode === 'edit') && !dRow) setDialog(null);
  }, [dialog, dRow]);
  const detailLabel = (c: NormalizedColumn<T>) => c.subLabel ?? t('detail', { label: c.label });
  const fieldSpecs = useMemo(() => buildFieldSpecs(cols, rows, detailLabel), [cols, rows, t]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── render ────────────────────────────────────────────────────────────────
  const hasData = rows.length > 0;
  const showBody = !loading && !error;

  return (
    <div ref={boxRef} className={clsx('nl-dt', props.className)}>
      <div className="nl-dt-toolbar">
        <label className="nl-dt-search">
          <MagnifyingGlass size={17} weight="duotone" aria-hidden="true" />
          <input
            className="input"
            type="search"
            placeholder={props.searchPlaceholder ?? t('searchPlaceholder', { plural })}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              firstPage();
            }}
            aria-label={t('searchAria')}
            aria-controls={`${uid}-body`}
          />
        </label>
        <button
          ref={filtersBtn}
          type="button"
          className="btn btn-secondary"
          aria-expanded={panel === 'filters'}
          aria-controls={`${uid}-filters`}
          onClick={() => setPanel((p) => (p === 'filters' ? null : 'filters'))}
        >
          <Funnel size={17} weight="duotone" aria-hidden="true" />
          {t('filters')}
          {chips.length > 0 && (
            <span className="tag tag-accent nl-dt-count">
              <span aria-hidden="true">{chips.length}</span>
              <span className="nl-dt-sr">{t('activeFilters', { count: chips.length })}</span>
            </span>
          )}
        </button>
        <button
          ref={colsBtn}
          type="button"
          className="btn btn-secondary"
          aria-expanded={panel === 'cols'}
          aria-controls={`${uid}-cols`}
          onClick={() => setPanel((p) => (p === 'cols' ? null : 'cols'))}
        >
          <Columns size={17} weight="duotone" aria-hidden="true" />
          {t('columns')}
        </button>
        {canExport && (
          <button type="button" className="btn btn-secondary" onClick={() => openReport(selCount ? 'selected' : 'filtered')} disabled={!showBody || !hasData}>
            <FileArrowDown size={17} weight="duotone" aria-hidden="true" />
            {t('report')}
          </button>
        )}
        {readOnly && (
          <span className="tag tag-neutral nl-dt-viewonly" title={t('viewOnlyTitle')}>
            <LockSimple size={13} weight="duotone" aria-hidden="true" />
            {t('viewOnly', { role: roleName })}
          </span>
        )}
        {canCreate && (
          <button type="button" className="btn btn-primary nl-dt-create" onClick={openCreate}>
            <Plus size={16} aria-hidden="true" />
            {props.createLabel ?? t('create', { entity })}
          </button>
        )}
      </div>

      {panel === 'filters' && (
        <FiltersPanel
          id={`${uid}-filters`}
          t={t}
          onEscape={() => {
            setPanel(null);
            filtersBtn.current?.focus();
          }}
        >
          {cols
            .filter((c) => c.filter)
            .map((c) => {
              if (c.filter === 'range') {
                const rg = ranges[c.key] ?? {};
                return (
                  <div key={c.key} className="nl-dt-fgroup" role="group" aria-labelledby={`${uid}-f-${c.key}`}>
                    <span id={`${uid}-f-${c.key}`} className="nl-dt-flabel">
                      {c.label}
                    </span>
                    <div className="nl-dt-range">
                      <label className="nl-dt-hitwrap"><input className="input" type="number" inputMode="decimal" step="any" placeholder={t('min')} value={rg.min ?? ''} onChange={(e) => setRange(c.key, 'min', e.target.value)} aria-label={t('minAria', { label: c.label })} /></label>
                      <label className="nl-dt-hitwrap"><input className="input" type="number" inputMode="decimal" step="any" placeholder={t('max')} value={rg.max ?? ''} onChange={(e) => setRange(c.key, 'max', e.target.value)} aria-label={t('maxAria', { label: c.label })} /></label>
                    </div>
                  </div>
                );
              }
              const counts = facetCounts(c.key, table.getColumn(c.key)?.getFacetedRowModel().rows.map((r) => r.original) ?? []);
              const on = facets[c.key] ?? [];
              return (
                <div key={c.key} className="nl-dt-fgroup" role="group" aria-labelledby={`${uid}-f-${c.key}`}>
                  <span id={`${uid}-f-${c.key}`} className="nl-dt-flabel">
                    {c.label}
                  </span>
                  <div className="nl-dt-chips">
                    {facetOptions(c, rows).map((v) => (
                      <Chip key={v} on={on.includes(v)} onClick={() => toggleFacet(c.key, v)}>
                        {v}
                        <span className="nl-dt-chip-count">{counts.get(v) ?? 0}</span>
                      </Chip>
                    ))}
                  </div>
                </div>
              );
            })}
          {!cols.some((c) => c.filter) && <span className="nl-dt-muted">{t('noFilterCols')}</span>}
        </FiltersPanel>
      )}

      {panel === 'cols' && (
        <div
          id={`${uid}-cols`}
          className="nl-dt-panel nl-dt-colpanel"
          role="group"
          aria-labelledby={`${uid}-cols-label`}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              setPanel(null);
              colsBtn.current?.focus();
            }
          }}
        >
          <span id={`${uid}-cols-label`} className="nl-dt-flabel">
            {t('showColumns')}
          </span>
          {cols.map((c) => {
            const on = c.primary || !hiddenMap[c.key];
            return (
              <Chip
                key={c.key}
                on={on}
                aria-disabled={c.primary || undefined}
                title={c.primary ? t('columnLocked') : undefined}
                className={clsx(c.primary && 'is-locked')}
                onClick={() => !c.primary && setUserHidden({ ...hiddenMap, [c.key]: !hiddenMap[c.key] })}
              >
                {on ? <Check size={14} aria-hidden="true" /> : <Plus size={14} aria-hidden="true" />}
                {c.label}
              </Chip>
            );
          })}
          <button type="button" className="btn btn-ghost nl-dt-small-btn nl-dt-push" onClick={() => setUserHidden(null)}>
            {t('reset')}
          </button>
        </div>
      )}

      {chips.length > 0 && (
        <div className="nl-dt-chiprow">
          {chips.map((c) => (
            <button key={c.key} type="button" className="tag tag-accent nl-dt-active-chip" onClick={c.remove} aria-label={t('removeFilter', { label: c.label })}>
              {c.label}
              <X size={12} aria-hidden="true" />
            </button>
          ))}
          <button type="button" className="btn btn-ghost nl-dt-small-btn" onClick={clearAll}>
            {t('clearAll')}
          </button>
        </div>
      )}

      {selCount > 0 && showBody && (
        <div className="nl-dt-bulk" role="region" aria-label={t('selected', { count: selCount })}>
          <strong>{t('selected', { count: selCount })}</strong>
          {selInFiltered < filtered.length && (
            <button type="button" className="nl-dt-link" onClick={() => setSel(filtered.map(getRowId), true)}>
              {t('selectAllMatching', { count: filtered.length })}
            </button>
          )}
          <span className="nl-dt-spacer" />
          {canExport && (
            <button type="button" className="btn btn-highlight nl-dt-bulk-btn" onClick={() => openReport('selected')}>
              <FileArrowDown size={16} weight="duotone" aria-hidden="true" />
              {t('generateReport')}
            </button>
          )}
          {custom
            .filter((a) => a.bulk !== false)
            .map((a) => {
              const I = a.icon ?? Lightning;
              return (
                <button key={a.id} type="button" className="btn nl-dt-bulk-btn nl-dt-bulk-soft" disabled={pendingAction === a.id} aria-busy={pendingAction === a.id || undefined} onClick={() => void runAction(a, selRows)}>
                  <I size={16} weight="duotone" aria-hidden="true" />
                  {pendingAction === a.id ? t('working') : a.label}
                </button>
              );
            })}
          {canDelete && (
            <button type="button" className="btn nl-dt-bulk-btn nl-dt-bulk-danger" onClick={() => setDialog({ mode: 'delete', ids: selRows.map(getRowId) })}>
              <Trash size={16} weight="duotone" aria-hidden="true" />
              {t('delete')}
            </button>
          )}
          <button type="button" className="nl-dt-bulk-x" aria-label={t('clearSelection')} title={t('clearSelection')} onClick={() => setSelection({})}>
            <X size={16} aria-hidden="true" />
          </button>
        </div>
      )}

      <div id={`${uid}-body`} className="nl-dt-body" aria-busy={loading || undefined}>
        {loading && <Skeleton cardMode={cardMode} columns={shown} rows={Math.min(pageSize, 5)} label={t('loading', { plural })} actionsLabel={t('actions')} />}

        {!loading && error && (
          <div className="nl-dt-error" role="alert">
            <WarningCircle size={20} weight="duotone" aria-hidden="true" />
            <span className="nl-dt-error-text">
              {t('loadError', { plural })}
              {errMessage(error) && <span className="nl-dt-error-detail"> {errMessage(error)}</span>}
            </span>
            {onRetry && (
              <button type="button" className="btn btn-secondary" onClick={onRetry}>
                {t('retry')}
              </button>
            )}
          </div>
        )}

        {showBody && filtered.length > 0 && !cardMode && (
          <table className="table nl-dt-table" aria-label={tableLabel} aria-rowcount={filtered.length + 1}>
            <thead>
              <tr aria-rowindex={1}>
                <th scope="col" className="nl-dt-check-cell">
                  <DataTableCheckbox checked={allOn} indeterminate={someOn} onChange={toggleAll} label={t('selectAllRows')} />
                </th>
                {shown.map((c) => {
                  const { cur, rank } = sortState(c.key);
                  const SortIcon = cur ? (cur.desc ? CaretDown : CaretUp) : ArrowsDownUp;
                  return (
                    <th key={c.key} scope="col" className={clsx(c.isNum && 'is-num')} aria-sort={cur ? (cur.desc ? 'descending' : 'ascending') : 'none'}>
                      <button type="button" className="nl-dt-sort" title={t('sortHint')} onClick={(e) => onSort(c.key, e.shiftKey)}>
                        {c.label}
                        <SortIcon size={12} className={clsx('nl-dt-sort-icon', cur && 'is-on')} aria-hidden="true" />
                        {rank > 0 && (
                          <span className="nl-dt-rank">
                            <span className="nl-dt-sr">{t('sortRank', { rank })}</span>
                            <span aria-hidden="true">{rank}</span>
                          </span>
                        )}
                      </button>
                    </th>
                  );
                })}
                <th scope="col" className="nl-dt-actions-head">
                  {t('actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {pageRows.map((r, i) => {
                const id = getRowId(r);
                const on = !!selection[id];
                const name = primary ? cellText(primary, r, locale) : id;
                return (
                  <tr
                    key={id}
                    aria-rowindex={page * pageSize + i + 2}
                    aria-selected={on}
                    className={clsx(on && 'is-selected', onOpen && 'is-clickable')}
                    onClick={onOpen ? (e: MouseEvent) => !isInteractive(e.target) && onOpen(r) : undefined}
                  >
                    <td className="nl-dt-check-cell">
                      <DataTableCheckbox checked={on} onChange={(v) => setSel([id], v)} label={t('selectRow', { name })} />
                    </td>
                    {shown.map((c) => (
                      <td key={c.key} className={clsx(c.isNum && 'is-num')}>
                        {renderValue(c, r, 'cell')}
                      </td>
                    ))}
                    <td className="nl-dt-actions-cell">
                      <RowActions acts={rowActs(r)} name={name} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}

        {showBody && filtered.length > 0 && cardMode && (
          <div className="nl-dt-cardmode">
            <div className="nl-dt-cardbar">
              <DataTableCheckbox checked={allOn} indeterminate={someOn} onChange={toggleAll} label={t('selectAllRows')}>
                {t('selectAll')}
              </DataTableCheckbox>
              <span className="nl-dt-push" id={`${uid}-sortby`}>
                {t('sort')}
              </span>
              <div className="nl-dt-chips" role="group" aria-labelledby={`${uid}-sortby`}>
                {shown.map((c) => {
                  const { cur } = sortState(c.key);
                  const SortIcon = cur ? (cur.desc ? CaretDown : CaretUp) : ArrowsDownUp;
                  return (
                    <Chip key={c.key} size="sm" on={!!cur} onClick={(e) => onSort(c.key, e.shiftKey)} title={t('sortHint')}>
                      {c.label}
                      <SortIcon size={12} aria-hidden="true" />
                    </Chip>
                  );
                })}
              </div>
            </div>
            <ul className="nl-dt-cards" aria-label={tableLabel}>
              {pageRows.map((r) => {
                const id = getRowId(r);
                const on = !!selection[id];
                const name = primary ? cellText(primary, r, locale) : id;
                const pSub = primary ? subText(primary, r) : '';
                const tagV = firstTag ? valueOf(r, firstTag.key) : null;
                return (
                  <li
                    key={id}
                    className={clsx('nl-dt-card', on && 'is-selected', onOpen && 'is-clickable')}
                    onClick={onOpen ? (e) => !isInteractive(e.target) && onOpen(r) : undefined}
                  >
                    <div className="nl-dt-card-head">
                      <DataTableCheckbox className="nl-dt-card-check" checked={on} onChange={(v) => setSel([id], v)} label={t('selectRow', { name })} />
                      <div className="nl-dt-card-title">
                        <div className="nl-dt-card-name">{name}</div>
                        {pSub && <div className="nl-dt-sub">{pSub}</div>}
                      </div>
                      {firstTag && !isBlank(tagV) && <span className={clsx('tag nl-dt-tag-wrap', toneOf(firstTag, r))}>{cellText(firstTag, r, locale)}</span>}
                    </div>
                    <dl className="nl-dt-card-fields">
                      {visible
                        .filter((c) => !c.primary && c !== firstTag)
                        .map((c) => (
                          <div key={c.key} className="nl-dt-card-field">
                            <dt>{c.label}</dt>
                            <dd>{renderValue(c, r, 'field')}</dd>
                          </div>
                        ))}
                    </dl>
                    <div className="nl-dt-card-acts">
                      <RowActions acts={rowActs(r)} name={name} card />
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
        )}

        {showBody && filtered.length === 0 && (
          <div className="nl-dt-empty">
            <MagnifyingGlassMinus size={32} weight="duotone" aria-hidden="true" />
            <p>{hasData ? t('emptyFiltered', { plural }) : (props.emptyText ?? t('emptyNone', { plural }))}</p>
            {hasData ? (
              <button type="button" className="btn btn-secondary" onClick={clearAll}>
                {t('clearSearchFilters')}
              </button>
            ) : (
              canCreate && (
                <button type="button" className="btn btn-primary" onClick={openCreate}>
                  <Plus size={16} aria-hidden="true" />
                  {props.createLabel ?? t('create', { entity })}
                </button>
              )
            )}
          </div>
        )}
      </div>

      <nav className="nl-dt-foot" aria-label={`${t('pagination')} · ${tableLabel}`}>
        <span>
          {loading ? ' ' : range}
          {!cardMode && showBody && layout.autoHidden.length > 0 && ` · ${t('autoHidden', { cols: layout.autoHidden.map((c) => c.label).join(', ') })}`}
        </span>
        <div className="nl-dt-pager">
          <span className="nl-dt-rows-label" aria-hidden="true">
            {t('rows')}
          </span>
          <div className="nl-dt-chips" role="group" aria-label={t('rowsPerPage')}>
            {PAGE_SIZES.map((n) => (
              <Chip key={n} size="sm" on={n === pageSize} onClick={() => setPagination({ pageIndex: 0, pageSize: n })}>
                {n}
              </Chip>
            ))}
          </div>
          <button type="button" className="btn btn-ghost btn-icon nl-dt-page-btn" aria-label={t('prevPage')} disabled={page === 0} onClick={() => setPagination((p) => ({ ...p, pageIndex: Math.max(0, page - 1) }))}>
            <CaretLeft size={16} aria-hidden="true" />
          </button>
          <span>
            <span aria-hidden="true">{t('page', { page: page + 1, count: pageCount })}</span>
            <span className="nl-dt-sr">{t('pageAria', { page: page + 1, count: pageCount })}</span>
          </span>
          <button type="button" className="btn btn-ghost btn-icon nl-dt-page-btn" aria-label={t('nextPage')} disabled={page >= pageCount - 1} onClick={() => setPagination((p) => ({ ...p, pageIndex: Math.min(pageCount - 1, page + 1) }))}>
            <CaretRight size={16} aria-hidden="true" />
          </button>
        </div>
      </nav>

      {/* ── dialogs ── */}
      {dialog?.mode === 'view' && dRow && (
        <DataTableDialog title={primary ? cellText(primary, dRow, locale) : capitalize(entity)} onClose={closeDialog} wide>
          <dl className="nl-dt-view-grid">
            {cols.map((c) => (
              <div key={c.key} className="nl-dt-view-field">
                <dt>{c.label}</dt>
                <dd>{renderValue(c, dRow, 'field')}</dd>
              </div>
            ))}
          </dl>
          <div className="dialog-actions nl-dt-dialog-actions">
            {canDelete && (
              <button type="button" className="btn btn-ghost nl-dt-danger-ghost" onClick={() => setDialog({ mode: 'delete', ids: [dialog.id] })}>
                <Trash weight="duotone" aria-hidden="true" />
                {t('delete')}
              </button>
            )}
            <button type="button" className="btn btn-secondary" onClick={closeDialog}>
              {t('close')}
            </button>
            {canUpdate && (
              <button type="button" className="btn btn-primary" onClick={() => openEdit(dRow)}>
                <PencilSimple weight="duotone" aria-hidden="true" />
                {t('edit')}
              </button>
            )}
          </div>
        </DataTableDialog>
      )}

      {(dialog?.mode === 'create' || (dialog?.mode === 'edit' && dRow)) && (
        <DataTableDialog title={dialog.mode === 'create' ? t('createTitle', { entity }) : t('editTitle', { entity })} onClose={closeDialog} busy={busy} wide>
          <RecordForm
            key={dialog.mode === 'edit' ? dialog.id : 'create'}
            fields={fieldSpecs}
            initial={initialValues(fieldSpecs, dialog.mode === 'edit' ? (dRow ?? null) : null)}
            submitLabel={dialog.mode === 'create' ? t('createSave', { entity }) : t('saveChanges')}
            t={t}
            onCancel={closeDialog}
            onBusyChange={setBusy}
            onSubmit={async (values) => {
              if (dialog.mode === 'create') {
                await props.onCreate?.(values as Partial<T>);
                firstPage();
                flash(t('toastCreated', { entity: capitalize(entity) }));
              } else if (dRow) {
                await props.onUpdate?.(dRow, values as Partial<T>);
                flash(t('toastSaved'));
              }
              closeDialog();
            }}
          />
        </DataTableDialog>
      )}

      {dialog?.mode === 'delete' && (
        <DeleteDialog
          t={t}
          title={dialog.ids.length === 1 ? t('deleteTitleOne', { entity }) : t('deleteTitleMany', { count: dialog.ids.length, plural })}
          text={t('deleteText', { count: dialog.ids.length, entity, plural })}
          onClose={closeDialog}
          onConfirm={async () => {
            const targets = dialog.ids.map((id) => rowById.get(id)).filter((r): r is T => !!r);
            await props.onDelete?.(targets);
            setSel(dialog.ids, false);
            flash(t('toastDeleted', { count: targets.length, noun: noun(targets.length) }));
            closeDialog();
          }}
        />
      )}

      {dialog?.mode === 'report' && (
        <ReportDialog
          t={t}
          title={t('reportTitle', { plural })}
          dialog={dialog}
          setDialog={setDialog}
          cols={cols}
          counts={{ selected: selCount, filtered: filtered.length, all: rows.length }}
          filterNote={chips.map((c) => c.label).join(', ')}
          onClose={closeDialog}
          onGenerate={() => {
            const data = dialog.scope === 'selected' ? selRows : dialog.scope === 'filtered' ? filtered : [...rows];
            const repCols = cols.filter((c) => dialog.cols[c.key]);
            const matrix = buildReport(data, repCols, locale, detailLabel);
            const stem = props.reportName ?? plural;
            if (dialog.format === 'csv') downloadBlob(toCsv(matrix), REPORT_MIME.csv, reportFileName(stem, 'csv'));
            else if (dialog.format === 'xlsx') downloadBlob(toXlsx(matrix, capitalize(plural)), REPORT_MIME.xlsx, reportFileName(stem, 'xlsx'));
            else {
              const ok = openPrintWindow(
                toPrintHtml(matrix, {
                  title: t('reportDocTitle', { plural: capitalize(plural) }),
                  heading: t('reportHeading', { plural: capitalize(plural), count: data.length, date: reportDate() }),
                  note: dialog.scope === 'filtered' && chips.length ? chips.map((c) => c.label).join(' · ') : undefined,
                  lang: locale === 'fr' ? 'fr-CA' : 'en-CA',
                  cssVars: readThemeVars(boxRef.current),
                  autoPrint: true,
                }),
              );
              if (!ok) {
                flash(t('popupBlocked'), 'error');
                return;
              }
            }
            closeDialog();
            flash(t('toastReport', { count: data.length }));
          }}
        />
      )}

      <div className="nl-dt-toast-region" role="status" aria-live="polite">
        {toast && (
          <div key={toast.n} className={clsx('nl-dt-toast', toast.tone === 'error' && 'is-error')}>
            {toast.tone === 'error' ? <WarningCircle size={18} weight="duotone" aria-hidden="true" /> : <CheckCircle size={18} weight="duotone" aria-hidden="true" />}
            {toast.text}
          </div>
        )}
      </div>
    </div>
  );
}

// ── pieces ──────────────────────────────────────────────────────────────────

function FiltersPanel({ id, t, children, onEscape }: { id: string; t: DataTableT; children: ReactNode; onEscape: () => void }) {
  return (
    <div id={id} className="nl-dt-panel nl-dt-filters" role="group" aria-label={t('filtersRegion')} onKeyDown={(e) => e.key === 'Escape' && onEscape()}>
      {children}
    </div>
  );
}

function RowActions({ acts, name, card }: { acts: RowAct[]; name: string; card?: boolean }) {
  return (
    <div className={clsx('nl-dt-row-acts', card && 'is-card')}>
      {acts.map((a) => {
        const I = a.icon;
        const labelled = card || a.showLabel;
        return (
          <button
            key={a.key}
            type="button"
            className={clsx('btn btn-ghost nl-dt-act', labelled && 'is-labelled', a.danger && 'is-danger')}
            aria-label={labelled ? undefined : `${a.label} · ${name}`}
            title={labelled ? undefined : a.label}
            disabled={a.busy}
            aria-busy={a.busy || undefined}
            onClick={(e) => {
              e.stopPropagation();
              a.run();
            }}
          >
            <I size={card ? 17 : 18} weight="duotone" aria-hidden="true" />
            {labelled && (
              <>
                {a.label}
                {!card && <span className="nl-dt-sr"> · {name}</span>}
              </>
            )}
          </button>
        );
      })}
    </div>
  );
}

function Skeleton<T>({ cardMode, columns, rows, label, actionsLabel }: { cardMode: boolean; columns: NormalizedColumn<T>[]; rows: number; label: string; actionsLabel: string }) {
  const bars = Array.from({ length: rows }, (_, i) => i);
  return (
    <>
      <span className="nl-dt-sr" role="status">
        {label}
      </span>
      {cardMode ? (
        <div className="nl-dt-cards" aria-hidden="true">
          {bars.slice(0, 3).map((i) => (
            <div key={i} className="nl-dt-card">
              <div className="nl-dt-card-head">
                <span className="nl-dt-sk nl-dt-sk-box" />
                <span className="nl-dt-sk" style={{ width: `${55 + ((i * 17) % 30)}%` }} />
              </div>
              <div className="nl-dt-card-fields">
                {columns.slice(1, 5).map((c) => (
                  <span key={c.key} className="nl-dt-sk" />
                ))}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <table className="table nl-dt-table" aria-hidden="true">
          <thead>
            <tr>
              <th className="nl-dt-check-cell">
                <span className="nl-dt-sk nl-dt-sk-box" />
              </th>
              {columns.map((c) => (
                <th key={c.key} className={clsx(c.isNum && 'is-num')}>
                  <span className="nl-dt-th-text">{c.label}</span>
                </th>
              ))}
              <th className="nl-dt-actions-head">{actionsLabel}</th>
            </tr>
          </thead>
          <tbody>
            {bars.map((i) => (
              <tr key={i}>
                <td className="nl-dt-check-cell">
                  <span className="nl-dt-sk nl-dt-sk-box" />
                </td>
                {columns.map((c, ci) => (
                  <td key={c.key} className={clsx(c.isNum && 'is-num')}>
                    <span className={clsx('nl-dt-sk', c.type === 'tag' && 'nl-dt-sk-pill')} style={{ width: c.isNum ? '3.5em' : c.type === 'tag' ? '5em' : `${60 + (((i + 1) * (ci + 3) * 13) % 35)}%` }} />
                  </td>
                ))}
                <td className="nl-dt-actions-cell">
                  <span className="nl-dt-sk nl-dt-sk-box nl-dt-push" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function DeleteDialog({ t, title, text, onClose, onConfirm }: { t: DataTableT; title: string; text: string; onClose: () => void; onConfirm: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  return (
    <DataTableDialog title={title} onClose={onClose} busy={busy}>
      <p className="dialog-body">{text}</p>
      {err && (
        <div className="nl-dt-inline-error" role="alert">
          <WarningCircle size={16} weight="duotone" aria-hidden="true" />
          {err}
        </div>
      )}
      <div className="dialog-actions nl-dt-dialog-actions">
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={busy}>
          {t('cancel')}
        </button>
        <button
          type="button"
          className="btn nl-dt-danger"
          disabled={busy}
          aria-busy={busy || undefined}
          onClick={async () => {
            setBusy(true);
            setErr('');
            try {
              await onConfirm();
            } catch (e) {
              setErr(errMessage(e) || t('genericError'));
              setBusy(false);
            }
          }}
        >
          <Trash weight="duotone" aria-hidden="true" />
          {busy ? t('deleting') : t('delete')}
        </button>
      </div>
    </DataTableDialog>
  );
}

type ReportDialogState = Extract<Dialog, { mode: 'report' }>;

function ReportDialog<T>({
  t,
  title,
  dialog,
  setDialog,
  cols,
  counts,
  filterNote,
  onClose,
  onGenerate,
}: {
  t: DataTableT;
  title: string;
  dialog: ReportDialogState;
  setDialog: (d: Dialog) => void;
  cols: NormalizedColumn<T>[];
  counts: Record<ReportScope, number>;
  filterNote: string;
  onClose: () => void;
  onGenerate: () => void;
}) {
  const uid = useId();
  const nCols = cols.filter((c) => dialog.cols[c.key]).length;
  const nRows = counts[dialog.scope];
  const formats: { value: ReportFormat; label: ReactNode }[] = [
    { value: 'csv', label: <><FileCsv size={15} weight="duotone" aria-hidden="true" />{t('formatCsv')}</> },
    { value: 'xlsx', label: <><MicrosoftExcelLogo size={15} weight="duotone" aria-hidden="true" />{t('formatXlsx')}</> },
    { value: 'pdf', label: <><FilePdf size={15} weight="duotone" aria-hidden="true" />{t('formatPdf')}</> },
  ];
  return (
    <DataTableDialog title={title} onClose={onClose} wide>
      <div className="nl-dt-rep-group">
        <span className="nl-dt-rep-label" id={`${uid}-rows`}>{t('reportRows')}</span>
        <ChipRadioGroup
          label={t('reportRows')}
          value={dialog.scope}
          onChange={(scope) => setDialog({ ...dialog, scope })}
          options={[
            { value: 'selected', label: t('scopeSelected', { count: counts.selected }), disabled: !counts.selected },
            { value: 'filtered', label: t('scopeFiltered', { count: counts.filtered }) },
            { value: 'all', label: t('scopeAll', { count: counts.all }) },
          ]}
        />
      </div>
      <div className="nl-dt-rep-group">
        <span className="nl-dt-rep-label">{t('reportFormat')}</span>
        <ChipRadioGroup label={t('reportFormat')} value={dialog.format} onChange={(format) => setDialog({ ...dialog, format })} options={formats} />
      </div>
      <div className="nl-dt-rep-group" role="group" aria-labelledby={`${uid}-cols`}>
        <span className="nl-dt-rep-label" id={`${uid}-cols`}>{t('reportColumns')}</span>
        <div className="nl-dt-chips">
          {cols.map((c) => {
            const on = !!dialog.cols[c.key];
            return (
              <Chip key={c.key} on={on} onClick={() => setDialog({ ...dialog, cols: { ...dialog.cols, [c.key]: !on } })}>
                {on ? <Check size={13} aria-hidden="true" /> : <Plus size={13} aria-hidden="true" />}
                {c.label}
              </Chip>
            );
          })}
        </div>
      </div>
      <div className="nl-dt-muted" aria-live="polite">
        {t('reportSummary', { rows: nRows, cols: nCols })}
        {dialog.scope === 'filtered' && filterNote ? t('reportSummaryFilters', { list: filterNote }) : ''}
      </div>
      <div className="dialog-actions nl-dt-dialog-actions">
        <button type="button" className="btn btn-secondary" onClick={onClose}>
          {t('cancel')}
        </button>
        <button type="button" className="btn btn-primary" disabled={!nRows || !nCols} onClick={onGenerate}>
          <FileArrowDown weight="duotone" aria-hidden="true" />
          {t('generateReport')}
        </button>
      </div>
    </DataTableDialog>
  );
}
