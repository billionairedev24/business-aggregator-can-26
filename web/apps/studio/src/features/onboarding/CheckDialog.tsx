import { useState } from 'react';
import { Checkbox, Dialog, Field, FileButton, OptionCard, TextArea, TextInput, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { uploadDocument } from '../storefront/api';
import type { Check, CompleteInput } from './api';
import { slotLabel } from './checks';
import { useOnboardingT } from './messages';
import { visitSlots } from './model';

export interface CheckDialogProps {
  merchantId: string;
  check: Check;
  title: string;
  pending: boolean;
  /** Last server 422 for this check, by field (`reference`, `documentId`, `choice`, `expiresOn`). */
  errors: Record<string, string>;
  onSubmit: (input: CompleteInput) => void;
  onClose: () => void;
}

/** Evidence for one check: a number, an upload (+ expiry), a signature, a choice or a visit slot. */
export function CheckDialog({ merchantId, check, title, pending, errors, onSubmit, onClose }: CheckDialogProps) {
  const t = useOnboardingT();
  const { locale } = useLocale();
  const [reference, setReference] = useState('');
  const [doc, setDoc] = useState<{ id: string; name: string } | null>(null);
  const [expiresOn, setExpiresOn] = useState('');
  const [choice, setChoice] = useState<string>(check.key === 'aglc' ? 'not_applicable' : check.key === 'category_permits' ? 'none' : '');
  const [agreed, setAgreed] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [local, setLocal] = useState<Record<string, string>>({});
  const err = (k: string) => local[k] || errors[k];
  const key = check.key.startsWith('licence:') ? 'licence' : check.key;

  const upload = async (file: File) => {
    setUploading(true); setLocal({});
    try { const d = await uploadDocument(merchantId, file, 'verification'); setDoc({ id: d.id, name: d.fileName }); }
    catch (e) { setLocal({ documentId: e instanceof ValidationError ? e.errors[0]?.message ?? t('saveError') : t('saveError') }); }
    finally { setUploading(false); }
  };

  const submit = () => {
    setLocal({});
    switch (check.action) {
      case 'number': return onSubmit({ reference });
      case 'upload': return onSubmit({ documentId: doc?.id, expiresOn: expiresOn || undefined });
      case 'sign': if (!agreed) { setLocal({ choice: t('signRequired') }); return; } return onSubmit({ choice: 'signed' });
      case 'slot': if (!choice) { setLocal({ reference: t('slotRequired') }); return; } return onSubmit({ reference: choice });
      case 'choose':
        if (key === 'returns_policy') { if (!choice) { setLocal({ choice: t('choiceRequired') }); return; } return onSubmit({ choice }); }
        return onSubmit(choice === 'reference' ? { reference } : { choice });
      default: return onSubmit({});
    }
  };

  const numberLabel = key === 'gst' ? t('dlgNumber_gst') : key === 'ahs_permit' ? t('dlgNumber_ahs_permit') : t('dlgNumber_licence');
  const radio = (value: string, label: string) => <OptionCard key={value} role="radio" aria-checked={choice === value} selected={choice === value} title={label} onClick={() => setChoice(value)} />;

  return (
    <Dialog open onClose={onClose} title={title} actions={<>
      <button type="button" className="btn btn-ghost" onClick={onClose}>{t('dlgCancel')}</button>
      <button type="button" className="btn btn-primary" disabled={pending || uploading} onClick={submit}>{check.action === 'number' ? t('dlgSave') : check.action === 'sign' ? t('dlgSign') : t('dlgSubmit')}</button>
    </>}>
      {check.action === 'number' && (
        <Field label={numberLabel} error={err('reference')}><TextInput data-autofocus value={reference} onChange={e => setReference(e.target.value)} onKeyDown={e => e.key === 'Enter' && submit()} /></Field>
      )}
      {check.action === 'upload' && (
        <div className="nl-ob-stack">
          <div className="nl-field">
            <span className="nl-label">{t('dlgFile')}</span>
            <FileButton accept="application/pdf,image/png,image/jpeg" pending={uploading} aria-invalid={!!err('documentId')} onFile={f => void upload(f)}>{uploading ? t('uploading') : doc ? t('uploaded', { file: doc.name }) : t('dlgChoose')}</FileButton>
            {err('documentId') ? <div role="alert" className="nl-error">{err('documentId')}</div> : <div className="nl-hint">{t('dlgUploadHint')}</div>}
          </div>
          {key === 'insurance' && <Field label={t('dlgExpires')} error={err('expiresOn')}><TextInput type="date" value={expiresOn} onChange={e => setExpiresOn(e.target.value)} /></Field>}
        </div>
      )}
      {check.action === 'sign' && (
        <>
          <div className="nl-ob-sign">{t(key === 'allergen_attestation' ? 'dlgSignText_allergen_attestation' : 'dlgSignText_product_safety')}</div>
          <Checkbox checked={agreed} onChange={setAgreed} label={t('dlgAgree')} />
          {err('choice') ? <div role="alert" className="nl-error">{err('choice')}</div> : null}
        </>
      )}
      {check.action === 'choose' && (
        <div className="nl-ob-stack" role="radiogroup" aria-label={title}>
          {key === 'returns_policy' && <>{radio('standard', t('ret_standard'))}{radio('perishables', t('ret_perishables'))}</>}
          {key === 'category_permits' && <>{radio('none', t('permitsNone'))}{radio('reference', t('permitsNumbers'))}</>}
          {key === 'aglc' && <>{radio('not_applicable', t('aglcNone'))}{radio('reference', t('aglcLicence'))}</>}
          {choice === 'reference' && (key === 'category_permits'
            ? <Field label={t('permitsNumbers')} error={err('reference')}><TextArea value={reference} onChange={e => setReference(e.target.value)} /></Field>
            : <Field label={t('aglcLicence')} error={err('reference')}><TextInput value={reference} onChange={e => setReference(e.target.value)} /></Field>)}
          {err('choice') ? <div role="alert" className="nl-error">{err('choice')}</div> : null}
          {choice !== 'reference' && err('reference') ? <div role="alert" className="nl-error">{err('reference')}</div> : null}
        </div>
      )}
      {check.action === 'slot' && (
        <div className="nl-ob-stack">
          <span className="nl-label">{t('dlgSlots')}</span>
          <div className="nl-ob-slots" role="radiogroup" aria-label={t('dlgSlots')}>
            {visitSlots(new Date()).map(s => { const iso = s.toISOString(); return radio(iso, slotLabel(iso, locale)); })}
          </div>
          {err('reference') ? <div role="alert" className="nl-error">{err('reference')}</div> : null}
        </div>
      )}
    </Dialog>
  );
}
