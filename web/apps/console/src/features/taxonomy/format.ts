import { ApiError } from '../../lib/http';
import type { Category, Regulator } from './api';
import type { TaxonomyT } from './messages';

export const errorText = (e: unknown) => (e instanceof ApiError ? e.message : e ? String(e) : undefined);

/** "Yes · AMVIC, Safety Codes (AB), BC: none" / "No" — the design's "Regulated · registry" column. */
export function regulation(c: Category, regulators: readonly Regulator[], t: TaxonomyT): string {
  const parts: string[] = c.regulatedRegistry ? [c.regulatedRegistry] : [];
  for (const r of c.regulators) {
    parts.push(r.regulator
      ? t('regIn', { name: regulators.find(x => x.code === r.regulator)?.name ?? r.regulator, province: r.province })
      : t('regNone', { province: r.province }));
  }
  const regulated = !!c.regulatedRegistry || c.regulators.some(r => !!r.regulator);
  if (regulated) return t('yes', { list: parts.join(', ') });
  return parts.length ? t('noList', { list: parts.join(', ') }) : t('no');
}
