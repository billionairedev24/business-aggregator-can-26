import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Dialog, ErrorState, Field, Select, Skeleton, TextArea, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '@northline/client';
import { stepUpWithCode, stepUpWithPasskey } from '../cart/stepUp';
import {
  correctableQuery, downloadLink, privacyRequestsQuery, problemCode, useOpenRequest, useResendCode, useVerify, useWithdraw,
  type PrivacyRequest, type RequestType,
} from './privacyApi';
import { usePrivacyT, type PrivacyKey, type PrivacyT } from './privacyMessages';

const OPEN = ['awaiting_verification', 'verified', 'in_progress'];

/**
 * "Your data" (S-105): a copy of one's data, a correction, or deleting the account — each a privacy request handled
 * under the person's province's law, with its deadline. A request is confirmed with the code texted to the verified
 * mobile, or with the passkey / authenticator (northline-auth's step-up proof).
 */
export function YourData() {
  const t = usePrivacyT();
  const requests = useQuery(privacyRequestsQuery);
  const open = useOpenRequest();
  const [verifying, setVerifying] = useState<PrivacyRequest>();
  const [dialog, setDialog] = useState<'delete' | 'correct'>();
  const [error, setError] = useState<string>();

  const ask = (type: RequestType, extra: { corrections?: { field: string; value: string }[]; note?: string } = {}) => {
    setError(undefined);
    open.mutate({ type, ...extra }, {
      onSuccess: r => { setDialog(undefined); if (r.state === 'awaiting_verification') setVerifying(r); },
      onError: e => setError(problemCode(e) === 'request_open' ? t('alreadyOpen') : e instanceof ValidationError ? e.message : t('error')),
    });
  };
  const has = (type: RequestType) => (requests.data ?? []).some(r => r.type === type && OPEN.includes(r.state));

  return (
    <section id="your-data" aria-labelledby="your-data-title" className="nl-your-data">
      <h2 id="your-data-title" className="nl-acct-h2">{t('title')}</h2>
      <p className="nl-acct-lede">{t('lede')}</p>
      <div className="nl-acct-actions">
        <Button type="button" variant="secondary" disabled={open.isPending || has('access')} onClick={() => ask('access')}>{t('download')}</Button>
        <Button type="button" variant="ghost" disabled={has('correction')} onClick={() => setDialog('correct')}>{t('correct')}</Button>
        <Button type="button" variant="ghost" className="nl-danger" disabled={has('erasure')} onClick={() => setDialog('delete')}>{t('delete')}</Button>
      </div>
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
      {requests.isPending ? <Skeleton height={44} />
        : requests.isError ? <ErrorState message={t('loadError')} onRetry={() => void requests.refetch()} />
          : requests.data.length ? (
            <ul className="nl-acct-list">
              {requests.data.map(r => <RequestRow key={r.id} request={r} t={t} onVerify={() => setVerifying(r)} />)}
            </ul>
          ) : null}
      {dialog === 'delete' ? (
        <Dialog open onClose={() => setDialog(undefined)} title={t('deleteTitle')} role="alertdialog"
          actions={<>
            <Button type="button" variant="ghost" onClick={() => setDialog(undefined)}>{t('cancel')}</Button>
            <Button type="button" className="nl-danger-btn" disabled={open.isPending} aria-busy={open.isPending} onClick={() => ask('erasure')}>{t('deleteConfirm')}</Button>
          </>}>
          <p>{t('deleteBody')}</p>
          {error ? <p className="nl-error" role="alert">{error}</p> : null}
        </Dialog>
      ) : null}
      {dialog === 'correct' ? <CorrectionDialog t={t} busy={open.isPending} error={error} onClose={() => setDialog(undefined)}
        onSend={(corrections, note) => ask('correction', { corrections, note })} /> : null}
      {verifying ? <VerifyDialog request={verifying} t={t} onClose={() => setVerifying(undefined)} /> : null}
    </section>
  );
}

function RequestRow({ request: r, t, onVerify }: { request: PrivacyRequest; t: PrivacyT; onVerify: () => void }) {
  const { date } = useFormatters();
  const withdraw = useWithdraw();
  const [busy, setBusy] = useState(false);
  const [links, setLinks] = useState<{ url: string; summaryUrl: string }>();
  const [failed, setFailed] = useState(false);
  const due = r.extendedTo ?? r.dueAt;
  /** A fresh link each time (it lives minutes): the bundle stays encrypted until it is fetched through it. */
  const getLinks = async () => {
    setBusy(true); setFailed(false);
    try { setLinks(await downloadLink(r.id)); } catch { setFailed(true); } finally { setBusy(false); }
  };
  const status = (() => {
    switch (r.state) {
      case 'awaiting_verification': return t('s_awaiting_verification');
      case 'rejected': return t('s_rejected', { reason: t(`r_${r.decision ?? 'frivolous'}` as PrivacyKey), authority: r.law.authority });
      case 'withdrawn': return t('s_withdrawn');
      case 'completed':
        if (r.type === 'access') return r.export?.ready ? t('s_access_ready', { date: date(r.export.expiresAt ?? due, 'long') }) : t('s_access_expired');
        if (r.type === 'correction') return t('s_correction_completed', { date: date(r.completedAt ?? due, 'long') });
        return t('s_erasure_completed');
      default:
        if (r.type === 'erasure' && r.state === 'verified') return t('s_erasure_verified', { date: date(r.scheduledFor ?? due, 'long') });
        return t(`s_${r.type}_${r.state}` as PrivacyKey);
    }
  })();
  return (
    <li className="nl-acct-row nl-privacy-row">
      <span>
        <strong>{t(`type_${r.type}`)}</strong> · {r.reference}
        <br /><span className="nl-small">{status}</span>
        {OPEN.includes(r.state) ? <><br /><span className="nl-small nl-muted">{t('due', { date: date(due, 'long'), law: r.law.shortName })}</span></> : null}
        {r.state === 'rejected' ? <> <a className="nl-small" href={r.law.authorityUrl} target="_blank" rel="noreferrer">{r.law.authority}</a></> : null}
      </span>
      <span className="nl-acct-actions">
        {r.state === 'awaiting_verification' ? <Button type="button" variant="secondary" onClick={onVerify}>{t('confirmIt')}</Button> : null}
        {r.type === 'erasure' && (r.state === 'verified' || r.state === 'awaiting_verification')
          ? <Button type="button" variant="ghost" disabled={withdraw.isPending} onClick={() => withdraw.mutate(r.id)}>{t('cancelDeletion')}</Button> : null}
        {r.type === 'access' && r.state === 'completed' && r.export?.ready ? (links ? <>
          <a className="btn btn-secondary" href={links.url} download>{t('getData')}</a>
          <a className="btn btn-ghost" href={links.summaryUrl} download>{t('getSummary')}</a>
        </> : <Button type="button" variant="secondary" disabled={busy} aria-busy={busy} onClick={() => void getLinks()}>{t('getLinks')}</Button>) : null}
        {failed ? <span className="nl-error" role="alert">{t('error')}</span> : null}
      </span>
    </li>
  );
}

function VerifyDialog({ request, t, onClose }: { request: PrivacyRequest; t: PrivacyT; onClose: () => void }) {
  const verify = useVerify();
  const resend = useResendCode();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string>();
  const [authenticator, setAuthenticator] = useState(!request.codeSentTo);
  const [sentTo, setSentTo] = useState(request.codeSentTo);
  const done = { onSuccess: onClose, onError: (e: Error) => setError(e instanceof ValidationError ? e.message : t('error')) };
  const confirmCode = () => {
    if (!/^\d{6}$/.test(code.trim())) { setError(t('v_code')); return; }
    setError(undefined);
    if (authenticator) {
      void stepUpWithCode(code).then(proof => verify.mutate({ id: request.id, proof }, done), () => setError(t('stepUpFailed')));
    } else {
      verify.mutate({ id: request.id, code: code.trim() }, done);
    }
  };
  const passkey = () => {
    setError(undefined);
    void stepUpWithPasskey().then(proof => verify.mutate({ id: request.id, proof }, done), () => setError(t('stepUpFailed')));
  };
  return (
    <Dialog open onClose={onClose} title={t('verifyTitle')}
      actions={<>
        <Button type="button" variant="ghost" onClick={onClose}>{t('close')}</Button>
        <Button type="button" disabled={verify.isPending} aria-busy={verify.isPending} onClick={confirmCode}>{t('confirm')}</Button>
      </>}>
      <p>{sentTo && !authenticator ? t('codeSent', { to: sentTo }) : t('noCode')}</p>
      <Field label={sentTo && !authenticator ? t('code') : t('authenticatorCode')} error={error}>
        <TextInput value={code} onChange={e => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} />
      </Field>
      <div className="nl-acct-actions">
        {sentTo && !authenticator ? <Button type="button" variant="ghost" disabled={resend.isPending}
          onClick={() => resend.mutate(request.id, { onSuccess: r => setSentTo(r.codeSentTo), onError: () => setError(t('error')) })}>{t('newCode')}</Button> : null}
        <Button type="button" variant="ghost" onClick={passkey}>{t('usePasskey')}</Button>
        {sentTo && !authenticator ? <Button type="button" variant="ghost" onClick={() => setAuthenticator(true)}>{t('useAuthenticator')}</Button> : null}
      </div>
    </Dialog>
  );
}

function CorrectionDialog({ t, busy, error, onClose, onSend }: {
  t: PrivacyT; busy: boolean; error?: string; onClose: () => void; onSend: (c: { field: string; value: string }[], note?: string) => void;
}) {
  const fields = useQuery(correctableQuery);
  const [field, setField] = useState('');
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  const [invalid, setInvalid] = useState<string>();
  const options = (fields.data ?? []).map(f => ({ value: f, label: t(`f_${f}` as PrivacyKey) }));
  const send = () => {
    const chosen = field || options[0]?.value;
    if (!chosen || !value.trim() || value.trim().length > 200) { setInvalid(t('v_value')); return; }
    setInvalid(undefined);
    onSend([{ field: chosen, value: value.trim() }], note.trim() || undefined);
  };
  return (
    <Dialog open onClose={onClose} title={t('correctTitle')}
      actions={<>
        <Button type="button" variant="ghost" onClick={onClose}>{t('cancel')}</Button>
        <Button type="button" disabled={busy} aria-busy={busy} onClick={send}>{t('send')}</Button>
      </>}>
      <p>{t('correctBody')}</p>
      <Field label={t('field')}><Select value={field || options[0]?.value || ''} onChange={e => setField(e.target.value)} options={options} /></Field>
      <Field label={t('value')} error={invalid}><TextInput value={value} onChange={e => setValue(e.target.value)} maxLength={200} /></Field>
      <Field label={t('note')}><TextArea value={note} onChange={e => setNote(e.target.value)} maxLength={1000} rows={3} /></Field>
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
    </Dialog>
  );
}
