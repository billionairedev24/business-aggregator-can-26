import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { defineMessages } from './i18n';

const useT = defineMessages({
  en: { period: 'Period', current: 'This period', previous: 'Previous period', point: 'Point' },
  fr: { period: 'Période', current: 'Cette période', previous: 'Période précédente', point: 'Point' },
});

/**
 * The chart's data as a table for screen readers (S-109, WCAG 1.1.1/1.3.1): the SVG is a picture with a title only, so
 * the numbers it draws are also given as text. Visually hidden; the figure's visible legend stays as designed.
 */
function DataTableAlt({ caption, head, rows }: { caption: string; head: readonly string[]; rows: readonly (readonly string[])[] }) {
  return (
    // the wrapper clips: a table ignores width: 1px and would widen the page (horizontal scroll at 320 px)
    <div className="nl-sr-only">
      <table>
        <caption>{caption}</caption>
        <thead><tr>{head.map((h, i) => <th key={i} scope="col">{h}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i}>{r.map((c, j) => (j === 0 ? <th key={j} scope="row">{c}</th> : <td key={j}>{c}</td>))}</tr>)}</tbody>
      </table>
    </div>
  );
}

/**
 * The SVG is drawn at the width it is shown at (measured), so its labels stay 11px on a phone and on a wide desktop
 * card alike — a fixed viewBox scaled them to 6px in a phone column. 480 until measured (and in tests).
 */
function useChartWidth() {
  const ref = useRef<HTMLElement>(null);
  const [w, setW] = useState(480);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const on = () => { const x = Math.round(el.getBoundingClientRect().width); if (x > 0) setW(Math.max(240, x)); };
    on();
    const ro = new ResizeObserver(on);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w] as const;
}
/** Every k-th label so that each has about 56px. */
const labelStep = (n: number, width: number) => Math.max(1, Math.ceil(n / Math.max(2, Math.floor(width / 56))));

export interface BarSeries { key: string; label: string; color: string }
export interface StackedBarChartProps { series: readonly BarSeries[]; data: readonly { label: string; values: Record<string, number> }[]; height?: number; title: string; format?: (n: number) => string }

/** Stacked weekly bars (Dashboard "Net earnings · 12 weeks"). Colours are token expressions, e.g. var(--color-action-primary). */
export function StackedBarChart({ series, data, height = 180, title, format = String }: StackedBarChartProps) {
  const t = useT();
  const [ref, W] = useChartWidth();
  const plotH = height - 20, n = Math.max(data.length, 1), slot = (W - 12) / n, bw = Math.min(32, slot * 0.62), step = labelStep(n, W);
  const max = Math.max(1, ...data.map(d => series.reduce((s, x) => s + (d.values[x.key] ?? 0), 0)));
  return (
    <figure className="nl-chart-figure" ref={ref}>
      <Legend items={series.map(s => ({ label: s.label, color: s.color }))} />
      <svg viewBox={`0 0 ${W} ${height}`} className="nl-chart" role="img" aria-label={title}>
        {data.map((d, i) => {
          let y = plotH;
          const x = 12 + i * slot;
          return (
            <g key={d.label}>
              <title>{`${d.label}: ${series.map(s => `${s.label} ${format(d.values[s.key] ?? 0)}`).join(', ')}`}</title>
              {series.map(s => { const h = ((d.values[s.key] ?? 0) / max) * (plotH - 8); y -= h; return <rect key={s.key} x={x} y={y} width={bw} height={h} fill={s.color} rx={2} />; })}
              {i % step === 0 ? <text x={x + bw / 2} y={height - 4} fontSize={11} textAnchor="middle">{d.label}</text> : null}
            </g>
          );
        })}
      </svg>
      <DataTableAlt caption={title} head={[t('period'), ...series.map(s => s.label)]} rows={data.map(d => [d.label, ...series.map(s => format(d.values[s.key] ?? 0))])} />
    </figure>
  );
}

export interface ColumnPoint { /** The axis label ("M", "Oct 12"). */ label: string; /** The full name for the tooltip and the table ("Monday, Oct 12"). */ name?: string; value: number }
export interface ColumnChartProps {
  title: string;
  data: readonly ColumnPoint[];
  /** The last column is the current period so far (today, this month): drawn in ink, the others grey. */
  highlightLast?: boolean;
  legend?: { current: ReactNode; past: ReactNode };
  height?: number;
  format?: (n: number) => string;
  /** The y-axis labels ("$750"); `format` when omitted. */
  axisFormat?: (n: number) => string;
}

/** A round axis top above `max` (1, 2, 2.5, 5 × a power of ten) and its four steps. */
function niceTop(max: number): number {
  if (max <= 0) return 4;
  const p = 10 ** Math.floor(Math.log10(max));
  return ([1, 2, 2.5, 5, 10].map(m => m * p).find(v => v >= max) ?? 10 * p);
}

/**
 * Daily or monthly sales as columns (design/revamp/a.html Studio dashboard "Sales, last 14 days"): a light grid with
 * y-axis figures, the current period in ink (`highlightLast`) and the earlier ones grey (the 3:1 control grey, WCAG
 * 1.4.11). Each column has a tooltip and the numbers are also a hidden table for screen readers.
 */
export function ColumnChart({ title, data, highlightLast, legend, height = 190, format = String, axisFormat }: ColumnChartProps) {
  const t = useT();
  const [ref, W] = useChartWidth();
  const axis = axisFormat ?? format;
  const left = 44, bottom = 22, top = 8;
  const plotH = height - bottom - top, n = Math.max(data.length, 1), slot = (W - left) / n, bw = Math.max(3, Math.min(28, slot * 0.66));
  const max = niceTop(Math.max(0, ...data.map(d => d.value)));
  // every label that fits: single letters (weekdays) all show; longer ones every k-th
  const longest = Math.max(1, ...data.map(d => d.label.length));
  const step = Math.max(1, Math.ceil(n / Math.max(2, Math.floor((W - left) / (longest * 7 + 12)))));
  const y = (v: number) => top + plotH - (v / max) * plotH;
  return (
    <figure className="nl-chart-figure" ref={ref}>
      <svg viewBox={`0 0 ${W} ${height}`} className="nl-chart nl-columns" role="img" aria-label={title}>
        {[0, 0.25, 0.5, 0.75, 1].map(f => (
          <g key={f}>
            <path d={`M${left} ${y(max * f).toFixed(1)} H${W}`} className="nl-columns-grid" />
            <text x={left - 6} y={y(max * f) + 3.5} textAnchor="end" className="nl-columns-axis">{axis(max * f)}</text>
          </g>
        ))}
        {data.map((d, i) => {
          const x = left + i * slot + (slot - bw) / 2;
          const h = Math.max(d.value > 0 ? 1 : 0, (d.value / max) * plotH);
          const now = highlightLast && i === data.length - 1;
          return (
            <g key={`${d.label}${i}`}>
              <rect x={x} y={top + plotH - h} width={bw} height={h} rx={1} className="nl-columns-bar" data-now={now || undefined}><title>{`${d.name ?? d.label}: ${format(d.value)}`}</title></rect>
              {i % step === 0 || i === data.length - 1 ? <text x={x + bw / 2} y={height - 6} textAnchor="middle" className="nl-columns-axis">{d.label}</text> : null}
            </g>
          );
        })}
      </svg>
      {legend ? <div className="nl-legend nl-columns-legend"><span><span className="nl-legend-swatch nl-columns-key" data-now="" />{legend.current}</span><span><span className="nl-legend-swatch nl-columns-key" />{legend.past}</span></div> : null}
      <DataTableAlt caption={title} head={[t('period'), title]} rows={data.map(d => [d.name ?? d.label, format(d.value)])} />
    </figure>
  );
}

export interface LineChartProps { current: readonly (number | null)[]; previous?: readonly (number | null)[]; labels?: readonly string[]; height?: number; title: string; legend?: { current: string; previous?: string }; format?: (n: number) => string;
  /** false when the screen shows the numbers as a table of its own (the console's reports): no hidden copy then. */
  dataTable?: boolean }
/** Period-over-period line (Reports "Weekly gross"): solid accent line with dots, dotted neutral previous period, light grid. */
export function LineChart({ current, previous, labels, height = 200, title, legend, format = String, dataTable = true }: LineChartProps) {
  const t = useT();
  const [ref, W] = useChartWidth();
  const n = Math.max(current.length, previous?.length ?? 0, 2);
  const known = (arr: readonly (number | null)[]) => arr.filter((v): v is number => v != null);
  const max = Math.max(1, ...known(current), ...known(previous ?? [])) * 1.1;
  const px = (i: number) => 20 + i * ((W - 40) / (n - 1));
  const py = (v: number) => height - 20 - (v / max) * (height - 40);
  // a missing value (withheld, not recorded) breaks the line instead of dropping it to zero
  const path = (arr: readonly (number | null)[]) => arr.map((v, i) => (v == null ? '' : `${i && arr[i - 1] != null ? 'L' : 'M'}${px(i).toFixed(1)} ${py(v).toFixed(1)}`)).filter(Boolean).join(' ');
  const grid = [0.25, 0.5, 0.75, 1].map(f => `M20 ${py(max * f / 1.1).toFixed(1)} H${W - 20}`).join(' ');
  return (
    <figure className="nl-chart-figure" ref={ref}>
      {legend ? <Legend items={[{ label: legend.current, color: 'var(--color-action-primary)' }, ...(legend.previous ? [{ label: legend.previous, color: 'var(--color-border-control)', dashed: true }] : [])]} /> : null}
      <svg viewBox={`0 0 ${W} ${height}`} className="nl-chart" role="img" aria-label={title}>
        <path d={grid} stroke="var(--neutral-100)" strokeWidth={1} fill="none" />
        {previous ? <path d={path(previous)} stroke="var(--color-border-control)" strokeWidth={1.5} strokeDasharray="4 4" fill="none" /> : null}
        <path d={path(current)} stroke="var(--color-action-primary)" strokeWidth={2.5} fill="none" />
        {current.map((v, i) => (v == null ? null : <circle key={i} cx={px(i)} cy={py(v)} r={3} fill="var(--color-action-primary)"><title>{`${labels?.[i] ?? i + 1}: ${format(v)}`}</title></circle>))}
        {labels?.map((l, i) => (i % labelStep(labels.length, W) === 0 ? <text key={l + i} x={px(i)} y={height - 4} fontSize={11} textAnchor="middle">{l}</text> : null))}
      </svg>
      {dataTable && <DataTableAlt caption={title}
        head={[t('point'), legend?.current ?? t('current'), ...(previous ? [legend?.previous ?? t('previous')] : [])]}
        rows={Array.from({ length: Math.max(current.length, previous?.length ?? 0) }, (_, i) => [labels?.[i] ?? String(i + 1), current[i] == null ? '' : format(current[i]!), ...(previous ? [previous[i] == null ? '' : format(previous[i]!)] : [])])} />}
    </figure>
  );
}

export function Legend({ items }: { items: readonly { label: ReactNode; color: string; dashed?: boolean }[] }) {
  return <div className="nl-legend">{items.map((it, i) => <span key={i}><span className="nl-legend-swatch" style={{ background: it.dashed ? 'transparent' : it.color, border: it.dashed ? `1.5px dashed ${it.color}` : undefined }} />{it.label}</span>)}</div>;
}

/** Horizontal bar list ("By listing"). */
export function BarList({ items, format = String }: { items: readonly { label: ReactNode; value: number }[]; format?: (n: number) => string }) {
  const max = Math.max(1, ...items.map(i => i.value));
  return (
    <div>
      {items.map((it, i) => (
        <div key={i} className="nl-meter">
          <span className="nl-meter-label" style={{ width: 170 }}>{it.label}</span>
          <div className="nl-meter-track" style={{ height: 10 }}><div className="nl-meter-fill" style={{ width: `${(it.value / max) * 100}%` }} /></div>
          <span className="nl-meter-value" style={{ width: 70 }}>{format(it.value)}</span>
        </div>
      ))}
    </div>
  );
}
