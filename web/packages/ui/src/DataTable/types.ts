import type { Icon } from '@phosphor-icons/react';

/** Column value kinds. `money` values are CAD cents (numbers) per the platform convention. */
export type DataTableColumnType = 'text' | 'num' | 'money' | 'tag';

/** Tag tone classes from the component layer (`base.css`). */
export type DataTableTone = 'tag-accent' | 'tag-accent-2' | 'tag-neutral' | 'tag-highlight';

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

  /** Initial page size (5 | 10 | 25 | 50). Default 10. */
  pageSize?: number;
  searchPlaceholder?: string;
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
