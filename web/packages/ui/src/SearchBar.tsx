import { useId } from 'react';
import clsx from 'clsx';
import { MagnifyingGlass } from '@phosphor-icons/react';
import { defineMessages } from './i18n';

const useT = defineMessages({
  en: { label: 'Search', button: 'Search' },
  fr: { label: 'Rechercher', button: 'Rechercher' },
});

export interface SearchBarProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: (value: string) => void;
  /** hero: the home page's pill with a Search button; header: the header's field (off-home). */
  variant?: 'hero' | 'header';
  placeholder?: string;
  /** Accessible name; defaults to "Search". */
  label?: string;
}

/** Search field (role=search). Enter or the button submits; typeahead (S-48) hangs off the same input. */
export function SearchBar({ value, onChange, onSubmit, variant = 'hero', placeholder, label }: SearchBarProps) {
  const t = useT();
  const id = useId();
  return (
    <form role="search" className={clsx('nl-search', `nl-search-${variant}`)} onSubmit={e => { e.preventDefault(); onSubmit(value.trim()); }}>
      <MagnifyingGlass weight="duotone" size={variant === 'hero' ? 22 : 18} className="nl-search-icon" aria-hidden />
      <label htmlFor={id} className="nl-sr-only">{label ?? t('label')}</label>
      <input id={id} type="search" className={clsx(variant === 'header' && 'input', 'nl-search-input')} value={value} placeholder={placeholder}
        enterKeyHint="search" autoComplete="off" onChange={e => onChange(e.target.value)} />
      {variant === 'hero' && <button type="submit" className="btn btn-highlight nl-search-button">{t('button')}</button>}
    </form>
  );
}
