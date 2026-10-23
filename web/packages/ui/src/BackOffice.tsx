import { useId, useState, type ReactNode } from 'react';
import { ArrowRight, type Icon } from '@phosphor-icons/react';
import clsx from 'clsx';
import type { StatusTone } from './Commerce';
import { Button, type ButtonVariant } from './Button';
import { tablistKeyDown } from './Choice';
import { defineMessages } from './i18n';

const useT = defineMessages({
  en: { danger: 'Urgent', warning: 'Due soon' },
  fr: { danger: 'Urgent', warning: 'À faire bientôt' },
});

/*
 * Back-office building blocks of the redesign (docs/design/redesign-spec.md § 1.3, AfroTransact BO:263–280, BO:811,
 * PH:53), shared by the Studio and the console: the page head, the KPI strip, chart and to-do cards, and the right
 * sheet's sections and facts. Styles in styles/backoffice.css (semantic tokens only).
 */

export type Trend = 'up' | 'down' | 'flat';
export interface KpiItem {
  key: string;
  label: ReactNode;
  value: ReactNode;
  /** The xs line under the value ("+12% vs Sep"); `trend` colours it green / red. */
  delta?: ReactNode;
  trend?: Trend;
  /** The delta is a change over time ("+12% vs last Friday"): ▲ / ▼ before it, as well as the colour. Off for a
   *  delta whose colour means good / bad rather than up / down. */
  arrow?: boolean;
  /** Makes the card a button that opens the screen behind the figure (PH:55). */
  onClick?: () => void;
}

/**
 * KPI strip (BO:266): bordered cards — label, 3xl value, delta. The columns follow the number of cards and the width
 * the strip has (a container query), so a row never ends with one card alone: 6 → 6 / 3+3 / 2+2+2, 5 → 5 / 3+2 (wider) /
 * 2+2+1 (the last full width), 4 → 4 / 2+2, 3 → 3 / 2+1. Phones get two compact columns.
 */
export function KpiStrip({ items, label }: { items: readonly KpiItem[]; label: string }) {
  return (
    <div className="bo-kpis-wrap"><ul className="bo-kpis" aria-label={label} data-count={Math.min(items.length, 8)}>
      {items.map(k => {
        const body = <>
          <span className="bo-kpi-label">{k.label}</span>
          <span className="bo-kpi-value">{k.value}</span>
          {k.delta ? <span className="bo-kpi-delta" data-trend={k.trend ?? 'flat'}>{k.arrow && k.trend === 'up' ? <span aria-hidden>▲ </span> : k.arrow && k.trend === 'down' ? <span aria-hidden>▼ </span> : null}{k.delta}</span> : null}
        </>;
        return <li key={k.key}>{k.onClick ? <button type="button" className="bo-kpi" onClick={k.onClick}>{body}</button> : <div className="bo-kpi">{body}</div>}</li>;
      })}
    </ul></div>
  );
}

/** A chart or list in a bordered card with its `md` title (DashCharts `Card`). */
export function ChartCard({ title, id, actions, children, className }: { title: ReactNode; id: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={clsx('bo-card', className)} aria-labelledby={id}>
      <div className="bo-card-head"><h2 className="bo-card-title" id={id}>{title}</h2>{actions}</div>
      {children}
    </section>
  );
}

/** One to-do row; without `onClick` (a screen the viewer can't open) it shows the count without the way in. */
export interface TodoItem { key: string; icon: Icon; text: ReactNode; meta?: ReactNode; onClick?: () => void }
/** A "To do" card (BO:273): bold title, then rows of icon · text · count → each opens its screen. */
export function TodoCard({ title, id, items, empty }: { title: ReactNode; id: string; items: readonly TodoItem[]; empty: ReactNode }) {
  return (
    <section className="bo-card" aria-labelledby={id}>
      <h2 className="bo-card-title" id={id}>{title}</h2>
      {items.length === 0 ? <p className="bo-muted">{empty}</p> : (
        <ul className="bo-todo">
          {items.map(({ key, icon: I, text, meta, onClick }) => {
            const body = <>
              <I size={20} aria-hidden className="bo-todo-icon" />
              <span className="bo-todo-text">{text}</span>
              {meta ? <span className="bo-todo-meta">{meta}</span> : null}
            </>;
            return (
              <li key={key}>
                {onClick
                  ? <button type="button" className="bo-todo-row" onClick={onClick}>{body}<ArrowRight size={16} aria-hidden className="bo-todo-arrow" /></button>
                  : <div className="bo-todo-row" data-static="">{body}</div>}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** A titled block inside the right sheet (BO:827): `md` bold title, then its content. */
export function SheetSection({ title, children }: { title: ReactNode; children: ReactNode }) {
  return <section className="bo-sheet-section"><h3 className="bo-sheet-h3">{title}</h3>{children}</section>;
}

/** Facts as a two-column definition list (BO:830): `minmax(120px, 38%) | 1fr`, bordered, `sm` text. */
export function FactList({ rows }: { rows: readonly (readonly [ReactNode, ReactNode] | null | false | undefined)[] }) {
  return (
    <dl className="bo-facts">
      {rows.filter((r): r is readonly [ReactNode, ReactNode] => !!r).map(([k, v], i) => <div key={i} className="bo-fact"><dt>{k}</dt><dd>{v}</dd></div>)}
    </dl>
  );
}

/** The back-office page head (PH:41): an optional kicker, H1 2xl + a muted sub line, actions on the right. */
export function BoHeader({ title, sub, actions, kicker }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode; kicker?: ReactNode }) {
  return (
    <div className="bo-head">
      <div className="bo-head-text">{kicker ? <p className="bo-kicker">{kicker}</p> : null}<h1 className="bo-title">{title}</h1>{sub ? <p className="bo-sub">{sub}</p> : null}</div>
      {actions ? <div className="bo-head-actions">{actions}</div> : null}
    </div>
  );
}

/** What a "Needs you now" card asks for: one button, a link (`href`) or an action (`onClick`). */
export interface ActionCardAction { label: string; href?: string; onClick?: () => void }
export interface ActionCardProps {
  /** Urgency: danger (now), warning (today), info / success / neutral (to know). Colours the card's edge and icon. */
  tone: StatusTone;
  icon: Icon;
  /** The job ("3 orders to pack"). */
  title: ReactNode;
  /** The deadline or the detail ("Pack by 5:20 p.m. for run R-611"). */
  detail?: ReactNode;
  action: ActionCardAction;
  /** The most urgent card's button is the page's primary action; the others are secondary. */
  primary?: boolean;
}

/**
 * One "Needs you now" card (design/revamp/a.html `act`): an icon tile, the job in bold, the deadline under it and the one
 * button that does it, with a 3 px edge in the urgency's status colour. Urgent and due-soon cards also say so to
 * screen readers (the edge is colour only).
 */
export function ActionCard({ tone, icon: I, title, detail, action, primary }: ActionCardProps) {
  const t = useT();
  const id = useId();
  const cls = clsx('btn', 'btn-sm', primary ? 'btn-primary' : 'btn-secondary', 'bo-act-btn');
  return (
    <li className="bo-act" data-tone={tone}>
      <span className="bo-act-icon" aria-hidden><I size={18} /></span>
      <div className="bo-act-body">
        <h3 className="bo-act-title" id={id}>{tone === 'danger' || tone === 'warning' ? <span className="nl-sr-only">{t(tone)}: </span> : null}{title}</h3>
        {detail ? <p className="bo-act-detail">{detail}</p> : null}
      </div>
      {action.href
        ? <a className={cls} href={action.href} aria-describedby={id} onClick={action.onClick ? e => { if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return; e.preventDefault(); action.onClick!(); } : undefined}>{action.label}</a>
        : <button type="button" className={cls} aria-describedby={id} onClick={action.onClick}>{action.label}</button>}
    </li>
  );
}

/** The "Needs you now" grid: a section title with its count, then the cards three across (one column on phones). */
export function ActionGrid({ title, children, count }: { title: ReactNode; count: number; children: ReactNode }) {
  const id = useId();
  return (
    <section className="bo-acts-wrap" aria-labelledby={id}>
      <h2 className="bo-sec-title" id={id}>{title}<span className="bo-count">{count}</span></h2>
      <ul className="bo-acts">{children}</ul>
    </section>
  );
}

export interface SummaryColumn<T> { key: string; label: ReactNode; align?: 'start' | 'end'; render: (row: T) => ReactNode; /** Left out on phones (< 600px) to give the first column room. */ hideOnPhone?: boolean }
/**
 * A short read-only table in a panel (design/revamp/a.html `tbl`: "Top products today"): grey head, hairline rows,
 * figures right-aligned in tabular numbers. For lists of a handful of rows; long, searchable lists are `DataTable`.
 */
export function SummaryTable<T>({ caption, columns, rows, rowKey, empty }: { caption: string; columns: readonly SummaryColumn<T>[]; rows: readonly T[]; rowKey: (row: T) => string; empty?: ReactNode }) {
  if (rows.length === 0) return <p className="bo-muted">{empty}</p>;
  return (
    <div className="bo-table-wrap">
      <table className="bo-table">
        <caption className="nl-sr-only">{caption}</caption>
        <thead><tr>{columns.map(c => <th key={c.key} scope="col" data-align={c.align ?? 'start'} data-phone={c.hideOnPhone ? 'hide' : undefined}>{c.label}</th>)}</tr></thead>
        <tbody>{rows.map(r => <tr key={rowKey(r)}>{columns.map(c => <td key={c.key} data-align={c.align ?? 'start'} data-phone={c.hideOnPhone ? 'hide' : undefined}>{c.render(r)}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}

/** A small square thumbnail in a grey well (a table's product photo); without a photo, the name's initial. */
export function Thumb({ src, name }: { src?: string | null; name: string }) {
  return <span className="bo-thumb" aria-hidden>{src ? <img src={src} alt="" loading="lazy" /> : name.trim().charAt(0).toUpperCase()}</span>;
}

// ── Record pages (S-177): a seller, an application, a dispute, an order, a case ──────────────────────────────────

/** A key fact in a record's head ("Type · Services", "Joined · Apr 2026"). */
export interface RecordFact { label: ReactNode; value: ReactNode }
export interface RecordHeaderProps {
  /** The way back to the list ("← All sellers"), above the title. */
  back?: ReactNode;
  title: ReactNode;
  /** The record's state: a `StatusTag` beside the title. */
  status?: ReactNode;
  facts?: readonly (RecordFact | null | false | undefined)[];
  /** The head's own buttons (Export, a destructive Suspend…); the record's work is in its `ActionPanel`. */
  actions?: ReactNode;
}
/**
 * A record page's head (the back office's detail pages): the way back, the h1 with the record's status tag, then its
 * key facts as a wrapping row of label · value pairs; buttons on the right.
 */
export function RecordHeader({ back, title, status, facts, actions }: RecordHeaderProps) {
  const shown = (facts ?? []).filter((f): f is RecordFact => !!f);
  return (
    <div className="bo-rec-head">
      {back ? <div className="bo-rec-back">{back}</div> : null}
      <div className="bo-head">
        <div className="bo-head-text">
          <div className="bo-rec-titlerow"><h1 className="bo-title">{title}</h1>{status}</div>
          {shown.length ? <dl className="bo-rec-facts">{shown.map((f, i) => <div key={i} className="bo-rec-fact"><dt>{f.label}</dt><dd>{f.value}</dd></div>)}</dl> : null}
        </div>
        {actions ? <div className="bo-head-actions">{actions}</div> : null}
      </div>
    </div>
  );
}

/**
 * A record page's body: the main column (figures, tabs) and the right-hand action panel. Two columns from 880px of
 * room (the panel 320px and sticky under the shell's top bar), one below, the panel after the main column.
 */
export function RecordLayout({ children, aside, asideLabel }: { children: ReactNode; aside?: ReactNode; /** The panel's landmark name ("Actions"). */ asideLabel?: string }) {
  return (
    <div className="bo-rec-wrap">
      <div className="bo-rec" data-aside={aside ? '' : undefined}>
        <div className="bo-rec-main">{children}</div>
        {aside ? <aside className="bo-rec-aside" aria-label={asideLabel}>{aside}</aside> : null}
      </div>
    </div>
  );
}

/** The right-hand action panel: its title, then each action (`ActionPanelItem`) stacked, then a note (who may act). */
export function ActionPanel({ title, children, note }: { title: ReactNode; children: ReactNode; note?: ReactNode }) {
  const id = useId();
  return (
    <section className="bo-card bo-actpanel" aria-labelledby={id}>
      <h2 className="bo-card-title" id={id}>{title}</h2>
      <div className="bo-actpanel-list">{children}</div>
      {note ? <p className="bo-muted">{note}</p> : null}
    </section>
  );
}

export interface ActionPanelItemProps {
  label: ReactNode;
  /** What it does, under the button ("Page, listings and checkout go offline at once"). */
  description?: ReactNode;
  /** `destructive` (red) for Suspend and every action that takes something away; `primary` for the record's next step. */
  variant?: ButtonVariant;
  disabled?: boolean;
  onClick?: () => void;
  /** A link instead of a button (an action on another screen). */
  href?: string;
}
/** One action of the panel: a full-width button and what it does under it. */
export function ActionPanelItem({ label, description, variant = 'secondary', disabled, onClick, href }: ActionPanelItemProps) {
  const id = useId();
  return (
    <div className="bo-actpanel-item">
      {href && !disabled
        ? <a className={`btn btn-${variant} btn-block`} href={href} aria-describedby={description ? id : undefined}>{label}</a>
        : <Button variant={variant} block disabled={disabled} onClick={onClick} aria-describedby={description ? id : undefined}>{label}</Button>}
      {description ? <p className="bo-actpanel-desc" id={id}>{description}</p> : null}
    </div>
  );
}

export interface RecordTab<V extends string = string> { value: V; label: ReactNode; count?: ReactNode; render: () => ReactNode }
/**
 * A record's tabs (history, evidence, notes): the underline tabs with counts over one tab panel, wired as ARIA tabs
 * (each tab controls the panel; ← → move, Home/End). Uncontrolled unless `value` is given.
 */
export function RecordTabs<V extends string>({ label, tabs, value, onChange }: { label: string; tabs: readonly RecordTab<V>[]; value?: V; onChange?: (v: V) => void }) {
  const id = useId();
  const [own, setOwn] = useState<V | undefined>(tabs[0]?.value);
  const current = tabs.find(x => x.value === (value ?? own)) ?? tabs[0];
  if (!current) return null;
  const pick = (v: V) => { setOwn(v); onChange?.(v); };
  const tabId = (v: string) => `${id}-tab-${v}`;
  return (
    <div className="bo-rec-tabs">
      <div className="nl-utabs" role="tablist" aria-label={label} onKeyDown={tablistKeyDown(tabs.map(x => x.value), current.value, pick)}>
        {tabs.map(x => (
          <button key={x.value} id={tabId(x.value)} type="button" role="tab" aria-selected={x.value === current.value} aria-controls={`${id}-panel`}
            tabIndex={x.value === current.value ? 0 : -1} className="nl-utab" onClick={() => pick(x.value)}>
            {x.label}{x.count != null ? <span className="nl-utab-count">{x.count}</span> : null}
          </button>
        ))}
      </div>
      <div id={`${id}-panel`} role="tabpanel" aria-labelledby={tabId(current.value)} tabIndex={0} className="bo-rec-panel">{current.render()}</div>
    </div>
  );
}

/** A record's history (a timeline or an audit trail): the date, then what happened and who did it, newest first. */
export function RecordHistory({ items, empty }: { items: readonly { key: string; when: ReactNode; text: ReactNode }[]; empty?: ReactNode }) {
  if (!items.length) return <p className="bo-muted">{empty}</p>;
  return <ol className="bo-rec-history">{items.map(e => <li key={e.key}><span className="bo-rec-when">{e.when}</span><span>{e.text}</span></li>)}</ol>;
}
