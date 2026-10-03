import { useQuery } from '@tanstack/react-query';
import { Alert, ErrorState, Kpi, KpiRow, PageSkeleton, Tag, useFormatters } from '@northline/ui';
import { REPORT_CSV, reportQuery } from './api';
import { useUatT, type UatKey } from './messages';
import '../shell/queues.css';

/**
 * UAT go/no-go (S-121): the verdict and why, open blocking items (accepted and not fixed, fixed and not verified,
 * reported blockers not triaged), sign-off coverage per persona and the last 14 days. S-118's go-live checklist reads the
 * same report (`uat.api.UatReadiness`); CSV for the go/no-go meeting.
 */
export function GoNoGo() {
  const t = useUatT();
  const { date } = useFormatters();
  const report = useQuery(reportQuery);
  if (report.isPending) return <PageSkeleton kpis={3} rows={6} />;
  if (report.isError) return <ErrorState message={t('loadError')} onRetry={() => void report.refetch()} />;
  const r = report.data;
  return (
    <div>
      <span className="nl-q-kicker">{t('rKicker')}</span>
      <h1 className="nl-q-title">{r.verdict === 'go' ? t('go') : t('noGo')}</h1>
      <p className="nl-q-lede">{t('rLede', { date: date(r.generatedAt, 'dateTime') })}</p>
      {r.reasons.length ? <Alert tone="highlight" role="status"><ul className="nl-q-list">{r.reasons.map(x => <li key={`${x.code}-${x.persona ?? ''}`}>{x.text}</li>)}</ul></Alert> : null}
      <div className="nl-q-actions nl-q-gap"><a className="btn btn-secondary" href={REPORT_CSV} download>{t('reportCsv')}</a></div>
      <KpiRow>
        <Kpi value={r.blockingOpen} label={t('kpiOpen')} />
        <Kpi value={r.blockingUnverified} label={t('kpiUnverified')} />
        <Kpi value={r.untriagedBlockers} label={t('kpiUntriaged')} />
      </KpiRow>
      <div className="nl-q-cols nl-q-gap">
        <section className="nl-q-panel">
          <h2 className="nl-q-h2">{t('blockingTitle')}</h2>
          {r.blockingItems.length === 0 ? <p className="nl-q-note">{t('noBlocking')}</p> : (
            <ul className="nl-q-list">
              {r.blockingItems.map(b => <li key={b.id}>
                <strong>{b.reference}</strong> <Tag tone="accent-2">{t(`s_${b.state}` as UatKey)}</Tag> · {t(`p_${b.persona}` as UatKey)} · {b.summary}
                <div className="nl-q-note">{b.ownerName ?? t('unassigned')} · {t('reports', { n: b.reports })}{b.trackerUrl ? <> · <a href={b.trackerUrl} target="_blank" rel="noreferrer noopener">{t('openTracker')}</a></> : null}</div>
              </li>)}
            </ul>
          )}
        </section>
        <section className="nl-q-panel">
          <h2 className="nl-q-h2">{t('coverageTitle')}</h2>
          <ul className="nl-q-list">
            {r.coverage.map(c => <li key={c.persona}>
              <strong>{t(`p_${c.persona}`)}</strong> <Tag tone={c.complete ? 'accent' : 'highlight'}>{c.complete ? t('complete') : t('incomplete')}</Tag>
              <div className="nl-q-note">{c.scriptTitle} v{c.scriptVersion} · {t('coverageLine', { signed: c.signedOff + c.withComments, total: c.participants, comments: c.withComments, blocked: c.blocked, pending: c.pending })}</div>
            </li>)}
          </ul>
        </section>
      </div>
      <section className="nl-q-gap">
        <h2 className="nl-q-h2">{t('trendTitle')}</h2>
        <div className="nl-q-tablewrap">
          <table className="nl-uat-trend">
            <thead><tr><th scope="col">{t('colDay')}</th><th scope="col">{t('colReported')}</th><th scope="col">{t('colOpenBlocking')}</th><th scope="col">{t('colResolved')}</th></tr></thead>
            <tbody>{r.trend.map(d => <tr key={d.date}><th scope="row">{d.date}</th><td>{d.reported}</td><td>{d.openBlocking}</td><td>{d.resolved}</td></tr>)}</tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
