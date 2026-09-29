import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Button, Drawer, ErrorState, Skeleton, useLocale } from '@northline/ui';
import { useMerchant } from '../shell/api';
import { screenHref, type ScreenKey } from '../shell/nav';
import { articleQuery, articlesQuery, casesQuery, topicsQuery, type ArticleSummary, type HelpTopic } from './api';
import { useCaseCells, useSlaNote } from './format';
import { useHelpT } from './messages';

export interface HelpHomeProps {
  merchantId: string;
  onContact: (preset: { topic?: string; channel?: 'chat' | 'call' }) => void;
  onOpenCase: (caseId: string) => void;
}

/** Help centre: search, suggested articles, topics, "Talk to a person" (SLA by tier), open cases, guided fixes. */
export function HelpHome({ merchantId, onContact, onOpenCase }: HelpHomeProps) {
  const t = useHelpT();
  const { locale } = useLocale();
  const merchant = useMerchant();
  const kitchen = merchant.type === 'kitchen';
  const sla = useSlaNote();
  const cells = useCaseCells();
  const navigate = useNavigate();
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [topic, setTopic] = useState<HelpTopic | null>(null);
  const [article, setArticle] = useState<string | null>(null);

  useEffect(() => { const id = setTimeout(() => setQuery(input.trim()), 250); return () => clearTimeout(id); }, [input]);

  const topics = useQuery(topicsQuery(merchantId, locale));
  const articles = useQuery(articlesQuery(merchantId, locale, query, query ? '' : topic?.key ?? ''));
  const cases = useQuery(casesQuery(merchantId));
  const openCases = (cases.data ?? []).filter(c => c.state !== 'resolved');

  const heading = query || (topic ? topic.name : t('suggested'));
  const meta = (a: ArticleSummary) => t(query ? 'metaRead' : 'metaShort', { section: a.section, min: a.readMin });
  const go = (key: ScreenKey) => (e: React.MouseEvent) => { e.preventDefault(); void navigate({ to: screenHref(merchantId, key) }); };
  const fixLink = (key: ScreenKey, label: string) => <a href={screenHref(merchantId, key)} onClick={go(key)}>{label}</a>;
  const fix = (msg: 'fix_payout' | 'fix_booked' | 'fix_signoff' | 'fix_gst', key: ScreenKey, label: string) => {
    const [before, after] = t(msg, { link: '\u0000' }).split('\u0000');
    return <li>· {before}{fixLink(key, label)}{after}</li>;
  };

  return (
    <>
      <input className="input nl-help-search" type="search" value={input} placeholder={t('search')} aria-label={t('searchLabel')}
        onChange={e => { setInput(e.target.value); }} />
      <div className="nl-help-grid">
        <div>
          <h2 className="nl-help-h2">{heading}</h2>
          {topic && !query ? <button type="button" className="nl-help-back" onClick={() => setTopic(null)}>{t('allTopics')}</button> : null}
          {articles.isPending ? <ArticleSkeleton /> : articles.isError ? (
            <ErrorState message={t('helpError')} onRetry={() => void articles.refetch()} />
          ) : articles.data.length === 0 ? (
            <p className="nl-help-note">{t('noResults', { q: query })}</p>
          ) : (
            <ul className="nl-help-articles" aria-busy={articles.isFetching || undefined}>
              {articles.data.map(a => (
                <li key={a.slug}>
                  <a href={`#${a.slug}`} className="nl-help-article" onClick={e => { e.preventDefault(); setArticle(a.slug); }}>
                    <span>{a.title}</span><span className="nl-help-meta">{meta(a)}</span>
                  </a>
                </li>
              ))}
            </ul>
          )}
          {topic && !query ? (
            <button type="button" className="nl-help-link" onClick={() => onContact({ topic: topic.caseTopic })}>{t('stillStuck')}</button>
          ) : null}
          <h2 className="nl-help-h2 nl-help-h2-gap">{t('browse')}</h2>
          {topics.isPending ? <Skeleton height={64} /> : topics.isError ? (
            <ErrorState message={t('helpError')} onRetry={() => void topics.refetch()} />
          ) : (
            <div className="nl-help-topics">
              {topics.data.map(tp => (
                <button key={tp.key} type="button" className="nl-help-topic" aria-pressed={topic?.key === tp.key} onClick={() => { setTopic(tp); setInput(''); setQuery(''); }}>
                  <span className="nl-help-topic-name">{tp.name}</span>
                  <span className="nl-help-meta">{t('articles', { count: tp.articleCount })}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        <div>
          <div className="nl-help-talk">
            <div className="nl-help-talk-kicker">{t('talk')}</div>
            <div className="nl-help-talk-sla">{sla(false)}</div>
            <div className="nl-help-talk-actions">
              <Button onClick={() => onContact({ channel: 'chat' })}>{t('startChat')}</Button>
              <Button variant="secondary" className="nl-help-on-bg" onClick={() => onContact({ channel: 'call' })}>{t('callback')}</Button>
            </div>
          </div>
          <h3 className="nl-help-h3">{openCases.length > 1 ? t('openCases') : t('openCase')}</h3>
          {cases.isPending ? <Skeleton height={80} /> : openCases.length === 0 ? <p className="nl-help-note">{t('noOpenCase')}</p> : openCases.map(c => {
            const cell = cells(c);
            return (
              <button key={c.id} type="button" className="nl-help-case" onClick={() => onOpenCase(c.id)}>
                <span className="nl-help-case-head"><strong>{c.code}</strong><span className="tag tag-accent-2">{t('open')}</span></span>
                <span className="nl-help-case-title">{c.subject}</span>
                <span className="nl-help-meta">{cell.status}{cell.next ? ` · ${cell.next}` : ''}</span>
              </button>
            );
          })}
          <h3 className="nl-help-h3">{t('guided')}</h3>
          <ul className="nl-help-fixes">
            {fix('fix_payout', 'compliance', t('fix_payout_link'))}
            {fix('fix_booked', 'compliance', t('fix_booked_link'))}
            {fix('fix_signoff', 'earnings', t('fix_signoff_link'))}
            {fix('fix_gst', kitchen ? 'menu' : 'products', kitchen ? t('fix_gst_link_kitchen') : t('fix_gst_link'))}
          </ul>
          <p className="nl-help-footer">{t('footer')}</p>
        </div>
      </div>
      {article ? <ArticleDrawer merchantId={merchantId} slug={article} topics={topics.data ?? []} onClose={() => setArticle(null)} onContact={onContact} /> : null}
    </>
  );
}

function ArticleDrawer({ merchantId, slug, topics, onClose, onContact }: { merchantId: string; slug: string; topics: HelpTopic[]; onClose: () => void; onContact: HelpHomeProps['onContact'] }) {
  const t = useHelpT();
  const { locale } = useLocale();
  const q = useQuery(articleQuery(merchantId, locale, slug));
  const caseTopic = q.data ? topics.find(tp => q.data.topicKeys.includes(tp.key))?.caseTopic : undefined;
  return (
    <Drawer open onClose={onClose} title={q.data?.title ?? ''} footer={<>
      <Button variant="ghost" onClick={onClose}>{t('close')}</Button>
      <Button variant="secondary" onClick={() => { onClose(); onContact({ topic: caseTopic }); }}>{t('stillStuck')}</Button>
    </>}>
      {q.isPending ? <><Skeleton height={14} /><Skeleton height={14} style={{ marginTop: 8 }} /><Skeleton height={14} width="60%" style={{ marginTop: 8 }} /></>
        : q.isError ? <ErrorState message={t('articleError')} onRetry={() => void q.refetch()} />
          : (
            <article className="nl-help-body">
              <p className="nl-help-meta">{t('metaRead', { section: q.data.section, min: q.data.readMin })}</p>
              {q.data.body.split(/\n\s*\n/).map((p, i) => <p key={i}>{p}</p>)}
            </article>
          )}
    </Drawer>
  );
}

function ArticleSkeleton() {
  return <div aria-busy="true">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} height={20} style={{ margin: '14px 0' }} />)}</div>;
}
