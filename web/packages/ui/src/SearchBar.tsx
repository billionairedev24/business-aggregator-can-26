import { Fragment, useId, useState, type KeyboardEvent, type ReactNode } from 'react';
import clsx from 'clsx';
import { MagnifyingGlass } from '@phosphor-icons/react';
import { defineMessages } from './i18n';

const useT = defineMessages({
  en: { label: 'Search', button: 'Search', suggestions: 'Search suggestions' },
  fr: { label: 'Rechercher', button: 'Rechercher', suggestions: 'Suggestions de recherche' },
});

/** A part of `text` to set in bold: UTF-16 offsets, as the search API's `highlight` (S-44). */
export interface TextRange { start: number; length: number }

/** One prediction under the field (design 06 header / hero suggestions). */
export interface SearchSuggestion {
  /** Unique within the list. */
  id: string;
  text: string;
  /** Parts of `text` in bold (the typed letters). */
  highlight?: readonly TextRange[];
  /** Right-hand detail ("Glenmore Bakery · $7.50"). */
  meta?: ReactNode;
  icon?: ReactNode;
}
export interface SearchSuggestionGroup { id: string; label: ReactNode; items: readonly SearchSuggestion[] }

export interface SearchBarProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: (value: string) => void;
  /** hero: the home page's pill with a Search button; header: the header's field (off-home). */
  variant?: 'hero' | 'header';
  placeholder?: string;
  /** Accessible name; defaults to "Search". */
  label?: string;
  /**
   * Predictions as you type (S-48). Given, the field is an ARIA combobox: the list opens while the field has focus and
   * text, ↑/↓ move, Enter picks the highlighted one (else submits), Esc closes.
   */
  suggestions?: readonly SearchSuggestionGroup[];
  onPick?: (suggestion: SearchSuggestion) => void;
}

/** `text` with the highlighted ranges in <strong> (ranges outside the text are ignored). */
export function Highlighted({ text, ranges = [] }: { text: string; ranges?: readonly TextRange[] }) {
  const parts: ReactNode[] = [];
  let at = 0;
  [...ranges].sort((a, b) => a.start - b.start).forEach((r, i) => {
    const start = Math.max(r.start, at), end = Math.min(r.start + r.length, text.length);
    if (end <= start) return;
    if (start > at) parts.push(text.slice(at, start));
    parts.push(<strong key={i}>{text.slice(start, end)}</strong>);
    at = end;
  });
  if (at < text.length) parts.push(text.slice(at));
  return <>{parts}</>;
}

/** Search field (role=search). Enter or the button submits; with `suggestions` it predicts as you type. */
export function SearchBar({ value, onChange, onSubmit, variant = 'hero', placeholder, label, suggestions, onPick }: SearchBarProps) {
  const t = useT();
  const id = useId();
  const listId = `${id}-list`;
  const [focused, setFocused] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [active, setActive] = useState(-1);
  const combobox = suggestions !== undefined;
  const items = (suggestions ?? []).flatMap(g => g.items);
  const open = combobox && focused && !dismissed && value.trim().length > 0 && items.length > 0;
  const current = open && active >= 0 && active < items.length ? active : -1;

  const pick = (s: SearchSuggestion) => { setDismissed(true); setActive(-1); onPick?.(s); };
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (!combobox) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) { setDismissed(false); return; }
      // positions -1 (the field itself) … items.length - 1, wrapping around
      const step = e.key === 'ArrowDown' ? 1 : -1;
      const size = items.length + 1;
      setActive(a => ((a + 1 + step + size) % size) - 1);
    } else if (e.key === 'Enter' && current >= 0) {
      e.preventDefault();
      pick(items[current]!);
    } else if (e.key === 'Escape' && open) {
      e.preventDefault();
      setDismissed(true);
      setActive(-1);
    }
  };

  let index = -1;
  return (
    <form role="search" className={clsx('nl-search', `nl-search-${variant}`)}
      onSubmit={e => { e.preventDefault(); setDismissed(true); onSubmit(value.trim()); }}>
      <MagnifyingGlass weight="duotone" size={variant === 'hero' ? 22 : 18} className="nl-search-icon" aria-hidden />
      <label htmlFor={id} className="nl-sr-only">{label ?? t('label')}</label>
      <input id={id} type="search" className={clsx(variant === 'header' && 'input', 'nl-search-input')} value={value} placeholder={placeholder}
        enterKeyHint="search" autoComplete="off"
        {...(combobox ? {
          role: 'combobox', 'aria-autocomplete': 'list' as const, 'aria-expanded': open, 'aria-controls': listId,
          'aria-activedescendant': current >= 0 ? `${listId}-${current}` : undefined,
        } : {})}
        onChange={e => { setDismissed(false); setActive(-1); onChange(e.target.value); }}
        onFocus={() => { setFocused(true); setDismissed(false); }}
        onBlur={() => { setFocused(false); setActive(-1); }}
        onKeyDown={onKeyDown} />
      {variant === 'hero' && <button type="submit" className="btn btn-highlight nl-search-button">{t('button')}</button>}
      {combobox && (
        <div className="nl-suggest" hidden={!open}>
          <ul id={listId} role="listbox" aria-label={t('suggestions')}>
            {(suggestions ?? []).map(group => group.items.length === 0 ? null : (
              <Fragment key={group.id}>
                <li role="presentation" className="nl-suggest-head">{group.label}</li>
                {group.items.map(s => {
                  index += 1;
                  const i = index;
                  return (
                    <li key={s.id} id={`${listId}-${i}`} role="option" aria-selected={i === current}
                      className={clsx('nl-suggest-item', i === current && 'nl-suggest-item-active')}
                      // mousedown, not click: the field's blur would close the list before the click lands
                      onMouseDown={e => { e.preventDefault(); pick(s); }} onMouseEnter={() => setActive(i)}>
                      <span className="nl-suggest-text">
                        {s.icon ? <span className="nl-suggest-icon" aria-hidden>{s.icon}</span> : null}
                        <span><Highlighted text={s.text} ranges={s.highlight} /></span>
                      </span>
                      {s.meta ? <span className="nl-suggest-meta">{s.meta}</span> : null}
                    </li>
                  );
                })}
              </Fragment>
            ))}
          </ul>
        </div>
      )}
    </form>
  );
}
