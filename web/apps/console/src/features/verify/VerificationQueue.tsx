import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { CheckCircle, Question } from '@phosphor-icons/react';
import { Button, Checkbox, DataTable, Dialog, ErrorState, Field, formatDate, formatNumber, PageSkeleton, Tag, TextArea, useLocale, type DataTableAction, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useGrant } from '../shell/grant';
import { PlaceFilters, useRegionName, type PlaceFilter } from '../shell/PlaceFilters';
import { SCREEN_PATH } from '../shell/screens';
import { detailQuery, queueQuery, useDecideApplication, useDecideIdentity, useDecideRegistry, type Application, type Check, type Detail } from './api';
import { useVerifyT, type VerifyKey, type VerifyT } from './messages';
import '../shell/queues.css';

export interface VerifySearch extends PlaceFilter { application?: string }

interface Row {
  id: string; name: string; sub: string; cat: string; region: string; checks: string; risk: string; wait: string; status: string;
  app: Application;
}

/** A check as the design names it ("KYC", "AMVIC", "GST #"); regulators come from the data, never from code. */
export function checkLabel(c: Pick<Check, 'key' | 'registry'>, t: VerifyT): string {
  const kind = c.key.startsWith('licence:') ? 'licence' : c.key;
  const registry = c.registry ?? c.key.replace(/^licence:/, '');
  const key = `c_${kind}` as VerifyKey;
  const label = t(key, { registry });
  return label === key ? c.key : label;
}
const MARK: Record<Check['state'], string> = { passed: '✓', review: '✗', failed: '✗', waiting: '○' };

/**
 * The verification queue (S-79, design 03 `verify`): submitted applications with their automated checks, risk and
 * waiting time; approve or request info inline (role `verify`), and an application panel with the manual reviews
 * behind the checks (S-23 registry lookups, S-22 identity mismatches) and the decision history. Admin and trust &
 * safety open it; every other role is refused by the api and gets the denied banner from the layout.
 */
export function VerificationQueue() {
  const t = useVerifyT();
  const { locale } = useLocale();
  const search = useSearch({ strict: false }) as VerifySearch;
  const filter = { province: search.province, market: search.market };
  const navigate = useNavigate();
  const query = useQuery(queueQuery(filter));
  const { can, roleName } = useGrant();
  const regionName = useRegionName();
  const age = useAge();
  const decide = useDecideApplication();
  const [asking, setAsking] = useState<Application | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  const select = (id: string | undefined) => void navigate({ to: SCREEN_PATH.verify, search: { ...filter, application: id } as never });

  if (query.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (query.isError && !query.data) {
    return (
      <div>
        <Header filter={filter} />
        {query.error instanceof ValidationError ? <p role="alert" className="nl-q-error">{t('badFilter')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}
      </div>
    );
  }
  const data = query.data!;
  const status = (a: Application) => (a.status === 'pending' ? t('pending') : a.decision === 'approved' ? t('approved') : a.decision === 'info_requested' ? t('infoRequested') : a.status);
  const rows: Row[] = data.items.map(a => ({
    id: a.merchantId, name: a.businessName, sub: a.legalName, cat: a.categories[0] ?? '—', region: regionName(a.province),
    checks: a.checks.map(c => `${checkLabel(c, t)} ${MARK[c.state]}`).join(' · '),
    risk: t(a.risk === 'low' ? 'riskLow' : a.risk === 'medium' ? 'riskMedium' : 'riskHigh'),
    wait: a.status === 'pending' ? age(a.submittedAt) : '—', status: status(a), app: a,
  }));
  const tones = (r: Row): Partial<Record<keyof Row, DataTableTone>> => ({
    risk: r.app.risk === 'low' ? 'tag-accent' : 'tag-accent-2',
    status: r.app.status === 'pending' ? 'tag-highlight' : r.app.decision === 'approved' ? 'tag-accent' : 'tag-neutral',
  });
  const columns: DataTableColumn<Row>[] = [
    { key: 'name', label: t('colApplicant'), sub: 'sub', subLabel: t('colBusiness'), primary: true },
    { key: 'cat', label: t('colCategory'), filter: 'facet' },
    { key: 'region', label: t('colRegion'), filter: 'facet' },
    { key: 'checks', label: t('colChecks'), editable: false },
    { key: 'risk', label: t('colRisk'), type: 'tag', editable: false },
    { key: 'wait', label: t('colWaiting'), editable: false },
    { key: 'status', label: t('colDecision'), type: 'tag', editable: false },
  ];
  const pendingLabel = t('pending');
  const actions: DataTableAction<Row>[] = [
    { id: 'approve', label: t('approve'), icon: CheckCircle, perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [pendingLabel] } },
    { id: 'info', label: t('requestInfo'), icon: Question, perm: 'update', inline: true, bulk: false, when: { key: 'status', in: [pendingLabel] } },
  ];
  const onAction = async (a: DataTableAction<Row>, hit: Row[]) => {
    const app = hit[0]!.app;
    if (a.id === 'info') {
      setAsking(app);
      return false;
    }
    setNotice(null);
    try {
      await decide.mutateAsync({ id: app.merchantId, decision: 'approve' });
      setNotice({ tone: 'ok', text: t('approvedToast', { business: app.businessName }) });
    } catch (e) {
      setNotice({ tone: 'error', text: e instanceof ApiError ? e.message : String(e) });
    }
    return false;
  };
  const median = data.medianDecisionHours;
  return (
    <div>
      <Header filter={filter} />
      <h1 className="nl-q-title">{median == null
        ? t('titleNoMedian', { n: data.pending })
        : t('title', { n: data.pending, median: t('medianDays', { d: formatNumber(median / 24, locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }) })}</h1>
      <p className="nl-q-lede">{t('lede')}</p>
      {notice ? <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'nl-q-error' : 'nl-q-note'}>{notice.text}</p> : null}
      <DataTable<Row> entity={t('entity')} plural={t('plural')} columns={columns} rows={rows} rowTones={tones}
        can={{ create: false, update: can('verify'), delete: false }} roleName={roleName} actions={actions} onAction={onAction}
        emptyText={t('empty')} openLabel={t('open')} onOpen={r => select(r.id)} />
      {search.application ? <ApplicationPanel id={search.application} canDecide={can('verify')} onClose={() => select(undefined)} onAsk={setAsking} /> : null}
      {asking ? <RequestInfoDialog app={asking} onClose={() => setAsking(null)} onSent={() => { setNotice({ tone: 'ok', text: t('infoToast', { business: asking.businessName }) }); setAsking(null); }} /> : null}
    </div>
  );
}

function Header({ filter }: { filter: PlaceFilter }) {
  const t = useVerifyT();
  return (
    <div className="nl-q-top">
      <span className="nl-q-kicker">{t('kicker')}</span>
      <PlaceFilters to={SCREEN_PATH.verify} filter={filter} />
    </div>
  );
}

/** One application: checks, owners' identity, registry lookups, history, and the decisions the role may take. */
function ApplicationPanel({ id, canDecide, onClose, onAsk }: { id: string; canDecide: boolean; onClose: () => void; onAsk: (a: Application) => void }) {
  const t = useVerifyT();
  const { locale } = useLocale();
  const query = useQuery(detailQuery(id));
  const regionName = useRegionName();
  const age = useAge();
  const decide = useDecideApplication();
  const identity = useDecideIdentity();
  const registry = useDecideRegistry();
  const [error, setError] = useState<string>();
  const run = (p: Promise<unknown>) => { setError(undefined); p.catch(e => setError(e instanceof ApiError ? e.message : String(e))); };
  if (query.isPending) return <section className="nl-q-panel nl-q-gap" aria-busy="true" />;
  if (query.isError) return <section className="nl-q-panel nl-q-gap"><ErrorState message={t('loadError')} onRetry={() => void query.refetch()} /></section>;
  const d: Detail = query.data;
  const a = d.application;
  const pending = a.status === 'pending';
  return (
    <section className="nl-q-panel nl-q-gap" aria-labelledby="nl-verify-panel">
      <div className="nl-q-row">
        <div>
          <h2 id="nl-verify-panel" className="nl-q-h2">{t('panelTitle', { business: a.businessName, legal: a.legalName })}</h2>
          <div className="nl-q-note">{t('panelMeta', { type: a.type ? t(`t_${a.type}` as VerifyKey) : '—', region: [a.city, regionName(a.province)].filter(Boolean).join(', '), age: age(a.submittedAt) })}</div>
        </div>
        <div className="nl-q-actions">
          {pending && canDecide ? <>
            <Button disabled={decide.isPending} onClick={() => run(decide.mutateAsync({ id: a.merchantId, decision: 'approve' }))}>{t('approve')}</Button>
            <Button variant="secondary" onClick={() => onAsk(a)}>{t('requestInfo')}</Button>
          </> : null}
          <Button variant="ghost" onClick={onClose}>{t('closePanel')}</Button>
        </div>
      </div>
      {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
      <div className="nl-q-cols nl-q-gap">
        <div>
          <h3 className="nl-q-h2">{t('checksTitle')}</h3>
          <ul className="nl-q-list">
            {a.checks.map(c => <li key={c.id} className="nl-q-row"><span>{checkLabel(c, t)}{c.reference ? ` · ${c.reference}` : ''}</span><Tag tone={c.state === 'passed' ? 'accent' : c.state === 'waiting' ? 'neutral' : 'accent-2'}>{t(`s_${c.state}` as VerifyKey)}</Tag></li>)}
          </ul>
        </div>
        <div>
          <h3 className="nl-q-h2">{t('ownersTitle')}</h3>
          <ul className="nl-q-list">
            {d.owners.map(o => (
              <li key={o.checkId ?? o.principalName} className="nl-q-row">
                <span>{t('ownerLine', { name: o.principalName, role: t(`p_${o.role}` as VerifyKey) })}
                  {o.nameMatch === 'mismatch' ? <><br /><span className="nl-q-note">{t('nameMismatch')}</span></> : null}
                  {o.dobMatch === 'mismatch' ? <><br /><span className="nl-q-note">{t('dobMismatch')}</span></> : null}
                  {o.reviewNote ? <><br /><span className="nl-q-note">{o.reviewNote}</span></> : null}
                </span>
                {o.status === 'review' && o.checkId && canDecide
                  ? <span className="nl-q-actions">
                      <Button variant="secondary" onClick={() => run(identity.mutateAsync({ id: a.merchantId, checkId: o.checkId!, decision: 'approve' }))}>{t('approve')}</Button>
                      <Button variant="ghost" onClick={() => run(identity.mutateAsync({ id: a.merchantId, checkId: o.checkId!, decision: 'reject' }))}>{t('reject')}</Button>
                    </span>
                  : <Tag tone={o.status === 'verified' ? 'accent' : o.status === 'review' ? 'accent-2' : 'neutral'}>{t(`o_${o.status}` as VerifyKey)}</Tag>}
              </li>
            ))}
          </ul>
          <h3 className="nl-q-h2 nl-q-gap">{t('registryTitle')}</h3>
          {d.registryReviews.length === 0 ? <p className="nl-q-note">{t('noReviews')}</p> : (
            <ul className="nl-q-list">
              {d.registryReviews.map(r => (
                <li key={r.id} className="nl-q-row">
                  <span>{t('registryLine', { registry: r.registry ?? r.source, number: r.queryNumber, outcome: r.outcome.replace(/_/g, ' ') })}{r.recordName ? <><br /><span className="nl-q-note">{r.recordName}{r.recordStatus ? ` · ${r.recordStatus}` : ''}</span></> : null}</span>
                  {r.reviewState === 'open' && canDecide
                    ? <span className="nl-q-actions">
                        <Button variant="secondary" onClick={() => run(registry.mutateAsync({ reviewId: r.id, decision: 'approve' }))}>{t('approve')}</Button>
                        <Button variant="ghost" onClick={() => run(registry.mutateAsync({ reviewId: r.id, decision: 'reject' }))}>{t('reject')}</Button>
                      </span>
                    : <Tag tone={r.reviewState === 'approved' ? 'accent' : r.reviewState === 'open' ? 'accent-2' : 'neutral'}>{t(`r_${r.reviewState ?? 'open'}` as VerifyKey)}</Tag>}
                </li>
              ))}
            </ul>
          )}
          {d.decisions.length ? <>
            <h3 className="nl-q-h2 nl-q-gap">{t('historyTitle')}</h3>
            <ul className="nl-q-list">
              {d.decisions.map(h => <li key={h.id}>{t(h.decision === 'approved' ? 'h_approved' : 'h_info_requested', { date: formatDate(h.decidedAt, locale, 'date') })}{h.checkKeys.length ? ` · ${h.checkKeys.map(k => checkLabel({ key: k, registry: null }, t)).join(', ')}` : ''}{h.note ? ` · ${h.note}` : ''}</li>)}
            </ul>
          </> : null}
        </div>
      </div>
    </section>
  );
}

/** "Request info": the checks to redo (not passed ones preselected) and the note emailed to the owners. */
function RequestInfoDialog({ app, onClose, onSent }: { app: Application; onClose: () => void; onSent: () => void }) {
  const t = useVerifyT();
  const decide = useDecideApplication();
  const [keys, setKeys] = useState<string[]>(() => app.checks.filter(c => c.state !== 'passed').map(c => c.key));
  const [note, setNote] = useState('');
  const fieldErrors = decide.error instanceof ValidationError ? decide.error.byField() : {};
  const other = decide.error && !(decide.error instanceof ValidationError) ? (decide.error as Error).message : undefined;
  const send = () => decide.mutate({ id: app.merchantId, decision: 'request_info', checkKeys: keys, note }, { onSuccess: onSent });
  return (
    <Dialog open onClose={onClose} title={t('infoTitle', { business: app.businessName })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={decide.isPending} onClick={send}>{t('infoSend')}</Button></>}>
      <fieldset className="nl-q-field" aria-describedby={fieldErrors.checkKeys ? 'nl-info-keys-err' : undefined}>
        <legend>{t('infoChecks')}</legend>
        {app.checks.map(c => (
          <Checkbox key={c.key} label={checkLabel(c, t)} checked={keys.includes(c.key)}
            onChange={on => setKeys(k => (on ? [...k, c.key] : k.filter(x => x !== c.key)))} />
        ))}
        {fieldErrors.checkKeys ? <span id="nl-info-keys-err" className="nl-q-error" role="alert">{fieldErrors.checkKeys}</span> : null}
      </fieldset>
      <Field label={t('infoNote')} hint={t('infoNoteHint')} error={fieldErrors.note}>
        <TextArea value={note} maxLength={500} onChange={e => setNote(e.target.value)} />
      </Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
