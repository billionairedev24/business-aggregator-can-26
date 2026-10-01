import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Sparkle } from '@phosphor-icons/react';
import { Button, Dialog, Panel } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { aiErrorKind, aiStatusQuery } from '../assistant/api';
import { useListingCopy, useQuoteLineSuggestions, useReplySuggestions, useReviewSummaryDraft, type Copy, type ListingFacts, type QuoteLineSuggestion } from './api';
import { useWritingT } from './messages';
import './Writing.css';

/** Whether AI drafting is on here (GET /api/v1/ai/status); every control below hides itself when it isn't. */
const useAiOn = () => !!useQuery(aiStatusQuery).data?.available;

function useFailure() {
  const t = useWritingT();
  return (e: unknown) => (e instanceof ValidationError ? e.errors[0]?.message ?? t('error')
    : aiErrorKind(e) === 'rate' ? t('rate') : aiErrorKind(e) === 'off' ? t('off') : t('error'));
}

/** Listing editor: "Draft with AI" → English and French drafts; the person picks one, which fills the fields to edit. */
export function ListingCopyButton({ merchantId, facts, onUse, disabled }: { merchantId: string; facts: ListingFacts; onUse: (copy: Copy) => void; disabled?: boolean }) {
  const t = useWritingT();
  const failure = useFailure();
  const on = useAiOn();
  const draft = useListingCopy(merchantId);
  const [open, setOpen] = useState(false);
  if (!on) return null;
  const use = (c: Copy) => { onUse(c); setOpen(false); };
  const d = draft.data;
  return <>
    <Button type="button" variant="secondary" disabled={disabled || draft.isPending} onClick={() => draft.mutate(facts, { onSuccess: () => setOpen(true) })}>
      <Sparkle size={16} weight="duotone" aria-hidden /> {draft.isPending ? t('drafting') : t('draftCopy')}
    </Button>
    {draft.isError ? <div role="alert" className="nl-error">{failure(draft.error)}</div> : null}
    <Dialog open={open && !!d} onClose={() => setOpen(false)} title={t('copyTitle')} width={720}
      actions={<Button variant="ghost" onClick={() => setOpen(false)}>{t('close')}</Button>}>
      <p className="nl-writing-note">{t('copyNote')}</p>
      {d ? <div className="nl-writing-copies">
        {([['en', d.en, t('english'), t('useEnglish')], ['fr', d.fr, t('french'), t('useFrench')]] as const).map(([lang, c, label, cta]) => (
          <section key={lang} className="nl-writing-copy" lang={lang === 'fr' ? 'fr-CA' : 'en-CA'} aria-label={label}>
            <h3>{label}</h3>
            <dl>
              <dt>{t('titleLabel')}</dt><dd>{c.title}</dd>
              <dt>{t('descriptionLabel')}</dt><dd>{c.description}</dd>
              {c.bullets.length ? <><dt>{t('bulletsLabel')}</dt><dd><ul>{c.bullets.map(b => <li key={b}>{b}</li>)}</ul></dd></> : null}
            </dl>
            <Button onClick={() => use(c)}>{cta}</Button>
          </section>
        ))}
      </div> : null}
    </Dialog>
  </>;
}

/** Quote composer: "Suggest lines" adds AI-suggested rows (no prices) for the person to price and check. */
export function QuoteLineSuggestions({ merchantId, requestId, onAdd }: { merchantId: string; requestId: string; onAdd: (lines: QuoteLineSuggestion['lines']) => void }) {
  const t = useWritingT();
  const failure = useFailure();
  const on = useAiOn();
  const suggest = useQuoteLineSuggestions(merchantId);
  if (!on) return null;
  const s = suggest.data;
  return <div className="nl-writing-lines">
    <Button type="button" variant="ghost" disabled={suggest.isPending} onClick={() => suggest.mutate(requestId, { onSuccess: r => onAdd(r.lines) })}>
      <Sparkle size={16} weight="duotone" aria-hidden /> {suggest.isPending ? t('suggesting') : t('suggestLines')}
    </Button>
    {s ? <div className="nl-writing-note" role="status">
      {t('linesAdded', { count: s.lines.length })}
      {s.questions.length ? <>{' '}{t('askCustomer')} <ul>{s.questions.map(q => <li key={q}>{q}</li>)}</ul></> : null}
    </div> : null}
    {suggest.isError ? <div role="alert" className="nl-error">{failure(suggest.error)}</div> : null}
  </div>;
}

/** Messages composer: three AI-suggested replies; a click only fills the box — the person edits and sends. */
export function ReplySuggestions({ merchantId, threadId, onPick }: { merchantId: string; threadId: string; onPick: (text: string) => void }) {
  const t = useWritingT();
  const failure = useFailure();
  const on = useAiOn();
  const suggest = useReplySuggestions(merchantId);
  if (!on) return null;
  return <div className="nl-writing-replies">
    {suggest.data ? (
      <div role="group" aria-label={t('replies')}>
        <p className="nl-writing-note">{t('replies')}</p>
        <div className="nl-writing-reply-list">
          {suggest.data.replies.map(r => <button key={r} type="button" className="nl-chip nl-writing-reply" onClick={() => onPick(r)}>{r}</button>)}
        </div>
      </div>
    ) : (
      <Button type="button" variant="ghost" disabled={suggest.isPending} onClick={() => suggest.mutate(threadId)}>
        <Sparkle size={16} weight="duotone" aria-hidden /> {suggest.isPending ? t('suggesting') : t('suggestReplies')}
      </Button>
    )}
    {suggest.isError ? <div role="alert" className="nl-error">{failure(suggest.error)}</div> : null}
  </div>;
}

/** Reviews: an English and French summary draft with themes, to copy and edit; never published by itself. */
export function ReviewSummaryDraftPanel({ merchantId }: { merchantId: string }) {
  const t = useWritingT();
  const failure = useFailure();
  const on = useAiOn();
  const draft = useReviewSummaryDraft(merchantId);
  const [copied, setCopied] = useState<string | null>(null);
  if (!on) return null;
  const copy = (lang: string, text: string) => { void navigator.clipboard?.writeText(text); setCopied(lang); };
  const d = draft.data;
  return (
    <Panel as="section" className="nl-writing-summary" aria-label={t('summaryTitle')}>
      {!d ? <Button type="button" variant="secondary" disabled={draft.isPending} onClick={() => draft.mutate()}>
        <Sparkle size={16} weight="duotone" aria-hidden /> {draft.isPending ? t('summarizing') : t('summarize')}
      </Button> : <>
        <h2 className="nl-writing-summary-title"><Sparkle size={16} weight="duotone" aria-hidden /> {t('summaryTitle')}</h2>
        <p className="nl-writing-note">{t('summaryNote', { count: d.reviews })}</p>
        {([['en', d.en, t('english')], ['fr', d.fr, t('french')]] as const).map(([lang, v, label]) => (
          <div key={lang} className="nl-writing-version" lang={lang === 'fr' ? 'fr-CA' : 'en-CA'}>
            <strong>{label}</strong>
            <p>{v.summary}</p>
            {v.themes.length ? <p className="nl-writing-note">{t('themes')}: {v.themes.join(' · ')}</p> : null}
            <Button variant="ghost" onClick={() => copy(lang, v.summary)}>{copied === lang ? t('copied') : t('copy')}</Button>
          </div>
        ))}
      </>}
      {draft.isError ? <div role="alert" className="nl-error">{failure(draft.error)}</div> : null}
    </Panel>
  );
}
