import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Field, TextArea, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { frenchQuery, useFrenchT, useSaveFrench } from './french';
import { useMessageT } from './validation';

/**
 * S-116: the listing's French name and description, under its own. Where the business's place asks for French listing
 * text (region configuration `french_listings`), the panel says so — a warning, or that the listing can't go live
 * without it — until both are written.
 */
export function FrenchTextPanel({ merchantId, listingId, disabled }: { merchantId: string; listingId: string | undefined; disabled?: boolean }) {
  const t = useFrenchT();
  if (!listingId) return <p className="nl-hint">{t('saveFirst')}</p>;
  return <FrenchFields merchantId={merchantId} listingId={listingId} disabled={disabled} />;
}

function FrenchFields({ merchantId, listingId, disabled }: { merchantId: string; listingId: string; disabled?: boolean }) {
  const t = useFrenchT();
  const mt = useMessageT();
  const q = useQuery(frenchQuery(merchantId, listingId));
  const save = useSaveFrench(merchantId, listingId);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);
  useEffect(() => { if (q.data) { setTitle(q.data.title); setDescription(q.data.description ?? ''); } }, [q.data]);

  if (q.isError) return <Alert tone="error">{t('loadError')}</Alert>;
  const rule = q.data?.rule ?? 'off';
  const missing = q.data?.missing ?? true;

  async function onSave() {
    setErrors({}); setSaved(false);
    try { await save.mutateAsync({ title, description }); setSaved(true); }
    catch (e) {
      if (e instanceof ValidationError) setErrors(Object.fromEntries(e.errors.map(x => [x.field, mt(x.message) ?? x.message])));
      else setErrors({ form: t('error') });
    }
  }

  return (
    <section className="nl-cat-french" aria-labelledby={`french-${listingId}`}>
      <h2 id={`french-${listingId}`} className="nl-label">{t('title')}</h2>
      <p className="nl-hint">{t('hint')}</p>
      {rule !== 'off' && missing && <Alert tone={rule === 'require' ? 'error' : 'neutral'} role="status">{t(rule === 'require' ? 'require' : 'warn')}</Alert>}
      <Field label={t('name')} error={errors.title}>
        <TextInput lang="fr-CA" value={title} maxLength={80} disabled={disabled} onChange={e => setTitle(e.target.value)} />
      </Field>
      <Field label={t('description')} error={errors.description}>
        <TextArea lang="fr-CA" rows={4} value={description} disabled={disabled} onChange={e => setDescription(e.target.value)} />
      </Field>
      {errors.form && <Alert tone="error">{errors.form}</Alert>}
      {saved && <p role="status" className="nl-hint">{t('saved')}</p>}
      <Button variant="secondary" onClick={() => void onSave()} disabled={disabled || save.isPending}>{save.isPending ? t('saving') : t('save')}</Button>
    </section>
  );
}
