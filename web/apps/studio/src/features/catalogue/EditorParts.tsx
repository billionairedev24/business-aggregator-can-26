import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { CheckCircle, Circle, XCircle } from '@phosphor-icons/react';
import { Alert, Button, Select, useFormatters, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import type { Category, ListingDetail } from './api';
import { useCatalogueT, type CatalogueT } from './messages';
import { categoryPath, childrenOf, fees, type Check } from './model';
import { useMessageT } from './validation';

// ── form state ────────────────────────────────────────────────────────────────────────────────────────────────────
/**
 * Editor form state: values, touched fields, submit attempt and server 422 errors. An error shows after the field
 * is touched or after a save/submit attempt (validation-rules.md); a server error clears when its field changes.
 */
export function useEditorForm<F extends object>(initial: F, validate: (f: F) => Record<string, string>) {
  const [form, setForm] = useState(initial);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [attempted, setAttempted] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [dirty, setDirty] = useState(false);
  const client = useMemo(() => validate(form), [form, validate]);

  const update = useCallback((patch: Partial<F>, fields: string[] = Object.keys(patch)) => {
    setForm(f => ({ ...f, ...patch }));
    setDirty(true);
    setServer(s => { const next = { ...s }; for (const k of fields) for (const key of Object.keys(next)) if (key === k || key.startsWith(`${k}.`) || key.startsWith(`${k}[`)) delete next[key]; return next; });
  }, []);
  const touch = useCallback((field: string) => setTouched(t => (t[field] ? t : { ...t, [field]: true })), []);
  const error = useCallback((field: string) => ((touched[field] || attempted) ? client[field] : undefined) ?? server[field], [touched, attempted, client, server]);
  /** Errors to count in the "N things need attention." summary. */
  const visible = useMemo(() => (attempted ? { ...client, ...server } : server), [attempted, client, server]);

  const reset = useCallback((next: F) => { setForm(next); setTouched({}); setAttempted(false); setServer({}); setDirty(false); }, []);
  /** Maps a 422 onto fields; returns false when the error was something else. */
  const fromServer = useCallback((e: unknown) => {
    if (!(e instanceof ValidationError)) return false;
    const map: Record<string, string> = {};
    for (const err of e.errors) map[err.field] ??= err.message;
    setServer(map);
    setAttempted(true);
    return true;
  }, []);
  return { form, update, touch, error, client, visible, attempted, setAttempted, reset, fromServer, dirty, setDirty };
}

/** "N things need attention." — the summary banner after a failed save or submit. */
export function AttentionSummary({ errors, t }: { errors: Record<string, string>; t: CatalogueT }) {
  const mt = useMessageT();
  const list = Object.entries(errors);
  if (!list.length) return null;
  return (
    <Alert tone="error" title={t('attention', { count: list.length })}>
      <ul className="nl-cat-attention">{list.slice(0, 6).map(([k, m]) => <li key={k}>{mt(m)}</li>)}</ul>
    </Alert>
  );
}

// ── header ────────────────────────────────────────────────────────────────────────────────────────────────────────
export function draftTag(detail: ListingDetail | undefined, t: CatalogueT, time: (iso: string) => string): { cls: string; label: string } {
  if (!detail) return { cls: 'tag-neutral', label: t('draftPrivate') };
  switch (detail.vetting) {
    case 'pending': return { cls: 'tag-accent-2', label: detail.vettingFlags.length ? t('inReview') : t('submitted') };
    case 'approved': return { cls: 'tag-accent', label: detail.status === 'live' ? t('approvedLive') : t('approvedHidden') };
    case 'rejected': return { cls: 'tag-accent-2', label: t('rejected') };
    default: return { cls: 'tag-neutral', label: t('draftSaved', { time: time(detail.updatedAt) }) };
  }
}

export function EditorHeader(props: {
  kicker: string; title: string; tag: { cls: string; label: string }; canEdit: boolean; roleName: string;
  saveLabel: string; saving: boolean; submitting: boolean; cannotSubmit: boolean; onSave: () => void; onSubmit: () => void;
}) {
  const t = useCatalogueT();
  return (
    <div className="nl-page-head">
      <div style={{ minWidth: 0 }}>
        <span className="nl-kicker">{props.kicker}</span>
        <h1 className="nl-page-title">{props.title}</h1>
      </div>
      <div className="nl-page-actions">
        <span className={`tag ${props.tag.cls}`} role="status">{props.tag.label}</span>
        {props.canEdit ? <>
          <Button variant="secondary" onClick={props.onSave} disabled={props.saving || props.submitting}>{props.saving ? t('saving') : props.saveLabel}</Button>
          <Button onClick={props.onSubmit} disabled={props.cannotSubmit || props.saving || props.submitting}>{props.submitting ? t('submitting') : t('submit')}</Button>
        </> : <span className="tag tag-neutral">{t('viewOnly', { role: props.roleName })}</span>}
      </div>
    </div>
  );
}

// ── re-vetting (S-39) ─────────────────────────────────────────────────────────────────────────────────────────────
/**
 * The state around re-vetting. An approved listing gets a one-line hint that material changes send it back to
 * vetting. A listing being re-vetted gets a notice saying what changed and that customers don't see it meanwhile.
 */
export function RevetNotice({ detail, kind }: { detail: ListingDetail | undefined; kind: 'product' | 'service' }) {
  const t = useCatalogueT();
  const { locale } = useLocale();
  if (!detail) return null;
  if (detail.vetting === 'approved') return <p className="nl-cat-note" style={{ marginBottom: 16 }}>{t('materialHint', { kind })}</p>;
  if (detail.vetting !== 'pending' || !detail.revetReasons.length) return null;
  const reasons = new Intl.ListFormat(locale, { type: 'conjunction' }).format(detail.revetReasons.map(r => t(`reason_${r}`)));
  return (
    <div style={{ marginBottom: 16 }}>
      <Alert tone="neutral" role="status" title={t('revetTitle')}>
        {detail.vettingFlags.length ? t('revetFlagged', { reasons }) : t('revetBody', { reasons })}
      </Alert>
    </div>
  );
}

// ── tabs with completion dots ─────────────────────────────────────────────────────────────────────────────────────
export function EditorTabs<K extends string>({ tabs, value, onChange, label }: { tabs: { key: K; name: string; done: boolean }[]; value: K; onChange: (k: K) => void; label: string }) {
  const t = useCatalogueT();
  return (
    <div className="nl-cat-tabs" role="tablist" aria-label={label}>
      {tabs.map(tab => (
        <button key={tab.key} type="button" role="tab" id={`tab-${tab.key}`} aria-selected={tab.key === value} aria-controls={`panel-${tab.key}`}
          className="nl-chip nl-cat-tab" aria-pressed={tab.key === value} onClick={() => onChange(tab.key)}
          aria-label={tab.done ? t('complete', { name: tab.name }) : t('incomplete', { name: tab.name })}>
          {tab.name}<span className="nl-cat-dot" data-done={tab.done} data-current={tab.key === value} aria-hidden />
        </button>
      ))}
    </div>
  );
}

// ── category picker ───────────────────────────────────────────────────────────────────────────────────────────────
/** Cascading dropdowns: one select per level of the tree, until a leaf is chosen. */
export function CategoryPicker({ categories, value, onChange, disabled, rootLabel, invalid, describedBy, id }: {
  categories: Category[]; value: string; onChange: (id: string) => void; disabled?: boolean; rootLabel: string; invalid?: boolean; describedBy?: string; id?: string;
}) {
  const t = useCatalogueT();
  const path = categoryPath(categories, value);
  const levels: { parent: string | null; selected: string }[] = [{ parent: null, selected: path[0]?.id ?? '' }];
  path.forEach((c, i) => { if (!c.leaf) levels.push({ parent: c.id, selected: path[i + 1]?.id ?? '' }); });
  return (
    <div className="nl-cat-cascade">
      {levels.map((lvl, i) => {
        const options = childrenOf(categories, lvl.parent).map(c => ({ value: c.id, label: i === 0 ? `${rootLabel} › ${c.name}` : c.name }));
        return (
          <Select key={lvl.parent ?? 'root'} id={i === 0 ? id : undefined} aria-label={t('categoryLevel', { n: i + 1 })} options={options} placeholder={t('choose')}
            value={lvl.selected} disabled={disabled} aria-invalid={invalid || undefined} aria-describedby={describedBy}
            onChange={e => onChange(e.target.value || (lvl.parent ?? ''))} />
        );
      })}
    </div>
  );
}

// ── side panels ───────────────────────────────────────────────────────────────────────────────────────────────────
export function CompletenessPanel({ percent, items, t }: { percent: number; items: { name: string; done: boolean }[]; t: CatalogueT }) {
  return (
    <div className="nl-cat-side">
      <strong>{t('completeness', { pct: percent })}</strong>
      <div className="nl-cat-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} aria-label={t('completeness', { pct: percent })}>
        <div className="nl-cat-bar-fill" data-complete={percent === 100} style={{ width: `${percent}%` }} />
      </div>
      <ul className="nl-cat-checklist">
        {items.map(i => <li key={i.name}><span className="nl-cat-dot" data-done={i.done} aria-hidden />{i.done ? i.name : t('incomplete', { name: i.name })}</li>)}
      </ul>
    </div>
  );
}

export function VettingPanel({ checks, t }: { checks: Check[]; t: CatalogueT }) {
  const clean = checks.every(c => c.ok === true);
  return (
    <div className="nl-cat-side">
      <strong>{t('vettingPreview')}</strong>
      <ul className="nl-cat-checks">
        {checks.map(c => (
          <li key={c.key} data-ok={String(c.ok)}>
            {c.ok === true ? <CheckCircle size={16} weight="duotone" aria-hidden /> : c.ok === false ? <XCircle size={16} weight="duotone" aria-hidden /> : <Circle size={16} aria-hidden />}
            <span>{t(c.key as Parameters<CatalogueT>[0], c.values)}</span>
          </li>
        ))}
      </ul>
      <span className="nl-cat-expect">{clean ? t('vpAuto') : t('vpManual')}</span>
    </div>
  );
}

export function FeesPanel({ priceCents, costCents, tier }: { priceCents: number | null; costCents: number | null; tier: string | null | undefined }) {
  const t = useCatalogueT();
  const { money } = useFormatters();
  if (!priceCents || priceCents <= 0) return null;
  const f = fees(priceCents, costCents, tier);
  return (
    <div className="nl-cat-side">
      <strong>{t('fees')}</strong><br />
      {t('feesLine', { sale: money(priceCents), rate: f.rate, fee: money(f.fee), processing: money(f.processing) })}<br />
      <strong>{t('net', { net: money(f.net) })}</strong>{f.margin !== null ? ` · ${t('margin', { pct: f.margin })}` : ''}
    </div>
  );
}

export const Side = ({ children }: { children: ReactNode }) => <aside className="nl-cat-aside">{children}</aside>;
