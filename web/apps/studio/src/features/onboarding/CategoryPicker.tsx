import { useId } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ErrorState, GroupedMultiSelect, Skeleton, useLocale } from '@northline/ui';
import type { MerchantType } from '../shell/api';
import { taxonomyQuery } from './api';
import { useOnboardingT } from './messages';
import { CATEGORY_LIMIT } from './validation';

/** Group notes in French (the taxonomy serves English notes from the seed file). */
const NOTE_FR: Record<string, string> = {
  'AMVIC licence checked': 'Permis AMVIC vérifié', 'Safety Codes / municipal permits': 'Safety Codes / permis municipaux', 'Insurance required': 'Assurance requise',
  'ProServe / AGLC for bar': 'ProServe / AGLC pour le bar', 'Provincial college where applicable': 'Ordre professionnel provincial s’il y a lieu', 'Regulated professions verified': 'Professions réglementées vérifiées',
  'Vulnerable-sector check': 'Vérification pour secteur vulnérable', 'AHS / CFIA rules': 'Règles AHS / ACIA', 'Health Canada labelling': 'Étiquetage Santé Canada', 'Health Canada toy safety': 'Sécurité des jouets Santé Canada', 'Licence required': 'Permis requis',
};

export interface CategoryPickerProps {
  type: MerchantType;
  value: string[];
  onChange: (ids: string[]) => void;
  suggestions: string[];
  onSuggestionsChange: (names: string[]) => void;
  /** Names of already-selected ids (from the saved application) while the taxonomy loads. */
  error?: string;
  onClose: () => void;
}

/** "Services you offer · N of M selected" — grouped, searchable, regulators shown, per-type limit (design 02 `taxonomy`). */
export function CategoryPicker({ type, value, onChange, suggestions, onSuggestionsChange, error, onClose }: CategoryPickerProps) {
  const t = useOnboardingT();
  const { locale } = useLocale();
  const id = useId();
  const tax = useQuery(taxonomyQuery(type, locale));
  const max = tax.data?.limit ?? CATEGORY_LIMIT[type];
  const count = value.length + suggestions.length;
  const label = t(`catLabel_${type}`);
  return (
    <div className="nl-field nl-span-all">
      <label className="nl-label" htmlFor={id}>{label}<span className="nl-label-note"> {t('catCount', { count, max })}</span></label>
      {tax.isPending ? <Skeleton height={44} /> : tax.isError ? <ErrorState message={t('taxonomyError')} onRetry={() => void tax.refetch()} /> : (
        <GroupedMultiSelect
          id={id}
          label={label}
          groups={tax.data.groups.map(g => ({
            id: g.id,
            name: g.name,
            note: locale === 'fr' ? NOTE_FR[g.note] ?? g.note : g.note,
            items: g.items.map(i => ({ id: i.id, name: i.name, badge: i.regulator ?? undefined })),
          }))}
          value={value}
          onChange={onChange}
          suggestions={suggestions}
          onSuggestionsChange={onSuggestionsChange}
          max={max}
          placeholder={t(type === 'kitchen' ? 'catPh_kitchen' : type === 'seller' ? 'catPh_seller' : 'catPh')}
          limitMessage={t('catLimit')}
          renderNoMatch={(q, suggest) => <>{t('noMatch')} {suggest ? <button type="button" className="btn btn-ghost" style={{ minHeight: 32, padding: '0 4px' }} onClick={suggest}>{t('suggest', { q })}</button> : null}{t('suggestTail')}</>}
          invalid={!!error}
          describedBy={error ? `${id}-err` : `${id}-note`}
          onClose={onClose}
        />
      )}
      {error ? <div id={`${id}-err`} role="alert" className="nl-error">{error}</div> : null}
      <div id={`${id}-note`} className="nl-hint">{t(`catNote_${type}`)}</div>
    </div>
  );
}
