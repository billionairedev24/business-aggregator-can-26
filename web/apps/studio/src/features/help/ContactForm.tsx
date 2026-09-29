import { useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { FilePdf, Image, X } from '@phosphor-icons/react';
import { Alert, Button, Chip, Field, Select, TextArea, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { uploadAttachment, type Attachment } from '../messages/api';
import { CASE_TOPICS, caseFormErrors, CHANNELS, FILE_ACCEPT, fileProblem, LIMITS, MSG, useMessageT, type CaseFormValues, type CaseTopic, type Channel } from '../messages/validation';
import { relatedQuery, useOpenCase, type CaseSummary } from './api';
import { useSlaNote } from './format';
import { useHelpT } from './messages';

const SERVER_FIELD: Record<string, keyof CaseFormValues> = { topic: 'topic', body: 'body', channel: 'channel', refType: 'related', refId: 'related', refLabel: 'related', attachmentIds: 'attachments' };

export interface ContactFormProps {
  merchantId: string;
  initialTopic?: string;
  initialChannel?: Channel;
  onSent: (created: CaseSummary, form: { topic: string; urgent: boolean }) => void;
}

/**
 * Contact support: topic, related record, description, attachments, channel, urgent flag. The SLA line follows the
 * urgent flag and the tier. Errors show after touch or submit, with the "N things need attention." summary.
 */
export function ContactForm({ merchantId, initialTopic, initialChannel = 'chat', onSent }: ContactFormProps) {
  const t = useHelpT();
  const mt = useMessageT();
  const { locale, setLocale } = useLocale();
  const sla = useSlaNote();
  const related = useQuery(relatedQuery(merchantId, locale));
  const open = useOpenCase(merchantId);
  const fileInput = useRef<HTMLInputElement>(null);
  const [values, setValues] = useState<CaseFormValues>({
    topic: (CASE_TOPICS as readonly string[]).includes(initialTopic ?? '') ? (initialTopic as CaseTopic) : '',
    related: '', body: '', channel: initialChannel, urgent: false, attachments: [],
  });
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [failed, setFailed] = useState(false);
  const [uploading, setUploading] = useState(0);
  const [fileError, setFileError] = useState<string>();

  const local = caseFormErrors(values);
  const visible = (k: keyof CaseFormValues) => server[k] ?? (submitted || touched[k] ? local[k] : undefined);
  const errors = { topic: visible('topic'), related: visible('related'), body: visible('body'), channel: visible('channel'), attachments: visible('attachments') ?? fileError };
  const count = Object.values(errors).filter(Boolean).length;
  const set = <K extends keyof CaseFormValues>(k: K, v: CaseFormValues[K]) => { setValues(s => ({ ...s, [k]: v })); setServer(({ [k]: _, ...rest }) => rest); setFailed(false); };

  async function addFiles(list: FileList | null) {
    if (!list) return;
    setFileError(undefined);
    const picked = Array.from(list);
    if (values.attachments.length + picked.length > LIMITS.files) { setFileError(MSG.TOO_MANY_FILES); return; }
    for (const file of picked) {
      const problem = fileProblem(file);
      if (problem) { setFileError(problem); continue; }
      setUploading(n => n + 1);
      try {
        const a: Attachment = await uploadAttachment(merchantId, file);
        setValues(s => ({ ...s, attachments: [...s.attachments, a] }));
      } catch (e) {
        setFileError(e instanceof ValidationError ? e.errors[0]?.message : t('sendError'));
      } finally {
        setUploading(n => n - 1);
      }
    }
    if (fileInput.current) fileInput.current.value = '';
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setSubmitted(true);
    setFailed(false);
    if (Object.keys(caseFormErrors(values)).length || uploading) return;
    const ref = related.data?.find(r => `${r.type}:${r.id}` === values.related) ?? null;
    try {
      const created = await open.mutateAsync({ topic: values.topic, related: ref, body: values.body.trim(), attachments: values.attachments, channel: values.channel, urgent: values.urgent });
      onSent(created, { topic: values.topic, urgent: values.urgent });
    } catch (err) {
      if (err instanceof ValidationError) {
        const mapped: Record<string, string> = {};
        for (const x of err.errors) { const k = SERVER_FIELD[x.field] ?? 'body'; mapped[k] ??= x.message; }
        setServer(mapped);
      } else setFailed(true);
    }
  }

  return (
    <form className="nl-help-form" onSubmit={e => void submit(e)} noValidate>
      {submitted && count > 0 ? <Alert tone="error">{t('attention', { count })}</Alert> : null}
      {failed ? <Alert tone="error">{t('sendError')}</Alert> : null}
      <fieldset className="nl-help-fieldset">
        <legend className="nl-label">{t('topic')}</legend>
        <div className="nl-help-chips">
          {CASE_TOPICS.map(o => <Chip key={o} selected={values.topic === o} onClick={() => { set('topic', o); setTouched(x => ({ ...x, topic: true })); }}>{t(`topic_${o}`)}</Chip>)}
        </div>
        {errors.topic ? <div role="alert" className="nl-error">{mt(errors.topic)}</div> : null}
      </fieldset>
      <Field label={t('relatedTo')} error={mt(errors.related)}>
        <Select value={values.related} placeholder={t('none')} onChange={e => set('related', e.target.value)}
          options={(related.data ?? []).map(r => ({ value: `${r.type}:${r.id}`, label: r.label }))} />
      </Field>
      <Field label={t('whatsHappening')} error={mt(errors.body)}>
        <TextArea className="nl-help-textarea" value={values.body} placeholder={t('whatsHappeningPlaceholder')}
          onChange={e => set('body', e.target.value)} onBlur={() => setTouched(x => ({ ...x, body: true }))} />
      </Field>
      <div className="nl-field">
        <span className="nl-label" id="nl-help-att">{t('attachments')}</span>
        <div className="nl-help-tiles" aria-labelledby="nl-help-att">
          {values.attachments.map(a => (
            <span key={a.id} className="nl-help-tile nl-help-tile-file">
              {a.contentType === 'application/pdf' ? <FilePdf size={20} aria-hidden /> : <Image size={20} aria-hidden />}
              <span className="nl-help-tile-name">{a.fileName}</span>
              <button type="button" aria-label={t('removeFile', { name: a.fileName })} onClick={() => set('attachments', values.attachments.filter(x => x.id !== a.id))}><X size={14} aria-hidden /></button>
            </span>
          ))}
          {uploading > 0 ? <span className="nl-help-tile" aria-live="polite">{t('uploading')}</span> : null}
          {values.attachments.length < LIMITS.files ? (
            <button type="button" className="nl-help-tile nl-help-tile-add" aria-label={t('addLabel')} onClick={() => fileInput.current?.click()}>{t('add')}</button>
          ) : null}
          <input ref={fileInput} type="file" hidden multiple accept={FILE_ACCEPT} data-testid="case-file" onChange={e => void addFiles(e.target.files)} />
        </div>
        {errors.attachments ? <div role="alert" className="nl-error">{mt(errors.attachments)}</div> : null}
      </div>
      <fieldset className="nl-help-fieldset">
        <legend className="nl-label">{t('reach')}</legend>
        <div className="nl-help-chips">
          {CHANNELS.map(c => <Chip key={c} selected={values.channel === c} onClick={() => set('channel', c)}>{t(`chan_${c}`)}</Chip>)}
        </div>
        {errors.channel ? <div role="alert" className="nl-error">{mt(errors.channel)}</div> : null}
      </fieldset>
      <button type="button" role="checkbox" aria-checked={values.urgent} className="nl-option nl-help-urgent" onClick={() => set('urgent', !values.urgent)}>
        <span className="nl-help-urgent-box" data-on={values.urgent || undefined} aria-hidden />
        <span className="nl-help-urgent-text">{t('urgent')}</span>
      </button>
      <p className="nl-help-note">{sla(values.urgent)} {t('context')}</p>
      <div className="nl-help-form-actions">
        <Button type="submit" disabled={open.isPending || uploading > 0}>{t('send')}</Button>
        <Button type="button" variant="ghost" lang={locale === 'en' ? 'fr' : 'en'} onClick={() => setLocale(locale === 'en' ? 'fr' : 'en')}>{t('switchLang')}</Button>
      </div>
    </form>
  );
}
