import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Chip, ErrorState, Field, TextArea, TextInput } from '@northline/ui';
import { FormSkeleton } from './ProfileTab';
import { ACCESSIBILITY, DIETARY, DISPLAY, prefsQuery, useSavePrefs, type Prefs } from './settingsApi';
import { useSettingsT, type SettingsT } from './settingsMessages';

/** Dietary & accessibility (design 06 `at.dietary`): dietary chips and allergies, accessibility chips and notes, display. */
export function DietaryTab() {
  const t = useSettingsT();
  const prefs = useQuery(prefsQuery);
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('dietTitle')}</h1>
      <p className="nl-acct-lede">{t('dietLede')}</p>
      {prefs.isPending ? <FormSkeleton label={t('loading')} rows={5} />
        : prefs.isError ? <ErrorState message={t('loadError')} onRetry={() => void prefs.refetch()} />
          : <DietaryForm initial={prefs.data} />}
    </>
  );
}

function Chips({ label, codes, prefix, selected, onToggle, t }: {
  label: string; codes: readonly string[]; prefix: 'd' | 'a' | 'x'; selected: string[]; onToggle: (c: string) => void; t: SettingsT;
}) {
  return (
    <div className="nl-chips" role="group" aria-label={label}>
      {codes.map(c => <Chip key={c} selected={selected.includes(c)} onClick={() => onToggle(c)}>{t(`${prefix}_${c}` as Parameters<SettingsT>[0])}</Chip>)}
    </div>
  );
}

function DietaryForm({ initial }: { initial: Prefs }) {
  const t = useSettingsT();
  const save = useSavePrefs();
  const [p, setP] = useState(initial);
  const [errors, setErrors] = useState<{ allergies?: string; accessNotes?: string }>({});
  const [saved, setSaved] = useState(false);
  useEffect(() => setP(initial), [initial]);
  const toggle = (key: 'dietary' | 'accessibility' | 'display') => (c: string) => {
    setP(x => ({ ...x, [key]: x[key].includes(c) ? x[key].filter(v => v !== c) : [...x[key], c] }));
    setSaved(false);
  };
  const submit = () => {
    const next = {
      allergies: (p.allergies ?? '').length > 200 ? t('v_allergies') : undefined,
      accessNotes: (p.accessNotes ?? '').length > 500 ? t('v_notes') : undefined,
    };
    setErrors(next);
    if (next.allergies || next.accessNotes) return;
    save.mutate({ dietary: p.dietary, allergies: p.allergies ?? '', accessibility: p.accessibility, accessNotes: p.accessNotes ?? '', display: p.display },
      { onSuccess: () => setSaved(true) });
  };
  return (
    <>
      <h2 className="nl-acct-h2 nl-first">{t('dietary')}</h2>
      <Chips label={t('dietary')} codes={DIETARY} prefix="d" selected={p.dietary} onToggle={toggle('dietary')} t={t} />
      <Field label={t('allergies')} error={errors.allergies} className="nl-acct-wide">
        <TextInput value={p.allergies ?? ''} placeholder={t('allergiesPlaceholder')} onChange={e => { setP(x => ({ ...x, allergies: e.target.value })); setSaved(false); }} />
      </Field>
      <h2 className="nl-acct-h2">{t('accessibility')}</h2>
      <Chips label={t('accessibility')} codes={ACCESSIBILITY} prefix="a" selected={p.accessibility} onToggle={toggle('accessibility')} t={t} />
      <Field label={t('accessNotes')} error={errors.accessNotes} className="nl-acct-wide">
        <TextArea value={p.accessNotes ?? ''} placeholder={t('accessNotesPlaceholder')} rows={3} onChange={e => { setP(x => ({ ...x, accessNotes: e.target.value })); setSaved(false); }} />
      </Field>
      <h2 className="nl-acct-h2">{t('display')}</h2>
      <Chips label={t('display')} codes={DISPLAY} prefix="x" selected={p.display} onToggle={toggle('display')} t={t} />
      {save.isError ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      <div className="nl-acct-actions">
        <Button type="button" onClick={submit} disabled={save.isPending} aria-busy={save.isPending}>{t('savePrefs')}</Button>
        <span className="nl-small nl-muted" role="status">{saved ? t('saved') : ''}</span>
      </div>
    </>
  );
}
