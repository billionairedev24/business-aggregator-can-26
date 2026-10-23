import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from '@tanstack/react-router';
import { ActionPanel, ActionPanelItem, ErrorState, PageSkeleton, RecordHeader, RecordHistory, RecordLayout, RecordTabs, StatusTag, useFormatters, useLocale } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useAge } from '../shell/age';
import { useRegionName } from '../shell/PlaceFilters';
import { useGrant } from '../shell/grant';
import { detailQuery, useDecideApplication, type Application, type Detail } from './api';
import { useVerifyT } from './messages';
import {
  BusinessFacts, ChecksEvidence, historyOf, OverrideDialog, RequestInfoDialog, ReviewDialog, ReviewSection, riskText, Stages, stageOf, statusText, useHands,
} from './VerificationQueue';
import '../shell/queues.css';
import './applications.css';

/**
 * One application (S-79; E-15 S-177 as a page): the head with its stage and key facts, the two stages and the current
 * review, then the business, the checks and identity (the evidence) and the history as tabs; the stage's action in the
 * right-hand panel — Review (stage 1, `verify`) or Approve / Request info (stage 2, `approve`), off with the reason for
 * the person who reviewed this round (the api refuses it too).
 */
export function ApplicationPage() {
  const t = useVerifyT();
  const { applicationId } = useParams({ strict: false }) as { applicationId: string };
  const query = useQuery(detailQuery(applicationId));
  const back = <div className="bo-rec-back"><Link to="/verification">{t('back')}</Link></div>;
  if (query.isPending) return <div className="bo-page">{back}<PageSkeleton kpis={0} rows={6} /></div>;
  if (query.isError) {
    const missing = query.error instanceof ApiError && query.error.status === 404;
    return <div className="bo-page">{back}{missing ? <p role="alert">{t('notFoundApp')}</p> : <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />}</div>;
  }
  return <ApplicationView d={query.data} />;
}

function ApplicationView({ d }: { d: Detail }) {
  const t = useVerifyT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const age = useAge();
  const regionName = useRegionName();
  const { roleName } = useGrant();
  const decide = useDecideApplication();
  const a = d.application;
  const { canReview, canApprove, fourEyes, reviewedByMe } = useHands(d);
  const [dialog, setDialog] = useState<'review' | 'override' | 'info' | null>(null);
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const stage = stageOf(a);
  const current = a.review ?? null;
  const pending = a.status === 'pending';
  const approve = async (app: Application) => {
    setError(undefined);
    if (current && current.recommendation !== 'approve') { setDialog('override'); return; }
    try {
      await decide.mutateAsync({ id: app.merchantId, decision: 'approve' });
      setNotice(t('approvedToast', { business: app.businessName }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  };
  const showReview = pending && canReview && stage === 'review';
  const showDecide = pending && canApprove && (stage === 'decision' || !fourEyes);
  const tone = !pending ? (a.decision === 'approved' ? 'success' : 'neutral') : stage === 'review' ? 'info' : 'warning';
  const note = !pending ? undefined : reviewedByMe && showDecide ? t('sameReviewer') : !showReview && !showDecide ? (stage === 'review' && canApprove ? t('waitingReview') : t('viewOnly', { role: roleName })) : undefined;
  return (
    <div className="bo-page">
      <RecordHeader back={<Link to="/verification">{t('back')}</Link>} title={a.businessName}
        status={<StatusTag tone={tone} size="lg">{pending ? t(`stage_${stage}`) : statusText(a, t)}</StatusTag>}
        facts={[
          { label: t('f_legal'), value: a.legalName },
          a.categories.length ? { label: t('f_categories'), value: a.categories.join(', ') } : null,
          { label: t('f_place'), value: [a.city, a.province ? regionName(a.province) : null].filter(Boolean).join(', ') || '—' },
          a.submittedAt ? { label: t('f_submitted'), value: t('submittedAgo', { age: age(a.submittedAt) }) } : null,
          { label: t('f_risk'), value: riskText(a, t) },
        ]} />
      {notice ? <p role="status" className="nl-q-note">{notice}</p> : null}
      <RecordLayout asideLabel={t('actionsTitle')} aside={
        <ActionPanel title={t('actionsTitle')} note={note}>
          {showReview ? <ActionPanelItem variant="primary" label={`${t('review')}…`} onClick={() => setDialog('review')} /> : null}
          {showDecide ? <>
            <ActionPanelItem variant={showReview ? 'secondary' : 'primary'} label={t('approve')} disabled={decide.isPending || reviewedByMe} onClick={() => void approve(a)} />
            <ActionPanelItem label={`${t('requestInfo')}…`} disabled={reviewedByMe} onClick={() => setDialog('info')} />
          </> : null}
          {error ? <p role="alert" className="nl-q-error">{error}</p> : null}
        </ActionPanel>}>
        <Stages stage={pending ? stage : 'done'} />
        <ReviewSection d={d} />
        <RecordTabs label={t('recordTabs')} tabs={[
          { value: 'business', label: t('secBusiness'), render: () => <BusinessFacts d={d} /> },
          { value: 'evidence', label: t('evidenceTab'), count: a.checks.length + d.owners.length, render: () => <ChecksEvidence d={d} canSettle={canReview} /> },
          { value: 'history', label: t('historyTitle'), count: d.decisions.length + d.reviews.length, render: () => <RecordHistory items={historyOf(d, t, locale).map(h => ({ key: h.key, when: fmt.date(h.at), text: h.text }))} empty="—" /> },
        ]} />
      </RecordLayout>
      {dialog === 'review' ? <ReviewDialog app={a} onClose={() => setDialog(null)} onSent={() => { setDialog(null); setNotice(t('reviewedToast', { business: a.businessName })); }} /> : null}
      {dialog === 'override' ? <OverrideDialog app={a} onClose={() => setDialog(null)} onSent={() => { setDialog(null); setNotice(t('approvedToast', { business: a.businessName })); }} /> : null}
      {dialog === 'info' ? <RequestInfoDialog app={a} keys={current?.recommendation === 'request_info' ? current.checkKeys : undefined} note={current?.recommendation === 'request_info' ? current.note ?? undefined : undefined}
        onClose={() => setDialog(null)} onSent={() => { setDialog(null); setNotice(t('infoToast', { business: a.businessName })); }} /> : null}
    </div>
  );
}
