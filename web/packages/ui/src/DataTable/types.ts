import type { Icon } from '@phosphor-icons/react';
import type { StatusTone } from '../Commerce';

/** Column value kinds. `money` values are CAD cents (numbers) per the platform convention. */
export type DataTableColumnType = 'text' | 'num' | 'money' | 'tag';

/** Tag tone classes from the component layer (`base.css`). */
export type DataTableTone = 'tag-accent' | 'tag-accent-2' | 'tag-neutral' | 'tag-highlight' | 'tag-success' | 'tag-warning' | 'tag-danger' | 'tag-info';

export type DataTablePerm = 'create' | 'update' | 'delete';

export interface DataTablePermissions {
  create?: boolean;
  update?: boolean;
  delete?: boolean;
  /** Defaults to true. */
  export?: boolean;
}

/** String keys of a row type. */
export type RowKey<T> = Extract<keyof T, string>;

export interface DataTableColumn<T> {
  key: RowKey<T>;
  label: string;
  /** Default `text`. */
  type?: DataTableColumnType;
  /** A secondary field shown under the value (e.g. SKU under the name). Searched, editable and exported with the column. */
  sub?: RowKey<T>;
  /** A picture's URL shown before the value, in the table and on the phone card (a product's photo). An empty value
   * shows the value's initial on linen instead. Not searched or exported. */
  thumb?: RowKey<T>;
  /** Label for `sub` in forms and reports. Default "<label> · detail". */
  subLabel?: string;
  /** Default: `facet` for tag columns, `range` for num/money, off otherwise. */
  filter?: 'facet' | 'range' | false;
  /** Allowed values (form choices + facet order). Tag columns fall back to the distinct values in the data. */
  options?: readonly string[];
  /** Tone per value for tag cells. Falls back to `rowTones`, then `tag-neutral`. */
  tones?: Readonly<Record<string, DataTableTone>>;
  /** Shown in the create/edit dialog. Default true. */
  editable?: boolean;
  required?: boolean;
  /** The row's title column: bold, never hidden, card heading, view-dialog title. Default: the first column. */
  primary?: boolean;
  /** Lower priority columns auto-hide first when the container is narrow. Default: later columns hide first. */
  priority?: number;
  /** Hidden initially (the viewer can show it from the Columns panel). */
  hidden?: boolean;
  /** Custom display text (also used for search and reports). */
  format?: (value: unknown, row: T) => string;
  /**
   * S-175: the cell is a status (To pack, Delivered, Wrong item): drawn as a `StatusTag` of this tone — the semantic
   * colour pair with its dot, never the action or commerce colour. Facets and reports still read the text.
   */
  status?: (row: T) => StatusTone;
  /**
   * S-175: where the value goes on a compact phone card (design/revamp/a.html, the orders table at 390): `line` joins
   * the body line ("A. Osei · Country sourdough ×2"), `meta` the muted line under it ("Tonight 6–9 p.m."), `end` sits
   * at the meta line's end in bold (the total), `tag` is the tag at the top line's end (default: the first tag or status
   * column). When any column sets it the cards are compact: the row's picture, the primary value and the tag on the top
   * line, only these columns below, then the row's actions; the others stay in the view dialog and the table.
   */
  card?: 'line' | 'meta' | 'end' | 'tag';
  /** S-175: the cell never wraps (an order number, a time): the table drops a column before it breaks one. */
  nowrap?: boolean;
  /** S-175: the value's words on a compact card when the bare value would be unclear there ("You get $75.44"). */
  cardFormat?: (value: unknown, row: T) => string;
}

/** S-175: a status tab above the table ("To pack 3"): the rows it keeps, with their count. */
export interface DataTableView<T> {
  id: string;
  label: string;
  /** Rows in this view. Omit for "All". */
  when?: (row: T) => boolean;
  /** `danger` keeps the count red (Issues 1). */
  tone?: 'danger';
  /** Hidden while it would count 0 (a Cancelled tab with nothing cancelled). */
  hideEmpty?: boolean;
}

/** S-175: a quick filter chip in the toolbar ("Today", "Tonight's run"): applied chips combine (and). */
export interface DataTableQuickFilter<T> {
  id: string;
  label: string;
  when: (row: T) => boolean;
}

/** Guard: the action only applies to rows whose `key` value (stringified) is one of `in`. */
export interface DataTableWhen<T> {
  key: RowKey<T>;
  in: readonly string[];
}

export interface DataTableAction<T> {
  id: string;
  label: string;
  icon?: Icon;
  /** Permission required to see the action at all. */
  perm?: DataTablePerm;
  when?: DataTableWhen<T>;
  /** Render as a labelled button in each applicable row. */
  inline?: boolean;
  /** Offer in the bulk bar when rows are selected. Default true. */
  bulk?: boolean;
  /** A destructive action (Suspend): drawn in the danger colour, red in the bulk bar (E-15: never the action colour). */
  danger?: boolean;
  /**
   * S-175: an inline action drawn as a small button instead of a quiet icon-and-label: `primary` for the row's next job
   * (Mark packed: the action colour, here and in the bulk bar), `secondary` for a lesser one (Print label).
   */
  emphasis?: 'primary' | 'secondary';
}

export interface DataTableProps<T extends object> {
  /** Singular noun, already localised by the caller ("order" / "commande"). */
  entity: string;
  /** Plural noun. Default: English pluralisation of `entity`. */
  plural?: string;
  columns: readonly DataTableColumn<T>[];
  rows: readonly T[];
  /** Stable row id. Default `row.id`. */
  getRowId?: (row: T) => string;
  /** Per-row tag tones keyed by column key (the design's `_cls` map). */
  rowTones?: (row: T) => Partial<Record<RowKey<T>, DataTableTone>> | undefined;

  can?: DataTablePermissions;
  /** Localised role name. With no create/update/delete permission, shows "View only · <role>". */
  roleName?: string;
  actions?: readonly DataTableAction<T>[];

  /** S-175: status tabs with counts above the toolbar (the orders table). The first is the default unless `view` says. */
  views?: readonly DataTableView<T>[];
  /** The current tab (controlled); with `onViewChange` the caller keeps it (a route's search param). */
  view?: string;
  onViewChange?: (id: string) => void;
  /** S-175: quick filter chips in the toolbar, before Filters. */
  quickFilters?: readonly DataTableQuickFilter<T>[];
  /** Quick filters applied on arrival. */
  initialQuickFilters?: readonly string[];

  /** Initial page size (5 | 10 | 25 | 50). Default 10. */
  pageSize?: number;
  searchPlaceholder?: string;
  /** The search box's text on arrival (the Studio's global search opens a screen with `?q=`). */
  initialSearch?: string;
  createLabel?: string;
  emptyText?: string;
  /** File name stem for reports. Default: `plural`. */
  reportName?: string;
  /** Label for the row open action when `onOpen` is set. Default "Open". */
  openLabel?: string;

  loading?: boolean;
  /** A load error. Shows the rosehip inline error with Retry (when `onRetry` is set). */
  error?: string | Error | null;
  onRetry?: () => void;

  /** Row click and the "Open" row action (replaces the built-in View dialog). */
  onOpen?: (row: T) => void;
  /** Take over the create flow (e.g. navigate to a full page) instead of the built-in dialog. */
  onCreateClick?: () => void;
  /** Take over the edit flow instead of the built-in dialog. */
  onEditClick?: (row: T) => void;

  /** Persist a new record. Reject (throw) to keep the dialog open with the error message. */
  onCreate?: (values: Partial<T>) => Promise<unknown> | void;
  onUpdate?: (row: T, values: Partial<T>) => Promise<unknown> | void;
  /** Called after the viewer confirms; the caller writes the audit log entry. */
  onDelete?: (rows: T[]) => Promise<unknown> | void;
  /**
   * Custom row / bulk action. `rows` already excludes rows the `when` guard rejects. Resolve to `false` when the
   * action continues elsewhere (a dialog of the caller's) and no "done" toast should show.
   */
  onAction?: (action: DataTableAction<T>, rows: T[]) => Promise<unknown> | unknown;

  /** Accessible name for the table. Default: capitalised plural. */
  'aria-label'?: string;
  className?: string;
}

export type ReportScope = 'selected' | 'filtered' | 'all';
export type ReportFormat = 'csv' | 'xlsx' | 'pdf';
