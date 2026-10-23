import { useEffect, useId, useRef, useState } from 'react';
import { MagnifyingGlass } from '@phosphor-icons/react';
import { defineMessages } from './i18n';

const useT = defineMessages({
  en: { shortcut: 'Press / to search', results: '{count, plural, one {# place to search} other {# places to search}}' },
  fr: { shortcut: 'Appuyez sur / pour rechercher', results: '{count, plural, one {# endroit où chercher} other {# endroits où chercher}}' },
});

export interface ShellSearchScope { key: string; /** "Orders matching “brake”" — the option shown for the typed query. */ label: (query: string) => string }
export interface ShellSearchProps {
  /** The search's accessible name ("Search the Studio"). */
  label: string;
  placeholder: string;
  /** Where a query can go, most useful first; Enter takes the highlighted one (the first by default). */
  scopes: readonly ShellSearchScope[];
  onSearch: (query: string, scope: string) => void;
}

const editable = (el: Element | null) => !!el && (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement || (el as HTMLElement).isContentEditable);

/**
 * The back office's global search (design/revamp/a.html `bo-search`): a grey field with a "/" hint; "/" anywhere on the
 * page (outside a field) focuses it. Typing offers the places the query can go ("Orders matching …", "Products
 * matching …") as a combobox list: ↑/↓ move, Enter goes, Escape closes then clears. One scope submits directly.
 */
export function ShellSearch({ label, placeholder, scopes, onSearch }: ShellSearchProps) {
  const t = useT();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const wrap = useRef<HTMLFormElement>(null);
  const listId = useId();
  const query = q.trim();
  const showList = open && query.length > 0 && scopes.length > 1;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey || editable(document.activeElement)) return;
      if (!input.current || input.current.offsetParent === null) return; // not on screen (the phone menu is closed)
      e.preventDefault();
      input.current.focus();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => { if (!wrap.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  const go = (i: number) => {
    const scope = scopes[i] ?? scopes[0];
    if (!query || !scope) return;
    setOpen(false);
    onSearch(query, scope.key);
  };
  return (
    <form ref={wrap} role="search" aria-label={label} className="nl-shell-search" onSubmit={e => { e.preventDefault(); go(active); }}>
      <MagnifyingGlass size={16} aria-hidden className="nl-shell-search-icon" />
      <input
        ref={input} type="search" className="nl-shell-search-input" value={q} placeholder={placeholder} aria-label={label} aria-keyshortcuts="/"
        role={scopes.length > 1 ? 'combobox' : undefined} aria-expanded={scopes.length > 1 ? showList : undefined} aria-controls={showList ? listId : undefined}
        aria-autocomplete={scopes.length > 1 ? 'list' : undefined} aria-activedescendant={showList ? `${listId}-${active}` : undefined}
        onChange={e => { setQ(e.target.value); setOpen(true); setActive(0); }}
        onFocus={() => setOpen(true)}
        onKeyDown={e => {
          if (e.key === 'Escape') { if (showList) setOpen(false); else setQ(''); e.preventDefault(); return; }
          if (!showList || (e.key !== 'ArrowDown' && e.key !== 'ArrowUp')) return;
          e.preventDefault();
          setActive(a => (a + (e.key === 'ArrowDown' ? 1 : -1) + scopes.length) % scopes.length);
        }}
      />
      <kbd className="nl-shell-search-kbd" title={t('shortcut')} aria-hidden>/</kbd>
      {showList ? (
        <ul id={listId} role="listbox" aria-label={t('results', { count: scopes.length })} className="nl-shell-search-list">
          {scopes.map((s, i) => (
            <li key={s.key} id={`${listId}-${i}`} role="option" aria-selected={i === active} className="nl-shell-search-option"
              onMouseDown={e => e.preventDefault()} onMouseEnter={() => setActive(i)} onClick={() => go(i)}>
              <MagnifyingGlass size={14} aria-hidden />{s.label(query)}
            </li>
          ))}
        </ul>
      ) : null}
    </form>
  );
}
