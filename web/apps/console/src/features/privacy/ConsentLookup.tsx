import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, Field, TextInput, useFormatters } from '@northline/ui';
import { useGrant } from '../shell/grant';
import { consentLookupQuery, grantedNow, useWithdrawConsent } from './consentApi';
import { useConsentT } from './consentMessages';

type Key = Parameters<ReturnType<typeof useConsentT>>[0];

/**
 * S-108: CASL proof of consent, under the privacy queue — every grant and withdrawal of a person (by account id, or
 * by an email or phone they consented with), and, for the `privacy` grant, a withdrawal recorded on their behalf.
 */
export function ConsentLookup() {
  const t = useConsentT();
  const { date } = useFormatters();
  const { can } = useGrant();
  const [draft, setDraft] = useState('');
  const [q, setQ] = useState('');
  const records = useQuery(consentLookupQuery(q));
  const withdraw = useWithdrawConsent();
  const submit = (e: FormEvent) => { e.preventDefault(); setQ(draft.trim()); };
  const label = (k: string) => t(k as Key);
  return (
    <section aria-labelledby="nl-consent-title" className="nl-q-section">
      <h2 id="nl-consent-title" className="nl-q-title">{t('title')}</h2>
      <p className="nl-q-lede">{t('lede')}</p>
      <form onSubmit={submit} className="nl-q-actions" role="search">
        <Field label={t('query')}><TextInput value={draft} onChange={e => setDraft(e.target.value)} /></Field>
        <Button type="submit" variant="secondary">{t('search')}</Button>
      </form>
      {q === '' ? null : records.isPending ? null : records.isError ? <ErrorState message={t('loadError')} onRetry={() => void records.refetch()} />
        : records.data.length === 0 ? <p>{t('empty')}</p> : (
          <>
            {can('privacy') ? grantedNow(records.data).map(g => (
              <Button key={`${g.userId}|${g.category}`} type="button" variant="ghost" disabled={withdraw.isPending}
                onClick={() => withdraw.mutate(g)}>{t('withdraw', { what: label(`cat_${g.category}`), account: g.userId })}</Button>
            )) : null}
            {withdraw.isSuccess ? <p role="status">{t('withdrawn')}</p> : null}
            {withdraw.isError ? <p role="alert" className="nl-error">{t('withdrawError')}</p> : null}
            <div className="nl-q-tablewrap">
              <table className="table">
                <thead><tr>
                  <th scope="col">{t('colWhen')}</th><th scope="col">{t('colAccount')}</th><th scope="col">{t('colWhat')}</th><th scope="col">{t('colAction')}</th>
                  <th scope="col">{t('colWhere')}</th><th scope="col">{t('colWording')}</th><th scope="col">{t('colNetwork')}</th>
                </tr></thead>
                <tbody>
                  {records.data.map(r => (
                    <tr key={r.id}>
                      <td>{date(r.at)}</td><td>{r.userId}</td><td>{label(`cat_${r.category}`)}</td><td>{label(`act_${r.action}`)}</td>
                      <td>{label(`src_${r.source}`)}</td><td>{r.wordingVersion ? `${r.wordingVersion}${r.language ? ` · ${r.language}` : ''}` : '—'}</td><td>{r.ipPrefix ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
    </section>
  );
}
