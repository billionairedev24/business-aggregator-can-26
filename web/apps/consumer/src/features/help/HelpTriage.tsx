import { useId, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { Sparkle, Warning } from '@phosphor-icons/react';
import { ApiError, http, ValidationError } from '@northline/client';
import { SiteLink } from '@northline/ui';
import { aiStatusQuery } from '../search/api';
import { signInHref } from '../session/api';
import { useHelpT } from './messages';
import { useTriageT, type TriageKey, type TriageT } from './triageMessages';

/** S-132's triage answer (`POST /api/v1/me/help/triage`): a suggestion only — it opens nothing. */
export const Triage = z.object({
  category: z.string(), route: z.enum(['refund_request', 'dispute', 'support']).catch('support'), urgent: z.boolean().default(false), summary: z.string().nullish(),
});
export type Triage = z.infer<typeof Triage>;

const ORDERS = '/account/orders';
const CASES = '/account?tab=help';

/** The help page section that answers this kind of problem (the anchor of its heading on this page). */
export function articleFor(category: string): 'help-problem' | 'help-pay' | 'help-contact' {
  if (category === 'billing') return 'help-pay';
  if (category === 'account' || category === 'safety' || category === 'other') return 'help-contact';
  return 'help-problem';
}

export const categoryText = (c: string, t: TriageT) => { const k = `cat_${c}` as TriageKey; const s = t(k); return s === k ? t('cat_other') : s; };

/**
 * Help triage in the help centre (S-158 on S-132): the signed-in person says what happened and gets where to start —
 * the case type (a refund request or a dispute from the order or booking, else a help case) and the section of this
 * page that answers it — before opening anything. Safety is always urgent. When AI is off or unavailable (503), the
 * topics below stay the way in.
 */
export function HelpTriage({ signedIn }: { signedIn: boolean }) {
  const t = useTriageT();
  const help = useHelpT();
  const id = useId();
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const status = useQuery({ ...aiStatusQuery, enabled: signedIn });
  const triage = useMutation({ mutationFn: (body: { text: string }) => http('/api/v1/me/help/triage', { method: 'POST', body }, Triage) });

  if (!signedIn) {
    return (
      <section aria-labelledby={`${id}-h`} className="nl-help-triage">
        <h2 id={`${id}-h`}>{t('title')}</h2>
        <p>{t('guest')} <SiteLink href={signInHref('/help')}>{t('signIn')}</SiteLink></p>
      </section>
    );
  }
  if (status.data && !status.data.available) return null;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const typed = text.trim();
    if (typed.length < 10) { setError(t('tooShort')); return; }
    if (typed.length > 2000) { setError(t('tooLong')); return; }
    setError(null);
    triage.mutate({ text: typed }, {
      onError: e2 => { if (e2 instanceof ValidationError) setError(e2.byField().text ?? t('failed')); },
    });
  };
  const failed = triage.error && !(triage.error instanceof ValidationError);
  const unavailable = failed && triage.error instanceof ApiError && (triage.error.status === 503 || triage.error.status === 429);
  const r = triage.data;
  const topic = r ? { 'help-problem': help('problemTitle'), 'help-pay': help('payTitle'), 'help-contact': help('contactTitle') }[articleFor(r.category)] : '';
  return (
    <section aria-labelledby={`${id}-h`} className="nl-help-triage">
      <h2 id={`${id}-h`}>{t('title')}</h2>
      <p>{t('lede')}</p>
      <form onSubmit={submit} noValidate>
        <label className="nl-label" htmlFor={`${id}-t`}>{t('label')}</label>
        <textarea id={`${id}-t`} className="input" rows={3} value={text} placeholder={t('placeholder')} aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-e` : undefined} onChange={e => { setText(e.target.value); setError(null); }} />
        {error ? <div id={`${id}-e`} role="alert" className="nl-error">{error}</div> : null}
        <button type="submit" className="btn btn-secondary" disabled={triage.isPending}><Sparkle size={16} aria-hidden /> {triage.isPending ? t('suggesting') : t('suggest')}</button>
      </form>
      {failed ? <p role="status" className="nl-help-note">{unavailable ? t('unavailable') : t('failed')}</p> : null}
      {r ? (
        <div className="nl-help-answer" role="status">
          {r.urgent ? <p className="nl-help-urgent"><Warning size={18} aria-hidden /> {t('urgent')}</p> : null}
          <p><strong>{t('looksLike', { category: categoryText(r.category, t) })}</strong></p>
          <p>{t(`r_${r.route}`)}</p>
          <p className="nl-help-links">
            {r.route === 'support' ? <SiteLink href={CASES}>{t('toCases')}</SiteLink> : <SiteLink href={ORDERS}>{t('toOrders')}</SiteLink>}
            <a href={`#${articleFor(r.category)}`}>{t('read', { topic })}</a>
          </p>
          <p className="nl-help-note">{t('aiNote')}</p>
        </div>
      ) : null}
    </section>
  );
}
