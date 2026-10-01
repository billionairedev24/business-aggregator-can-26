import { useId, useRef, useState, type FormEvent } from 'react';
import { FilePdf, Image, Paperclip, X } from '@phosphor-icons/react';
import { Button } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { uploadAttachment, type Attachment, type QuickReply } from './api';
import { useMessagesT } from './messages';
import { draftProblem, FILE_ACCEPT, fileProblem, LIMITS, MSG, useMessageT } from './validation';
import { ReplySuggestions } from '../writing/WritingHelp';

export interface ComposerSend { body: string; attachments: Attachment[]; templateKey: string | null }

export interface ComposerProps {
  merchantId: string;
  /** Accessible name of the text field ("Reply to Amara Osei"). */
  label: string;
  quickReplies?: readonly QuickReply[];
  /** "icon" = paperclip button (Messages); "text" = secondary "Attach" button (help case, as in the design). */
  attach?: 'icon' | 'text';
  attachText?: string;
  sendVariant?: 'primary' | 'secondary';
  maxLength?: number;
  /** S-131: the thread to draft AI reply suggestions for (Messages only); a pick only fills the box. */
  replySuggestionsFor?: string;
  onSend: (draft: ComposerSend) => Promise<unknown>;
}

/**
 * Reply box shared by Messages and help-case conversations: quick-reply tags fill the field, files upload as soon as
 * they're picked (JPG, PNG, HEIC, PDF ≤ 10 MB, up to 5), validation shows after a send attempt, the draft survives a
 * failed send.
 */
export function Composer({ merchantId, label, quickReplies = [], attach = 'icon', attachText, sendVariant = 'primary', maxLength = LIMITS.message, replySuggestionsFor, onSend }: ComposerProps) {
  const t = useMessagesT();
  const mt = useMessageT();
  const errId = useId();
  const fileInput = useRef<HTMLInputElement>(null);
  const [body, setBody] = useState('');
  const [template, setTemplate] = useState<QuickReply | null>(null);
  const [files, setFiles] = useState<Attachment[]>([]);
  const [uploading, setUploading] = useState<string[]>([]);
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState<string>();
  const [sending, setSending] = useState(false);

  const localProblem = (b: string, f: Attachment[]) => {
    if (b.length > maxLength) return maxLength === LIMITS.message ? MSG.MESSAGE_TOO_LONG : MSG.CASE_BODY_TOO_LONG;
    return draftProblem(b.slice(0, LIMITS.message), f);
  };
  const error = serverError ?? (submitted ? localProblem(body, files) : undefined);

  async function pickFiles(list: FileList | null) {
    if (!list) return;
    setServerError(undefined);
    const picked = Array.from(list);
    if (files.length + picked.length > LIMITS.files) { setServerError(MSG.TOO_MANY_FILES); return; }
    for (const file of picked) {
      const problem = fileProblem(file);
      if (problem) { setServerError(problem); continue; }
      setUploading(u => [...u, file.name]);
      try {
        const uploaded = await uploadAttachment(merchantId, file);
        setFiles(f => [...f, uploaded]);
      } catch (e) {
        setServerError(e instanceof ValidationError ? e.errors[0]?.message : t('sendError'));
      } finally {
        setUploading(u => u.filter(n => n !== file.name));
      }
    }
    if (fileInput.current) fileInput.current.value = '';
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setSubmitted(true);
    setServerError(undefined);
    if (localProblem(body, files) || uploading.length) return;
    setSending(true);
    try {
      await onSend({ body: body.trim(), attachments: files, templateKey: template && template.text === body.trim() ? template.key : null });
      setBody(''); setFiles([]); setTemplate(null); setSubmitted(false);
    } catch (err) {
      setServerError(err instanceof ValidationError ? err.errors[0]?.message ?? t('sendError') : t('sendError'));
    } finally {
      setSending(false);
    }
  }

  return (
    <form className="nl-msg-composer" onSubmit={e => void submit(e)} noValidate>
      {replySuggestionsFor ? <ReplySuggestions key={replySuggestionsFor} merchantId={merchantId} threadId={replySuggestionsFor} onPick={text => { setBody(text); setTemplate(null); setServerError(undefined); }} /> : null}
      {quickReplies.length > 0 && (
        <div className="nl-msg-quick" role="group" aria-label={t('quickReplies')}>
          {quickReplies.map(q => (
            <button key={q.key} type="button" className="tag tag-outline nl-msg-quick-tag" onClick={() => { setBody(q.text); setTemplate(q); setServerError(undefined); }}>{q.text}</button>
          ))}
        </div>
      )}
      <div className="nl-msg-compose-row">
        <input
          className="input" value={body} placeholder={t('reply')} aria-label={label} maxLength={maxLength + 200}
          aria-invalid={error ? true : undefined} aria-describedby={error ? errId : undefined}
          onChange={e => { setBody(e.target.value); setServerError(undefined); }}
        />
        <input ref={fileInput} type="file" hidden multiple accept={FILE_ACCEPT} onChange={e => void pickFiles(e.target.files)} data-testid="composer-file" />
        {attach === 'icon'
          ? <Button type="button" variant="ghost" icon aria-label={t('attach')} title={t('attach')} onClick={() => fileInput.current?.click()}><Paperclip size={20} weight="duotone" aria-hidden /></Button>
          : <Button type="button" variant="secondary" onClick={() => fileInput.current?.click()}>{attachText ?? t('attach')}</Button>}
        <Button type="submit" variant={sendVariant} disabled={sending || uploading.length > 0}>{sending ? t('sending') : t('send')}</Button>
      </div>
      {(files.length > 0 || uploading.length > 0) && (
        <ul className="nl-msg-files">
          {files.map(f => (
            <li key={f.id} className="nl-msg-file">
              {f.contentType === 'application/pdf' ? <FilePdf size={16} aria-hidden /> : <Image size={16} aria-hidden />}
              <span className="nl-msg-file-name">{f.fileName}</span>
              <button type="button" aria-label={t('removeFile', { name: f.fileName })} onClick={() => setFiles(fs => fs.filter(x => x.id !== f.id))}><X size={14} aria-hidden /></button>
            </li>
          ))}
          {uploading.map(n => <li key={n} className="nl-msg-file" aria-live="polite">{t('uploading', { name: n })}</li>)}
        </ul>
      )}
      {error ? <div id={errId} role="alert" className="nl-error">{mt(error)}</div> : null}
    </form>
  );
}
