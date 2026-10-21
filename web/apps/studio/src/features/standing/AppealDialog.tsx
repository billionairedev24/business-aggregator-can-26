import { useState } from 'react';
import { Alert, Button, Dialog, Field, FileButton, TextArea } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useLocalizeMessage } from '../../lib/validation';
import { useFileAppeal, type Appeal } from './api';
import { useStandingT } from './messages';
import { AppealForm, FILE_TYPES, FR, firstErrors } from './validation';

/**
 * Appeal a consequence or a dispute decision (S-156): a statement and up to 5 photos or PDFs. Filed once; the files go
 * up after the appeal is filed, so a refused file leaves the appeal sent and says which file.
 */
export function AppealDialog({ merchantId, subjectType, subjectId, what, onClose, onSent }: {
  merchantId: string; subjectType: 'dispute' | 'consequence'; subjectId: string; what: string; onClose: () => void; onSent: (a: Appeal) => void;
}) {
  const t = useStandingT();
  const localize = useLocalizeMessage(FR);
  const file = useFileAppeal(merchantId);
  const [statement, setStatement] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [submitted, setSubmitted] = useState(false);
  const [problem, setProblem] = useState<string>();
  const client = submitted ? firstErrors(AppealForm.safeParse({ statement, files })) : {};
  const server = file.error instanceof ValidationError ? file.error.byField() : {};
  const statementError = localize(client.statement ?? server.statement);
  const filesError = localize(client.files ?? server.file);

  const send = async () => {
    setSubmitted(true);
    setProblem(undefined);
    if (!AppealForm.safeParse({ statement, files }).success) return;
    try {
      onSent(await file.mutateAsync({ subjectType, subjectId, statement: statement.trim(), files }));
    } catch (e) {
      if (!(e instanceof ValidationError)) setProblem(e instanceof ApiError && e.body && typeof e.body === 'object' && 'detail' in e.body ? String((e.body as { detail: unknown }).detail) : t('actionError'));
    }
  };

  return (
    <Dialog open onClose={onClose} title={t('appealTitle', { what })} width={560}
      actions={<>
        <Button variant="secondary" onClick={onClose}>{t('cancel')}</Button>
        <Button onClick={() => void send()} disabled={file.isPending} aria-busy={file.isPending}>{file.isPending ? t('sending') : t('send')}</Button>
      </>}>
      <Field label={t('statement')} hint={t('statementHint')} error={statementError}>
        <TextArea value={statement} rows={6} maxLength={2100} onChange={e => setStatement(e.target.value)} />
      </Field>
      <div className="nl-field" role="group" aria-labelledby="nl-st-files-label" aria-describedby="nl-st-files-hint">
        <span className="nl-label" id="nl-st-files-label">{t('files')}</span>
        <ul className="nl-st-files">
          {files.map((f, i) => (
            <li key={`${f.name}-${i}`} className="nl-st-file">
              <span>{f.name}</span>
              <Button variant="ghost" onClick={() => setFiles(fs => fs.filter((_, j) => j !== i))} aria-label={t('remove', { name: f.name })}>×</Button>
            </li>
          ))}
        </ul>
        <FileButton accept={FILE_TYPES.join(',')} onFile={f => setFiles(fs => [...fs, f])} disabled={files.length >= 5}>{t('addFiles')}</FileButton>
        {filesError ? <div role="alert" className="nl-error">{filesError}</div> : null}
        <div id="nl-st-files-hint" className="nl-hint">{t('filesHint')}</div>
      </div>
      {problem ? <Alert tone="error">{problem}</Alert> : null}
    </Dialog>
  );
}
