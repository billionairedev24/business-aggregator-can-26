import type { ReactNode } from 'react';
import { ArrowRight, type Icon } from '@phosphor-icons/react';
import clsx from 'clsx';

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
          {k.delta ? <span className="bo-kpi-delta" data-trend={k.trend ?? 'flat'}>{k.delta}</span> : null}
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
