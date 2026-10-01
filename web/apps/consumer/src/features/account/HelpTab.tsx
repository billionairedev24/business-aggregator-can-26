import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Button, DataTable, EmptyState, ErrorState, Field, SiteLink, TextArea, useFormatters, useLocale, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { isNotFound } from '@northline/client';
import { brandName } from './PaymentsTab';
import { caseQuery, casesQuery, useAddNote, type CaseDetail, type CaseRow } from './problemApi';
import { useProblemT, type ProblemT } from './problemMessages';
import { FormSkeleton } from './ProfileTab';
import { shortDate, titleText } from './format';
import { useAccountT } from './messages';
import { tabHref } from './tabs';

interface Row { id: string; number: string; about: string; opened: string; status: string; tone: DataTableTone }

/** "Seller reviewing · 14 h left", "Closed · $15 credit"… (design 06 cases table). */
export function caseStatus(c: CaseRow, t: ProblemT, money: (c: number) => string, now = Date.now()): string {
  if (c.kind === 'refund') {
    switch (c.state) {
      case 'seller_review': {
        if (!c.respondBy) return t('status_seller_review_due');
        const min = Math.max(0, Math.round((new Date(c.respondBy).getTime() - now) / 60_000));
        const left = min >= 48 * 60 ? t('left_d', { n: Math.round(min / 1440) }) : min >= 60 ? t('left_h', { n: Math.round(min / 60) }) : t('left_m', { n: min });
        return t('status_seller_review', { left });
      }
      case 'agent_review': return t('status_agent_review');
      case 'approved': return t('status_approved');
      case 'paid': return t(c.outcome === 'credit' ? 'status_paid_credit' : 'status_paid', { amount: money((c.settledCents ?? c.amountCents) + (c.outcome === 'credit' ? 0 : c.taxCents)) });
      case 'denied': return t('status_denied');
      default: return c.state;
    }
  }
  const key = `status_${c.state}` as Parameters<ProblemT>[0];
  const text = t(key);
  return text === key ? c.state : text;
}

/** Help & cases (design 06 `at.help`): the person's refund cases and disputes; `?case=` opens one. */
export function HelpTab() {
  const search = useSearch({ strict: false }) as { case?: unknown };
  return typeof search.case === 'string' && search.case ? <CaseView id={search.case} /> : <CaseList />;
}

function CaseList() {
  const t = useProblemT();
  const a = useAccountT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const navigate = useNavigate();
  const list = useQuery(casesQuery);
  const columns = useMemo<DataTableColumn<Row>[]>(() => [
    { key: 'number', label: t('colCase'), primary: true, filter: false },
    { key: 'about', label: t('colAbout'), filter: false },
    { key: 'opened', label: t('colOpened'), filter: false },
    { key: 'status', label: t('colStatus'), type: 'tag' },
  ], [t]);
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('helpTitle')}</h1>
      {list.isPending ? <FormSkeleton label={t('loading')} rows={3} />
        : list.isError ? <ErrorState message={t('loadError')} onRetry={() => void list.refetch()} />
          : list.data.length === 0 ? <EmptyState action={<SiteLink href="/account/orders" className="btn btn-primary">{t('openOrders')}</SiteLink>}>{t('noCases')}</EmptyState>
            : (
              <div className="nl-acct-table nl-help-table">
                <DataTable<Row> entity={t('caseEntity')} plural={t('casePlural')} aria-label={t('helpTitle')} columns={columns}
                  rows={list.data.map(c => ({
                    id: c.id,
                    number: c.number,
                    about: [c.subject ? `${titleText(c.subject, a)} ${shortDate(c.subject.when, locale)}` : c.merchantName, c.what, money(c.amountCents + c.taxCents)].filter(Boolean).join(' · '),
                    opened: shortDate(c.openedAt, locale),
                    status: caseStatus(c, t, money),
                    tone: c.open ? 'tag-accent-2' : 'tag-neutral',
                  }))}
                  rowTones={r => ({ status: r.tone })} can={{ create: false, update: false, delete: false, export: true }}
                  onOpen={r => void navigate({ to: '/account', search: { tab: 'help', case: r.id } as never })} />
              </div>
            )}
      <div className="nl-acct-actions">
        <SiteLink href={tabHref('language')} className="btn btn-ghost">{t('languageLink', { language: t(locale === 'fr' ? 'lang_fr' : 'lang_en') })}</SiteLink>
      </div>
    </>
  );
}

function CaseView({ id }: { id: string }) {
  const t = useProblemT();
  const detail = useQuery(caseQuery(id));
  return (
    <>
      <SiteLink href={tabHref('help')} className="nl-small">← {t('backToCases')}</SiteLink>
      {detail.isPending ? <><h1 id="acct-title" className="nl-acct-h1">{t('helpTitle')}</h1><FormSkeleton label={t('loading')} rows={4} /></>
        : detail.isError ? (
          <>
            <h1 id="acct-title" className="nl-acct-h1">{t('helpTitle')}</h1>
            {isNotFound(detail.error) ? <EmptyState>{t('caseNotFound')}</EmptyState> : <ErrorState message={t('caseLoadError')} onRetry={() => void detail.refetch()} />}
          </>
        ) : <CaseBody detail={detail.data} />}
    </>
  );
}

function CaseBody({ detail }: { detail: CaseDetail }) {
  const t = useProblemT();
  const a = useAccountT();
  const { money, date } = useFormatters();
  const { locale } = useLocale();
  const c = detail.row;
  const card = detail.card ? `${brandName(detail.card.brand)} ··${detail.card.last4}` : t('yourCard');
  const detailOf = (key: string, at: string | null | undefined) => {
    switch (key) {
      case 'submitted': return t('stepd_submitted', { time: at ? date(at, 'dateTime') : '', attached: t('attachedReason') });
      case 'seller': return t('stepd_seller', { merchant: c.merchantName, date: at ? date(at, 'dateTime') : '' });
      case 'northline': return t('stepd_northline');
      default: return t('stepd_refund', { card });
    }
  };
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('caseTitle', { number: c.number })}</h1>
      <p className="nl-small nl-muted">
        {[c.subject ? `${titleText(c.subject, a)} ${shortDate(c.subject.when, locale)}` : c.merchantName, c.what, money(c.amountCents + c.taxCents)].filter(Boolean).join(' · ')}
      </p>
      <div className="nl-acct-panel"><strong>{caseStatus(c, t, money)}</strong>{detail.thread ? <span className="nl-small nl-muted">{t('staffCase', { code: detail.thread.code })}</span> : null}</div>
      <ol className="nl-problem-steps">
        {detail.steps.map(s => (
          <li key={s.key} className={`is-${s.state}`}>
            <span className="nl-problem-dot" aria-hidden />
            <span><strong>{t(`step_${s.key}`)}</strong> · {detailOf(s.key, s.at)}<span className="nl-sr-only"> ({t(`stepState_${s.state}`)})</span></span>
          </li>
        ))}
      </ol>
      {detail.thread ? <Conversation caseId={c.id} detail={detail} /> : null}
    </>
  );
}

function Conversation({ caseId, detail }: { caseId: string; detail: CaseDetail }) {
  const t = useProblemT();
  const { date } = useFormatters();
  const add = useAddNote(caseId);
  const [body, setBody] = useState('');
  const [error, setError] = useState<string>();
  const thread = detail.thread!;
  const closed = thread.state === 'resolved';
  const send = () => {
    if (!body.trim()) { setError(t('v_noteEmpty')); return; }
    setError(undefined);
    add.mutate({ body: body.trim(), attachmentIds: [] }, { onSuccess: () => setBody(''), onError: () => setError(t('submitError')) });
  };
  return (
    <section aria-labelledby="case-messages">
      <h2 id="case-messages" className="nl-acct-h2">{t('conversation')}</h2>
      <ul className="nl-case-notes">
        {thread.notes.map((n, i) => (
          <li key={i} className={`nl-case-note is-${n.by}`}>
            <span className="nl-small nl-muted">{t(n.by === 'you' ? 'you' : 'northline')} · {date(n.at, 'dateTime')}</span>
            <span className="nl-case-note-body">{n.body}</span>
            {n.attachments.length ? <span className="nl-small">{n.attachments.map(x => x.fileName).join(', ')}</span> : null}
          </li>
        ))}
      </ul>
      {closed ? <p className="nl-small nl-muted">{t('caseClosed')}</p> : (
        <>
          <Field label={t('addNote')} hint={t('noteHint')} error={error}><TextArea value={body} rows={3} onChange={e => setBody(e.target.value)} /></Field>
          <div className="nl-acct-actions"><Button type="button" disabled={add.isPending} aria-busy={add.isPending} onClick={send}>{t('send')}</Button></div>
        </>
      )}
    </section>
  );
}
