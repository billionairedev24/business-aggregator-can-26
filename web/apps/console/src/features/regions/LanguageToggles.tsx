import { useState } from 'react';
import { Button, ChartCard, Field, Select } from '@northline/ui';
import { useSwitchboard, type LanguageSetting, type Province } from './api';
import { useLanguageT } from './languageMessages';

type First = 'inherit' | 'yes' | 'no';
type Listings = 'inherit' | 'off' | 'warn' | 'require';

const firstOf = (s: LanguageSetting): First => (s.frenchFirst == null ? 'inherit' : s.frenchFirst ? 'yes' : 'no');
const listingsOf = (s: LanguageSetting): Listings => s.frenchListings ?? 'inherit';

/**
 * S-158: S-116's language toggles per province and market (they were SQL or REGION_FRENCH_FIRST only): French first and
 * what listings need in French, a market following its province unless it has its own. Admins (action `province`);
 * the api audits every change and re-reads the region model.
 */
export function LanguageToggles({ province: p, name, allowed }: { province: Province; name: string; allowed: boolean }) {
  const t = useLanguageT();
  const rules = p.languageRules ?? [];
  if (rules.length === 0) return null;
  return (
    <ChartCard id={`rg-lang-${p.code}`} title={t('title', { name })}>
      <p className="bo-muted">{t('lede')}</p>
      <div className="nl-rg-rows">
        {rules.map(s => <LanguageRow key={s.regionId} setting={s} allowed={allowed} />)}
      </div>
      <div className="nl-rg-note">{allowed ? t('audited') : t('cannot')}</div>
    </ChartCard>
  );
}

function LanguageRow({ setting: s, allowed }: { setting: LanguageSetting; allowed: boolean }) {
  const t = useLanguageT();
  const change = useSwitchboard();
  const [first, setFirst] = useState<First>(firstOf(s));
  const [listings, setListings] = useState<Listings>(listingsOf(s));
  const [saved, setSaved] = useState(false);
  const market = s.kind === 'market';
  const place = market ? (s.city ?? s.regionId) : t('provinceRow');
  const dirty = first !== firstOf(s) || listings !== listingsOf(s);
  const save = () => change.mutate({
    kind: 'languages', regionId: s.regionId,
    frenchFirst: first === 'inherit' ? null : first === 'yes', frenchListings: listings === 'inherit' ? null : listings,
  }, { onSuccess: () => setSaved(true) });
  return (
    <div className="nl-rg-market" data-kind={s.kind}>
      <span><strong>{place}</strong>
        <span className="nl-rg-meta nl-rg-block">{t('applies', { first: String(s.effectiveFrenchFirst), listings: t(`lst_${s.effectiveFrenchListings}`) })}</span>
        {s.fromConfiguration ? <span className="nl-rg-meta nl-rg-block">{t('configured')}</span> : null}
      </span>
      <Field label={t('frenchFirst')}>
        <Select value={first} disabled={!allowed} onChange={e => { setFirst(e.target.value as First); setSaved(false); }}
          options={[{ value: 'inherit', label: t(market ? 'ff_inherit' : 'ff_unset') }, { value: 'yes', label: t('ff_yes') }, { value: 'no', label: t('ff_no') }]} />
      </Field>
      <Field label={t('frenchListings')}>
        <Select value={listings} disabled={!allowed} onChange={e => { setListings(e.target.value as Listings); setSaved(false); }}
          options={[{ value: 'inherit', label: t(market ? 'fl_inherit' : 'fl_unset') }, { value: 'off', label: t('fl_off') }, { value: 'warn', label: t('fl_warn') }, { value: 'require', label: t('fl_require') }]} />
      </Field>
      {allowed ? <span className="nl-rg-actions">
        <Button variant="secondary" aria-label={t('saveLabel', { place })} disabled={!dirty || change.isPending} onClick={save}>{t('save')}</Button>
        {saved && !dirty ? <span role="status" className="nl-rg-note">{t('saved')}</span> : null}
      </span> : null}
      {change.isError ? <p role="alert" className="nl-rg-error">{t('failed')}</p> : null}
    </div>
  );
}
