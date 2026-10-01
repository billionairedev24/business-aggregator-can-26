import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, Field, FormGrid, OptionCard, Select, useLocale } from '@northline/ui';
import { useRegions } from '../location/regions';
import { FormSkeleton } from './ProfileTab';
import { prefsQuery, useSavePrefs, type Prefs } from './settingsApi';
import { useSettingsT } from './settingsMessages';

/**
 * Language / Langue & region (design 06 `at.language`): the app language (it switches in place and is the language of
 * receipts and notifications), the province you shop in (the served ones from the region model, pilots marked),
 * units, currency (CAD only) and the time format.
 */
export function LanguageTab() {
  const t = useSettingsT();
  const prefs = useQuery(prefsQuery);
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('langTitle')}</h1>
      {prefs.isPending ? <FormSkeleton label={t('loading')} rows={4} />
        : prefs.isError ? <ErrorState message={t('loadError')} onRetry={() => void prefs.refetch()} />
          : <LanguageForm initial={prefs.data} />}
    </>
  );
}

function LanguageForm({ initial }: { initial: Prefs }) {
  const t = useSettingsT();
  const { locale, setLocale } = useLocale();
  const regions = useRegions();
  const save = useSavePrefs();
  const [p, setP] = useState(initial);
  const [saved, setSaved] = useState(false);
  useEffect(() => setP(initial), [initial]);
  const update = (next: Partial<Prefs>) => { setP(x => ({ ...x, ...next })); setSaved(false); };
  const served = (regions?.provinces ?? []).filter(r => r.status === 'live' || r.status === 'pilot');
  const provinces = served.map(r => ({ value: r.code, label: r.status === 'pilot' ? t('pilot', { name: r.name }) : r.name }));
  if (p.province && !provinces.some(o => o.value === p.province)) provinces.push({ value: p.province, label: p.province });
  const submit = () => save.mutate({ language: p.language, province: p.province ?? undefined, units: p.units, timeFormat: p.timeFormat }, {
    onSuccess: next => { setSaved(true); if (next.language !== locale) setLocale(next.language); },
  });
  return (
    <>
      <fieldset className="nl-acct-fieldset">
        <legend className="nl-label">{t('appLanguage')}</legend>
        <div className="nl-lang-options">
          {(['en', 'fr'] as const).map(l => (
            <OptionCard key={l} type="button" selected={p.language === l} aria-pressed={p.language === l} lang={l === 'fr' ? 'fr-CA' : 'en-CA'}
              title={t(`l_${l}`)} description={t(`l_${l}_sub`)} onClick={() => update({ language: l })} />
          ))}
        </div>
      </fieldset>
      <FormGrid min={200} className="nl-acct-form">
        <Field label={t('regionProvince')}>
          <Select value={p.province ?? ''} onChange={e => update({ province: e.target.value || null })} options={[{ value: '', label: t('provinceAuto') }, ...provinces]} />
        </Field>
        <Field label={t('units')}>
          <Select value={p.units} onChange={e => update({ units: e.target.value as Prefs['units'] })} options={(['metric', 'imperial'] as const).map(u => ({ value: u, label: t(`u_${u}`) }))} />
        </Field>
        <Field label={t('currency')}><Select value="CAD" disabled options={[{ value: 'CAD', label: 'CAD · $' }]} /></Field>
        <Field label={t('timeFormat')}>
          <Select value={p.timeFormat} onChange={e => update({ timeFormat: e.target.value as Prefs['timeFormat'] })} options={(['12h', '24h'] as const).map(f => ({ value: f, label: t(`t_${f}`) }))} />
        </Field>
      </FormGrid>
      <p className="nl-small nl-muted nl-measure">{t('langNote')}</p>
      {save.isError ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      <div className="nl-acct-actions">
        <Button type="button" onClick={submit} disabled={save.isPending} aria-busy={save.isPending}>{t('save')}</Button>
        <span className="nl-small nl-muted" role="status">{saved ? t('saved') : ''}</span>
      </div>
    </>
  );
}
