import { useState, type DragEvent } from 'react';
import { ArrowDown, ArrowUp, DotsSixVertical } from '@phosphor-icons/react';
import { Switch } from '@northline/ui';
import type { Section } from './api';
import { sectionText, useStorefrontT } from './messages';
import { move, type SectionKind } from './sections';

export interface SectionListProps {
  sections: readonly Section[];
  selected: SectionKind;
  onSelect: (kind: SectionKind) => void;
  /** New full order + enabled flags (one PATCH). */
  onChange: (next: { kind: SectionKind; enabled: boolean }[], moved?: { kind: SectionKind; to: number }) => void;
  disabled?: boolean;
}

/**
 * Page sections: drag ⇅ (HTML5 drag and drop), ↑/↓ buttons, on/off switches (hero and cta always on) and a row
 * button that selects the section for the detail panel. Every change emits the full ordered list.
 */
export function SectionList({ sections, selected, onSelect, onChange, disabled }: SectionListProps) {
  const t = useStorefrontT();
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);
  const list = sections.map(s => ({ kind: s.kind, enabled: s.enabled }));
  const name = (k: SectionKind) => sectionText(t, k, 'name');
  const reorder = (from: number, to: number) => {
    if (from === to || to < 0 || to >= list.length) return;
    onChange(move(list, from, to), { kind: list[from]!.kind, to });
  };
  const toggle = (i: number, on: boolean) => onChange(list.map((s, j) => (j === i ? { ...s, enabled: on } : s)));
  const endDrag = () => { setDragFrom(null); setDragOver(null); };

  return (
    <ol className="nl-sections" aria-label={t('sectionsHint')}>
      {sections.map((s, i) => {
        const nm = name(s.kind);
        const onDragStart = (e: DragEvent) => { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', s.kind); setDragFrom(i); };
        const onDragOver = (e: DragEvent) => { if (dragFrom === null) return; e.preventDefault(); if (dragOver !== i) setDragOver(i); };
        const onDrop = (e: DragEvent) => { e.preventDefault(); if (dragFrom !== null) reorder(dragFrom, i); endDrag(); };
        return (
          <li
            key={s.kind}
            className="nl-section-row"
            data-selected={selected === s.kind}
            data-off={!s.enabled}
            data-over={dragOver === i && dragFrom !== i}
            draggable={!disabled}
            onDragStart={onDragStart}
            onDragOver={onDragOver}
            onDrop={onDrop}
            onDragEnd={endDrag}
          >
            <span className="nl-section-grip" aria-hidden><DotsSixVertical size={18} /></span>
            <button type="button" className="nl-section-main" aria-pressed={selected === s.kind} onClick={() => onSelect(s.kind)}>
              <span className="nl-section-name">{nm}{s.required ? <span className="tag tag-neutral nl-section-tag">{t('alwaysOn')}</span> : null}</span>
              <span className="nl-section-desc">{sectionText(t, s.kind, 'desc')}</span>
            </button>
            <span className="nl-section-arrows">
              <button type="button" className="btn btn-ghost nl-arrow" aria-label={`${t('moveUp')} · ${nm}`} disabled={disabled || i === 0} onClick={() => reorder(i, i - 1)}><ArrowUp size={16} /></button>
              <button type="button" className="btn btn-ghost nl-arrow" aria-label={`${t('moveDown')} · ${nm}`} disabled={disabled || i === sections.length - 1} onClick={() => reorder(i, i + 1)}><ArrowDown size={16} /></button>
            </span>
            <Switch checked={s.enabled} label={nm} disabled={disabled || s.required} onChange={on => toggle(i, on)} />
          </li>
        );
      })}
    </ol>
  );
}
