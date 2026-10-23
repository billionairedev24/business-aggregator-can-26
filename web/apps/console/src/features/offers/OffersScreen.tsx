import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, BoHeader, Button, Dialog, ErrorState, Field, FormGrid, PageSkeleton, Segmented, Select, TextArea, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { EMPTY, offersQuery, previewOffer, testOffer, useSendOffer, type Campaign, type Draft, type Overview, type Preview } from './api';
import { useOffersT, type OffersT } from './messages';
import './offers.css';

/**
 * S-157, the console's offers (admins, finance): write an offer in both languages, preview it as customers get it
 * (email with the CASL footer, the text with its sender, opt-out link and STOP), send yourself a test, see how many
 * customers of the market agreed to that channel, and send it — the worker sends it a few a minute and checks each
 * person's consent again first. Below, the offers sent and how far each got.
 */
export function OffersScreen() {
  const t = useOffersT();
  const q = useQuery(offersQuery);
  const head = <BoHeader title={t('title')} sub={t('sub')} />;
  if (q.isPending) return <div className="bo-page">{head}<PageSkeleton kpis={0} rows={6} /></div>;
  if (q.isError) return <div className="bo-page">{head}<ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;
  return (
    <div className="bo-page">
      {head}
      <Composer overview={q.data} />
      <History campaigns={q.data.campaigns} />
    </div>
  );
}

function Composer({ overview }: { overview: Overview }) {
  const t = useOffersT();
  const { date } = useFormatters();
  const { can } = useGrant();
  const [draft, setDraft] = useState<Draft>({ ...EMPTY, market: overview.markets[0] ?? '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState<'preview' | 'test' | null>(null);
  const [notice, setNotice] = useState<string>();
  const [failure, setFailure] = useState<string>();
  const [confirming, setConfirming] = useState(false);
  const send = useSendOffer();
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => { setDraft(d => ({ ...d, [k]: v })); setErrors(e => ({ ...e, [k]: '' })); setPreview(null); setNotice(undefined); };
  const channelName = (c: Draft['channel']) => t(c).toLowerCase();
  const count = preview ? (draft.channel === 'email' ? preview.audience.email : preview.audience.sms) : 0;

  const run = async <T,>(what: 'preview' | 'test', fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(what); setFailure(undefined); setNotice(undefined);
    try { return await fn(); } catch (e) {
      if (e instanceof ValidationError) setErrors(e.byField()); else setFailure(t('error'));
      return undefined;
    } finally { setBusy(null); }
  };
  const doPreview = async (e: FormEvent) => { e.preventDefault(); const p = await run('preview', () => previewOffer(draft)); if (p) setPreview(p); };
  const doTest = async () => { if ((await run('test', () => testOffer(draft))) !== undefined) setNotice(t('tested', { channel: channelName(draft.channel) })); };
  const doSend = async () => {
    setConfirming(false); setFailure(undefined);
    try {
      const c = await send.mutateAsync(draft);
      setNotice(t('sent', { count: c.recipients, time: date(c.finishesAt, 'dateTime') }));
      setPreview(null);
    } catch (e) { if (e instanceof ValidationError) setErrors(e.byField()); else setFailure(t('error')); }
  };

  return (
    <section className="bo-card nl-of-compose" aria-labelledby="nl-of-new">
      <h2 id="nl-of-new" className="bo-card-title">{t('newTitle')}</h2>
      <form noValidate onSubmit={e => void doPreview(e)}>
        <FormGrid min={220}>
          <Field label={t('market')} error={errors.market}>
            <Select value={draft.market} onChange={e => set('market', e.target.value)} placeholder={t('marketPh')} options={overview.markets} />
          </Field>
          <Field label={t('channel')} error={errors.channel}>
            <Segmented name="nl-of-channel" aria-label={t('channel')} value={draft.channel} onChange={v => set('channel', v)}
              options={[{ value: 'email', label: t('email') }, { value: 'sms', label: t('sms') }]} />
          </Field>
        </FormGrid>
        <FormGrid min={260}>
          <Field label={t('titleEn')} error={errors.titleEn}><TextInput value={draft.titleEn} maxLength={80} onChange={e => set('titleEn', e.target.value)} /></Field>
          <Field label={t('titleFr')} error={errors.titleFr}><TextInput lang="fr-CA" value={draft.titleFr} maxLength={80} onChange={e => set('titleFr', e.target.value)} /></Field>
          <Field label={t('bodyEn')} error={errors.bodyEn}><TextArea rows={3} value={draft.bodyEn} maxLength={600} onChange={e => set('bodyEn', e.target.value)} /></Field>
          <Field label={t('bodyFr')} error={errors.bodyFr}><TextArea lang="fr-CA" rows={3} value={draft.bodyFr} maxLength={600} onChange={e => set('bodyFr', e.target.value)} /></Field>
          <Field label={t('path')} note={t('pathNote')} error={errors.path}><TextInput value={draft.path} onChange={e => set('path', e.target.value)} /></Field>
        </FormGrid>
        <div className="nl-of-actions">
          <Button type="submit" variant="secondary" disabled={busy !== null} aria-busy={busy === 'preview' || undefined}>{busy === 'preview' ? t('previewing') : t('preview')}</Button>
          {can('offers') ? <Button type="button" variant="tertiary" disabled={busy !== null} aria-busy={busy === 'test' || undefined} onClick={() => void doTest()}>{busy === 'test' ? t('testing') : t('test')}</Button> : null}
        </div>
      </form>
      {!can('offers') ? <p className="bo-muted">{t('noAction')}</p> : null}
      {failure ? <Alert tone="error">{failure}</Alert> : null}
      {notice ? <p role="status" className="nl-of-notice">{notice}</p> : null}
      {preview ? (
        <div className="nl-of-preview" aria-labelledby="nl-of-preview">
          <h3 id="nl-of-preview" className="nl-of-h3">{t('previewTitle')}</h3>
          <p className="nl-of-audience">{t('audience', { count, market: preview.audience.market, channel: channelName(draft.channel) })}</p>
          <div className="nl-of-langs">
            <Rendered t={t} lang={t('english')} r={preview.en} channel={draft.channel} />
            <Rendered t={t} lang={t('french')} r={preview.fr} channel={draft.channel} />
          </div>
          {can('offers') && count > 0 ? <Button onClick={() => setConfirming(true)} disabled={send.isPending}>{t('send', { count })}</Button> : null}
        </div>
      ) : null}
      <Dialog open={confirming} onClose={() => setConfirming(false)} title={t('confirmTitle')}
        actions={<><Button variant="tertiary" onClick={() => setConfirming(false)}>{t('cancel')}</Button><Button onClick={() => void doSend()}>{t('confirm')}</Button></>}>
        <p>{t('confirmBody', { count, market: draft.market, channel: channelName(draft.channel), perMinute: overview.perMinute })}</p>
      </Dialog>
    </section>
  );
}

function Rendered({ t, lang, r, channel }: { t: OffersT; lang: string; r: Preview['en']; channel: Draft['channel'] }) {
  return (
    <article className="nl-of-rendered" aria-label={lang}>
      <h4 className="nl-of-lang">{lang}</h4>
      {channel === 'email' ? (
        <>
          <p className="nl-of-subject">{t('subject', { subject: r.subject })}</p>
          <pre className="nl-of-text">{r.text}</pre>
        </>
      ) : (
        <>
          <pre className="nl-of-text">{r.sms}</pre>
          <p className="bo-muted">{t('segments', { n: r.smsSegments })}</p>
        </>
      )}
    </article>
  );
}

function History({ campaigns }: { campaigns: Campaign[] }) {
  const t = useOffersT();
  const { date } = useFormatters();
  return (
    <section className="bo-card" aria-labelledby="nl-of-history">
      <h2 id="nl-of-history" className="bo-card-title">{t('historyTitle')}</h2>
      {campaigns.length === 0 ? <p className="bo-muted">{t('none')}</p> : (
        <div className="nl-of-tablewrap">
          <table className="table">
            <thead><tr>
              <th scope="col">{t('colSent')}</th><th scope="col">{t('colMarket')}</th><th scope="col">{t('colChannel')}</th><th scope="col">{t('colTitle')}</th>
              <th scope="col">{t('colRecipients')}</th><th scope="col">{t('colPending')}</th><th scope="col">{t('colGivenUp')}</th>
            </tr></thead>
            <tbody>
              {campaigns.map(c => (
                <tr key={c.id}>
                  <td>{date(c.sentAt, 'dateTime')}</td><td>{c.market}</td><td>{t(c.channel)}</td><td>{c.titleEn}</td>
                  <td>{c.recipients}</td><td>{c.pending}</td><td>{c.givenUp}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
