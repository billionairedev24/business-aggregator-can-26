import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Dialog, Field, FileButton, Select, Tag, TextInput, useFormatters, type TagTone } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId } from '../shell/api';
import { AGE_CLASSES, licencesQuery, useSubmitLicence, type AgeClass, type Licence } from './licences';
import { useLicenceT, type LicenceT } from './licenceMessages';

const ACCEPT = 'application/pdf,image/png,image/jpeg';

const statusTone = (s: Licence['status']): TagTone => (s === 'approved' ? 'accent' : s === 'pending' ? 'highlight' : s === 'replaced' ? 'neutral' : 'accent-2');

/**
 * Compliance › Age-restricted sales (2026-10-04): the licences for alcohol, tobacco/vape and cannabis accessories,
 * their review and expiry, and (owners) adding one. Copy is ours (no design drawing), en + fr-CA.
 */
export function RestrictedLicences({ owner }: { owner: boolean }) {
  const t = useLicenceT();
  const merchantId = useMerchantId();
  const q = useQuery(licencesQuery(merchantId));
  const { date } = useFormatters();
  const [adding, setAdding] = useState(false);
  if (q.isError) return null; // the rest of Compliance stands without it
  const items = q.data?.items ?? [];
  const licensed = q.data?.licensed ?? [];
  return (
    <section aria-labelledby="cmp-age" className="nl-cmp-gap">
      <h2 id="cmp-age" className="nl-cmp-h2">{t('title')}</h2>
      <p className="nl-cmp-meta">{t('lede')}</p>
      {q.isPending ? null : items.length === 0 ? (
        <p className="nl-small nl-muted">{t('none')}</p>
      ) : (
        <ul className="nl-cmp-rows">
          {items.filter(l => l.status !== 'replaced').map(l => (
            <li key={l.id} className="nl-cmp-row">
              <span>
                {t('row', { cls: t(`cls_${l.ageClass}`), number: l.licenceNumber, expires: date(`${l.expiresOn}T12:00:00Z`, 'long') })}
                {l.status === 'rejected' ? <span className="nl-small nl-muted"> · {reason(t, l.rejectReason)}{l.note ? ` — ${l.note}` : ''}</span> : null}
              </span>
              <Tag tone={statusTone(l.status)}>{t(`st_${l.status}`)}</Tag>
            </li>
          ))}
        </ul>
      )}
      {licensed.length > 0 ? <p className="nl-small">{t('licensed', { classes: licensed.map(c => t(`cls_${c}`)).join(', ') })}</p> : null}
      {owner ? <Button variant="secondary" onClick={() => setAdding(true)}>{t('add')}</Button> : null}
      {adding ? <AddLicence onClose={() => setAdding(false)} /> : null}
    </section>
  );
}

function reason(t: LicenceT, code: string | null | undefined): string {
  const key = `why_${code ?? 'other'}` as Parameters<LicenceT>[0];
  const text = t(key);
  return text === key ? t('why_other') : text;
}

function AddLicence({ onClose }: { onClose: () => void }) {
  const t = useLicenceT();
  const merchantId = useMerchantId();
  const submit = useSubmitLicence(merchantId);
  const [ageClass, setAgeClass] = useState<AgeClass>('alcohol');
  const [number, setNumber] = useState('');
  const [expires, setExpires] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const errors = submit.error instanceof ValidationError ? submit.error.byField() : {};
  const other = submit.error && !(submit.error instanceof ValidationError) ? t('error') : undefined;
  return (
    <Dialog open onClose={onClose} title={t('addTitle')}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={submit.isPending} aria-busy={submit.isPending}
          onClick={() => submit.mutate({ ageClass, licenceNumber: number, expiresOn: expires, file }, { onSuccess: onClose })}>{t('send')}</Button></>}>
      <p className="nl-small">{t('addLede')}</p>
      <Field label={t('class')} error={errors.ageClass}>
        <Select value={ageClass} onChange={e => setAgeClass(e.target.value as AgeClass)} options={AGE_CLASSES.map(c => ({ value: c, label: t(`cls_${c}`) }))} />
      </Field>
      <Field label={t('number')} hint={t('numberHint')} error={errors.licenceNumber}>
        <TextInput value={number} maxLength={40} onChange={e => setNumber(e.target.value)} />
      </Field>
      <Field label={t('expires')} error={errors.expiresOn}>
        <TextInput type="date" value={expires} onChange={e => setExpires(e.target.value)} />
      </Field>
      <Field label={t('document')} hint={file ? file.name : t('documentHint')} error={errors.file}>
        <FileButton accept={ACCEPT} onFile={setFile}>{file ? t('replaceFile') : t('chooseFile')}</FileButton>
      </Field>
      {other ? <Alert tone="error" role="alert">{other}</Alert> : null}
    </Dialog>
  );
}
