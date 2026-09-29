import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import clsx from 'clsx';
import { Check, X } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import './GroupedMultiSelect.css';

const useT = defineMessages({
  en: { done: 'Done', remove: 'Remove {name}', selected: '{count} of {max} selected', options: 'Options' },
  fr: { done: 'Terminé', remove: 'Retirer {name}', selected: '{count} sur {max} sélectionnés', options: 'Options' },
});

export interface MultiSelectItem { id: string; name: string; /** Small outlined tag after the name (e.g. the regulator). */ badge?: string }
export interface MultiSelectGroup { id: string; name: string; note?: string; items: readonly MultiSelectItem[] }

export interface GroupedMultiSelectProps {
  groups: readonly MultiSelectGroup[];
  /** Selected item ids. */
  value: readonly string[];
  onChange: (ids: string[]) => void;
  /** Free-text entries that matched nothing (shown as chips next to the selection). */
  suggestions?: readonly string[];
  onSuggestionsChange?: (names: string[]) => void;
  /** Most selections (items + suggestions). */
  max: number;
  placeholder?: string;
  /** Accessible name of the search input. */
  label: string;
  /** Shown at the top of the list when the limit is reached. */
  limitMessage?: ReactNode;
  /** "No match" line; receives the query and a callback that adds it as a suggestion. */
  renderNoMatch?: (query: string, suggest: (() => void) | undefined) => ReactNode;
  invalid?: boolean;
  describedBy?: string;
  id?: string;
  /** Called when the popup closes (the field counts as touched). */
  onClose?: () => void;
}

/**
 * Searchable, grouped multi-select with a hard limit (onboarding "Services you offer"): chips for the selection, a
 * combobox input, a listbox of groups whose items filter by item or group name, and an optional "suggest" path for
 * free text. Items beyond the limit are disabled.
 */
export function GroupedMultiSelect({ groups, value, onChange, suggestions = [], onSuggestionsChange, max, placeholder, label, limitMessage, renderNoMatch, invalid, describedBy, id, onClose }: GroupedMultiSelectProps) {
  const t = useT();
  const auto = useId();
  const inputId = id ?? auto;
  const listId = `${inputId}-list`;
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const wrap = useRef<HTMLDivElement>(null);
  const count = value.length + suggestions.length;
  const atMax = count >= max;
  const q = query.trim().toLowerCase();
  const names = useMemo(() => new Map(groups.flatMap(g => g.items.map(i => [i.id, i.name] as const))), [groups]);
  const shown = useMemo(() => groups
    .map(g => ({ ...g, items: g.items.filter(i => !q || i.name.toLowerCase().includes(q) || g.name.toLowerCase().includes(q)) }))
    .filter(g => g.items.length), [groups, q]);
  const flat = shown.flatMap(g => g.items);

  const close = () => { if (!open) return; setOpen(false); setQuery(''); onClose?.(); };
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => { if (!wrap.current?.contains(e.target as Node)) close(); };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  });
  useEffect(() => { setActive(0); }, [q]);

  const toggle = (itemId: string) => {
    if (value.includes(itemId)) onChange(value.filter(v => v !== itemId));
    else if (!atMax) onChange([...value, itemId]);
  };
  const suggest = onSuggestionsChange && q && !atMax
    ? () => { onSuggestionsChange([...suggestions, query.trim()]); setQuery(''); }
    : undefined;

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive(a => Math.min(flat.length - 1, a + 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(0, a - 1)); }
    else if (e.key === 'Enter') { e.preventDefault(); const it = flat[active]; if (open && it) toggle(it.id); else if (!flat.length) suggest?.(); }
    else if (e.key === 'Escape') { close(); }
  };
  const activeId = open && flat[active] ? `${inputId}-opt-${flat[active]!.id}` : undefined;

  return (
    <div className="nl-gms" ref={wrap}>
      {count > 0 && (
        <div className="nl-gms-chips">
          {value.map(v => (
            <span key={v} className="nl-gms-chip">{names.get(v) ?? v}
              <button type="button" aria-label={t('remove', { name: names.get(v) ?? v })} onClick={() => onChange(value.filter(x => x !== v))}><X size={12} weight="bold" /></button>
            </span>
          ))}
          {suggestions.map(s => (
            <span key={`s-${s}`} className="nl-gms-chip">{s}
              <button type="button" aria-label={t('remove', { name: s })} onClick={() => onSuggestionsChange?.(suggestions.filter(x => x !== s))}><X size={12} weight="bold" /></button>
            </span>
          ))}
        </div>
      )}
      <div className="nl-gms-anchor">
        <input
          id={inputId}
          className="input"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          aria-label={label}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          placeholder={placeholder}
          value={query}
          onChange={e => { setQuery(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
        />
        {open && (
          <div className="nl-gms-pop">
            {atMax && limitMessage ? <div className="nl-gms-limit" role="status">{limitMessage}</div> : null}
            <div id={listId} role="listbox" aria-multiselectable="true" aria-label={label}>
              {shown.map(g => (
                <div key={g.id} role="group" aria-labelledby={`${inputId}-g-${g.id}`}>
                  <div className="nl-gms-group" id={`${inputId}-g-${g.id}`}>{g.name}{g.note ? <span className="nl-gms-note"> · {g.note}</span> : null}</div>
                  {g.items.map(it => {
                    const on = value.includes(it.id);
                    const disabled = !on && atMax;
                    const idx = flat.indexOf(it);
                    return (
                      <div
                        key={it.id}
                        id={`${inputId}-opt-${it.id}`}
                        role="option"
                        aria-selected={on}
                        aria-disabled={disabled || undefined}
                        className={clsx('nl-gms-opt', idx === active && 'nl-gms-opt-active')}
                        onMouseDown={e => e.preventDefault()}
                        onMouseEnter={() => setActive(idx)}
                        onClick={() => !disabled && toggle(it.id)}
                      >
                        <span className="nl-gms-box" data-on={on} aria-hidden>{on ? <Check size={11} weight="bold" /> : null}</span>
                        <span style={{ flex: 1 }}>{it.name}</span>
                        {it.badge ? <span className="tag tag-outline nl-gms-badge">{it.badge}</span> : null}
                      </div>
                    );
                  })}
                </div>
              ))}
            </div>
            {!shown.length && renderNoMatch ? <div className="nl-gms-empty">{renderNoMatch(query.trim(), suggest)}</div> : null}
            <div className="nl-gms-foot"><button type="button" className="btn btn-ghost" onClick={close}>{t('done')}</button></div>
          </div>
        )}
      </div>
    </div>
  );
}
