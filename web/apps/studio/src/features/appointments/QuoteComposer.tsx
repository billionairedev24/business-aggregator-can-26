import { useId, useRef, useState } from 'react';
import { Alert, Field, FormGrid, Select, TextArea, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId } from '../shell/api';
import { useReviseQuote, useSendQuote, useUploadMedia, type LineKind, type Quote, type QuoteRequest } from './api';
import { useAppointmentsT } from './messages';
import { DURATIONS, VALID_HOURS, attention, initialState, newLine, toBody, totals, validate, type ComposerLine, type ComposerState } from './quote';

const KINDS: LineKind[] = ['labour', 'part', 'fee', 'travel', 'discount'];

/**
 * The itemized quote composer (design: quotes / qLines / qTot / qErr). Sends version 1, or — with `revising` — a
 * revision of that quote. Errors show after the first send attempt, with the "N things to fix" summary.
 */
export function QuoteComposer({ request, revising, onDone, onCancel }: { request: QuoteRequest; revising?: Quote | null; onDone: () => void; onCancel: () => void }) {
  const t = useAppointmentsT();
  const f = useFormatters();
  const merchantId = useMerchantId();
  const send = useSendQuote(merchantId);
  const revise = useReviseQuote(merchantId);
  const upload = useUploadMedia(merchantId);
  const [s, setS] = useState<ComposerState>(() => initialState(request, revising));
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [uploadErr, setUploadErr] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const uid = useId();

  const errors = { ...(tried ? validate(s) : {}), ...server };
  const tot = totals(s.lines);
  const count = attention(errors);
  const pending = send.isPending || revise.isPending;
  const set = (patch: Partial<ComposerState>) => { setS(x => ({ ...x, ...patch })); setServer({}); };
  const setLine = (i: number, patch: Partial<ComposerLine>) => set({ lines: s.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) });

  const submit = () => {
    setTried(true);
    if (Object.keys(validate(s)).length > 0) return;
    const body = toBody(s);
    const opts = {
      onSuccess: () => onDone(),
      onError: (e: Error) => { if (e instanceof ValidationError) setServer(Object.fromEntries(e.errors.map(x => [x.field, x.message]))); },
    };
    if (revising) revise.mutate({ quoteId: revising.id, body }, opts); else send.mutate({ requestId: request.id, body }, opts);
  };

  const onFiles = async (files: FileList | null) => {
    setUploadErr(null);
    for (const file of Array.from(files ?? [])) {
      try { const m = await upload.mutateAsync(file); setS(x => ({ ...x, attachments: [...x.attachments, m] })); }
      catch { setUploadErr(t('uploadError', { name: file.name })); }
    }
    if (fileRef.current) fileRef.current.value = '';
  };
  const sendError = (send.isError || revise.isError) && !(send.error instanceof ValidationError) && !(revise.error instanceof ValidationError);

  return (
    <div className="nl-appt-composer">
      <div className="nl-appt-composer-head">
        <strong>{revising ? t('composerRevising', { ref: request.ref, version: revising.version + 1 }) : t('composerTitle', { ref: request.ref })}</strong>
        <span className="nl-small nl-muted">{t('composerNote')}</span>
      </div>
      <div className="nl-appt-lines" role="group" aria-label={t('colItem')}>
        <div className="nl-appt-line nl-appt-line-head" aria-hidden><span>{t('colItem')}</span><span>{t('colType')}</span><span>{t('colQty')}</span><span className="nl-right">{t('colAmount')}</span><span /></div>
        {s.lines.map((l, i) => {
          const dErr = errors[`lines[${i}].description`], aErr = errors[`lines[${i}].unitCents`], qErr = errors[`lines[${i}].qty`];
          const err = dErr ?? aErr ?? qErr;
          const errId = `${uid}-l${i}`;
          return (
            <div key={l.key}>
              <div className="nl-appt-line">
                <TextInput aria-label={t('lineItem', { n: i + 1 })} value={l.name} placeholder={t('itemPlaceholder')} maxLength={200} aria-invalid={dErr ? true : undefined} aria-describedby={err ? errId : undefined} onChange={e => setLine(i, { name: e.target.value })} />
                <Select aria-label={t('lineType', { n: i + 1 })} value={l.kind} onChange={e => setLine(i, { kind: e.target.value as LineKind })} options={KINDS.map(k => ({ value: k, label: t(`kind_${k}`) }))} />
                <TextInput aria-label={t('lineQty', { n: i + 1 })} inputMode="decimal" value={l.qty} className="nl-center" aria-invalid={qErr ? true : undefined} onChange={e => setLine(i, { qty: e.target.value })} />
                <TextInput aria-label={t('lineAmount', { n: i + 1 })} inputMode="decimal" value={l.amount} className="nl-right" aria-invalid={aErr ? true : undefined} aria-describedby={err ? errId : undefined} onChange={e => setLine(i, { amount: e.target.value })} />
                <button type="button" className="btn btn-ghost nl-appt-x" aria-label={t('removeLine', { n: i + 1 })} disabled={s.lines.length === 1} onClick={() => set({ lines: s.lines.filter((_, j) => j !== i) })}>×</button>
              </div>
              {err ? <div id={errId} role="alert" className="nl-error">{err}</div> : null}
            </div>
          );
        })}
        {errors.lines ? <div role="alert" className="nl-error">{errors.lines}</div> : null}
      </div>
      <div className="nl-appt-quick">
        <button type="button" className="btn btn-ghost" onClick={() => set({ lines: [...s.lines, newLine()] })}>{t('addLine')}</button>
        <button type="button" className="btn btn-ghost" onClick={() => set({ lines: [...s.lines, newLine({ name: t('travelLine'), kind: 'travel', amount: '20' })] })}>{t('addTravel')}</button>
        <button type="button" className="btn btn-ghost" onClick={() => set({ lines: [...s.lines, newLine({ name: t('shopLine'), kind: 'fee', amount: '12' })] })}>{t('addShop')}</button>
      </div>
      <dl className="nl-appt-totals" aria-live="polite">
        <dt>{t('totLabour')}</dt><dd>{f.money(tot.labour)}</dd>
        <dt>{t('totParts')}</dt><dd>{f.money(tot.parts)}</dd>
        <dt>{t('totFees')}</dt><dd>{f.money(tot.fees)}</dd>
        {tot.discount > 0 ? <><dt>{t('totDiscount')}</dt><dd>−{f.money(tot.discount)}</dd></> : null}
        <dt>{t('totGst')}</dt><dd>{f.money(tot.tax)}</dd>
        <dt className="nl-appt-total">{t('totTotal')}</dt><dd className="nl-appt-total nl-appt-total-v">{f.money(tot.total)}</dd>
      </dl>
      <FormGrid min={200} style={{ marginTop: 14 }}>
        <Field label={t('proposedTime')}><TextInput type="datetime-local" value={s.proposedAt} onChange={e => set({ proposedAt: e.target.value })} /></Field>
        <Field label={t('duration')}><Select value={String(s.durationMin)} onChange={e => set({ durationMin: Number(e.target.value) })} options={DURATIONS.map(d => ({ value: String(d), label: t(`dur${d}`) }))} /></Field>
        <Field label={t('validFor')} error={errors.validHours}><Select value={String(s.validHours)} onChange={e => set({ validHours: Number(e.target.value) })} options={VALID_HOURS.map(h => ({ value: String(h), label: t(`valid${h}`) }))} /></Field>
        <Field label={t('deposit')}><Select value={s.deposit} onChange={e => set({ deposit: e.target.value as ComposerState['deposit'] })} options={[{ value: 'none', label: t('depositNone') }, { value: 'parts_upfront', label: t('depositParts') }, { value: 'pct', label: t('depositPct') }]} /></Field>
        <Field span label={t('scope')} note={t('scopeNote')} error={errors.scope}><TextArea className="nl-appt-scope" value={s.scope} placeholder={t('scopePlaceholder')} onChange={e => set({ scope: e.target.value })} /></Field>
        <Field span label={t('exclusions')} note={t('exclusionsNote')}><TextArea className="nl-appt-excl" value={s.exclusions} placeholder={t('exclusionsPlaceholder')} onChange={e => set({ exclusions: e.target.value })} /></Field>
        <Field label={t('warranty')}><Select value={s.warranty} onChange={e => set({ warranty: e.target.value as ComposerState['warranty'] })} options={(['parts_labour_12m', 'labour_90d', 'manufacturer', 'none'] as const).map(w => ({ value: w, label: t(`w_${w}`) }))} /></Field>
        <div className="nl-field">
          <span className="nl-label">{t('attachments')}</span>
          <input ref={fileRef} type="file" hidden multiple accept="image/jpeg,image/png,image/heic,image/webp,application/pdf" onChange={e => void onFiles(e.target.files)} />
          <button type="button" className="btn btn-secondary nl-appt-attach" disabled={upload.isPending || s.attachments.length >= 10} onClick={() => fileRef.current?.click()}>{upload.isPending ? t('uploading') : t('addAttachments')}</button>
          {s.attachments.length > 0 ? <ul className="nl-appt-files">{s.attachments.map(a => <li key={a.id}><span>{a.fileName}</span><button type="button" className="nl-appt-x btn btn-ghost" aria-label={t('removeAttachment', { name: a.fileName })} onClick={() => set({ attachments: s.attachments.filter(x => x.id !== a.id) })}>×</button></li>)}</ul> : null}
          {uploadErr ? <div role="alert" className="nl-error">{uploadErr}</div> : null}
        </div>
      </FormGrid>
      {tried && count > 0 ? <Alert tone="error">{t('toFix', { n: count })}</Alert> : null}
      {sendError ? <Alert tone="error">{t('sendError')}</Alert> : null}
      <div className="nl-appt-send">
        <button type="button" className="btn btn-primary" disabled={pending} onClick={submit}>{revising ? t('sendRevision', { total: f.money(tot.total) }) : t('sendQuote', { total: f.money(tot.total) })}</button>
        <button type="button" className="btn btn-ghost" onClick={onCancel}>{t('cancel')}</button>
        <span className="nl-small nl-muted">{t('sendNote')}</span>
      </div>
    </div>
  );
}
