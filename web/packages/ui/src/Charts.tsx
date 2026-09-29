import type { ReactNode } from 'react';

export interface BarSeries { key: string; label: string; color: string }
export interface StackedBarChartProps { series: readonly BarSeries[]; data: readonly { label: string; values: Record<string, number> }[]; height?: number; title: string; format?: (n: number) => string }

/** Stacked weekly bars (Dashboard "Net earnings · 12 weeks"). Colours are token expressions, e.g. var(--color-accent). */
export function StackedBarChart({ series, data, height = 180, title, format = String }: StackedBarChartProps) {
  const W = 480, plotH = height - 20, n = Math.max(data.length, 1), slot = (W - 12) / n, bw = Math.min(26, slot * 0.7);
  const max = Math.max(1, ...data.map(d => series.reduce((s, x) => s + (d.values[x.key] ?? 0), 0)));
  return (
    <figure style={{ margin: 0 }}>
      <Legend items={series.map(s => ({ label: s.label, color: s.color }))} />
      <svg viewBox={`0 0 ${W} ${height}`} className="nl-chart" role="img" aria-label={title}>
        {data.map((d, i) => {
          let y = plotH;
          const x = 12 + i * slot;
          return (
            <g key={d.label}>
              <title>{`${d.label}: ${series.map(s => `${s.label} ${format(d.values[s.key] ?? 0)}`).join(', ')}`}</title>
              {series.map(s => { const h = ((d.values[s.key] ?? 0) / max) * (plotH - 8); y -= h; return <rect key={s.key} x={x} y={y} width={bw} height={h} fill={s.color} rx={2} />; })}
              <text x={x + bw / 2} y={height - 4} fontSize={10} textAnchor="middle">{d.label}</text>
            </g>
          );
        })}
      </svg>
    </figure>
  );
}

export interface LineChartProps { current: readonly number[]; previous?: readonly number[]; labels?: readonly string[]; height?: number; title: string; legend?: { current: string; previous?: string } }
/** Period-over-period line (Reports "Weekly gross"): solid accent line with dots, dotted neutral previous period, light grid. */
export function LineChart({ current, previous, labels, height = 200, title, legend }: LineChartProps) {
  const W = 480, n = Math.max(current.length, previous?.length ?? 0, 2);
  const max = Math.max(1, ...current, ...(previous ?? [])) * 1.1;
  const px = (i: number) => 20 + i * ((W - 40) / (n - 1));
  const py = (v: number) => height - 20 - (v / max) * (height - 40);
  const path = (arr: readonly number[]) => arr.map((v, i) => `${i ? 'L' : 'M'}${px(i).toFixed(1)} ${py(v).toFixed(1)}`).join(' ');
  const grid = [0.25, 0.5, 0.75, 1].map(f => `M20 ${py(max * f / 1.1).toFixed(1)} H${W - 20}`).join(' ');
  return (
    <figure style={{ margin: 0 }}>
      {legend ? <Legend items={[{ label: legend.current, color: 'var(--color-accent)' }, ...(legend.previous ? [{ label: legend.previous, color: 'var(--color-neutral-500)', dashed: true }] : [])]} /> : null}
      <svg viewBox={`0 0 ${W} ${height}`} className="nl-chart" role="img" aria-label={title}>
        <path d={grid} stroke="var(--color-neutral-200)" strokeWidth={1} fill="none" />
        {previous ? <path d={path(previous)} stroke="var(--color-neutral-500)" strokeWidth={1.5} strokeDasharray="4 4" fill="none" /> : null}
        <path d={path(current)} stroke="var(--color-accent)" strokeWidth={2.5} fill="none" />
        {current.map((v, i) => <circle key={i} cx={px(i)} cy={py(v)} r={3} fill="var(--color-accent)"><title>{`${labels?.[i] ?? i + 1}: ${v}`}</title></circle>)}
        {labels?.map((l, i) => (i % Math.ceil(labels.length / 7) === 0 ? <text key={l + i} x={px(i)} y={height - 4} fontSize={10} textAnchor="middle">{l}</text> : null))}
      </svg>
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
