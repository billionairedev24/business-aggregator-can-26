import { useQuery } from '@tanstack/react-query';
import { Sparkle } from '@phosphor-icons/react';
import { Button, Panel, useLocale } from '@northline/ui';
import { aiStatusQuery, insightQuery, type InsightScreen } from './api';
import { useAssistantT } from './messages';
import './Assistant.css';

/**
 * S-130: "Insight" on the dashboard, earnings and listings screens. Fetched only when the person asks (each one is a
 * model call), always labelled AI-generated; hidden when no model is configured here.
 */
export function InsightCard({ merchantId, screen }: { merchantId: string; screen: InsightScreen }) {
  const t = useAssistantT();
  const { locale } = useLocale();
  const status = useQuery(aiStatusQuery);
  const insight = useQuery(insightQuery(merchantId, screen, locale));
  if (!status.data?.available) return null;
  const d = insight.data;
  return (
    <Panel as="section" className="nl-insight" aria-label={t('insight')}>
      <div className="nl-insight-head">
        <span className="nl-insight-kicker"><Sparkle size={16} weight="duotone" aria-hidden /> {t('insight')}</span>
        {!d && !insight.isFetching ? <Button variant="secondary" onClick={() => void insight.refetch()}>{t('explain')}</Button> : null}
      </div>
      {insight.isFetching ? <p className="nl-insight-body" role="status">{t('explaining')}</p> : null}
      {insight.isError && !insight.isFetching ? <p className="nl-insight-error" role="alert">{t('insightError')} <button type="button" className="nl-assistant-link" onClick={() => void insight.refetch()}>{t('retry')}</button></p> : null}
      {d && !insight.isFetching ? <>
        {d.title ? <h2 className="nl-insight-title">{d.title}</h2> : null}
        <p className="nl-insight-body">{d.body}</p>
        {d.bullets.length ? <ul className="nl-insight-bullets">{d.bullets.map(b => <li key={b}>{b}</li>)}</ul> : null}
        <p className="nl-insight-note">{t('insightNote')}</p>
      </> : null}
    </Panel>
  );
}
