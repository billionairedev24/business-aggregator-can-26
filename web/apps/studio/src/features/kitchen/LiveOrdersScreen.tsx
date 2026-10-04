import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, EmptyState, ErrorState, PageSkeleton, useLocale, timeZone } from '@northline/ui';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useLivePoll } from '../../lib/live';
import { LIVE_POLL_MS, liveQuery, useKitchenToggle, useLiveAction, type Ticket } from './api';
import { useKitchenT } from './messages';
import { CounterCheckDialog } from './CounterCheckDialog';
import { useCounterT } from './counterMessages';
import { lineText, mealOf, minutesUntil, prepText, weekdayIn, whereText, whoText } from './model';
import './Kitchen.css';

/**
 * S-109: what the screen reader hears when the board refreshes — one polite line naming the orders that arrived since
 * the last refresh ("2 new orders: FD-9931, FD-9932"), never the whole board again. The board itself is not a live
 * region: every poll (and the 30 s clock) re-rendered its tickets and their buttons, which a reader would re-announce.
 */
export function useNewOrderAnnouncement(items: readonly Ticket[] | undefined, say: (refs: string[]) => string): string {
  const seen = useRef<Set<string> | null>(null);
  const [message, setMessage] = useState('');
  useEffect(() => {
    if (!items) return;
    const ids = new Set(items.map(o => o.orderId));
    if (seen.current) {
      const fresh = items.filter(o => !seen.current!.has(o.orderId) && o.stage === 'new');
      if (fresh.length) setMessage(say(fresh.map(o => o.ref ?? o.orderId)));
    }
    seen.current = ids;
  }, [items]); // eslint-disable-line react-hooks/exhaustive-deps
  return message;
}

const TAG: Record<Ticket['stage'], string> = { new: 'tag-accent-2', cooking: 'tag-accent', ready: 'tag-neutral', handed_off: 'tag-neutral', refused: 'tag-neutral' };

/** Kitchen · Live orders (design 02 lines 831–841, `kds`): New → Cooking → Ready → handed off, busy bump, pause. */
export function LiveOrdersScreen() {
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const role = useRole();
  const t = useKitchenT();
  const { locale } = useLocale();
  const q = useQuery({ ...liveQuery(merchantId), refetchInterval: useLivePoll(LIVE_POLL_MS) });
  const act = useLiveAction(merchantId);
  const toggle = useKitchenToggle(merchantId);
  const canOperate = role !== 'bookkeeper';
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const id = setInterval(() => setNow(new Date()), 30_000); return () => clearInterval(id); }, []);
  const tc = useCounterT();
  const [checking, setChecking] = useState<Ticket | null>(null);
  const announcement = useNewOrderAnnouncement(q.data?.items, refs => t('announceNew', { n: refs.length, refs: refs.join(', ') }));

  const kicker = t('liveKicker', { name: merchant?.displayName ?? '' });
  if (q.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (q.isError) return <><div className="nl-k-kicker">{kicker}</div><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></>;

  const b = q.data;
  const paused = !!b.pausedUntil && new Date(b.pausedUntil) > now;
  const day = t(`dayLong_${weekdayIn(now)}` as 'dayLong_1');
  const title = t('liveTitle', { day, meal: t(`meal_${mealOf(now)}`), n: b.counts.open });
  return (
    <div className="nl-kitchen">
      <div className="nl-sr-only" role="status" aria-live="polite" aria-atomic="true">{announcement}</div>
      <div className="nl-k-head">
        <div>
          <span className="nl-k-kicker">{kicker}</span>
          <h1 className="nl-k-title">{title}</h1>
        </div>
        <div className="nl-k-actions">
          <span className="nl-k-muted">{t('prepShown')} <strong>{b.prep.bumpMin ? t('prepBumped', { min: b.prep.shownMin, bump: b.prep.bumpMin }) : t('prepMin', { min: b.prep.shownMin })}</strong></span>
          {canOperate ? (
            <>
              <button type="button" className="btn btn-secondary" disabled={toggle.isPending || b.prep.bumpMin >= 30} onClick={() => toggle.mutate('bump')}>{t('busy')}</button>
              <button type="button" className="btn btn-ghost" disabled={toggle.isPending || b.prep.bumpMin === 0} onClick={() => toggle.mutate('reset')}>{t('reset')}</button>
              <button type="button" className="btn btn-secondary nl-k-danger" aria-pressed={paused} disabled={toggle.isPending} onClick={() => toggle.mutate(paused ? 'resume' : 'pause')}>{paused ? t('resume') : t('pause')}</button>
            </>
          ) : null}
        </div>
      </div>
      {b.autoPause?.active && b.autoPause.threshold ? <div className="nl-k-paused" role="status"><strong>{t('autoPausedStrong')}</strong> {t('autoPausedText', { late: b.autoPause.lateOrders, limit: b.autoPause.threshold })}</div> : null}
      {paused ? <div className="nl-k-paused" role="status"><strong>{t('pausedStrong')}</strong> {t('pausedText', { n: Math.max(1, minutesUntil(b.pausedUntil!, now)) })}</div> : null}
      {act.isError || toggle.isError ? <Alert tone="error" role="alert">{t('actionError')}</Alert> : null}
      {b.items.length === 0 ? <EmptyState>{t('liveEmpty')}</EmptyState> : (
        <ul className="nl-k-board">
          {b.items.map(o => {
            const cta = o.stage === 'new' ? t('cta_new') : o.stage === 'cooking' ? t('cta_cooking') : o.fulfilmentMode === 'pickup' ? t('cta_ready_pickup') : t('cta_ready_delivery');
            const action = o.stage === 'new' ? 'accept' : o.stage === 'cooking' ? 'ready' : 'handoff';
            const prep = prepText(o, t, now);
            // 2026-10-04: an age-restricted pickup is handed over only after the counter's ID check
            const counterCheck = action === 'handoff' && o.fulfilmentMode === 'pickup' && !!o.idCheckAge;
            return (
              <li key={o.orderId} className="nl-k-ticket">
                <div className="nl-k-ticket-head"><strong id={`kds-${o.orderId}`}>{o.ref ?? o.orderId}</strong><span className={`tag ${TAG[o.stage]}`}>{t(`stage_${o.stage}`)}</span></div>
                <div className="nl-k-meta">{whoText(o, t, locale)}{prep ? ` · ${prep}` : ''}</div>
                {o.scheduledFor ? <div className="nl-k-meta">{t('scheduledFor', { time: new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: timeZone(), hour: 'numeric', minute: '2-digit' }).format(new Date(o.scheduledFor)) })}</div> : null}
                <div className="nl-k-lines">{o.lines.map((l, i) => <div key={i}>{lineText(l, t)}</div>)}</div>
                <div className="nl-k-where">{whereText(o, t, locale, now)}</div>
                {o.idCheckAge && o.fulfilmentMode === 'pickup' ? <div className="nl-k-meta"><span className="tag tag-highlight">{tc('checkId', { age: o.idCheckAge })}</span></div> : null}
                {canOperate ? (
                  <button type="button" className="btn btn-primary nl-k-cta" aria-describedby={`kds-${o.orderId}`} disabled={act.isPending && act.variables?.orderId === o.orderId}
                    onClick={() => (counterCheck ? setChecking(o) : act.mutate({ orderId: o.orderId, action }, { onError: () => void q.refetch() }))}>{cta}</button>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      <p className="nl-k-foot">{t('liveFooter')}</p>
      {checking ? <CounterCheckDialog merchantId={merchantId} ticket={checking} onClose={() => setChecking(null)} /> : null}
    </div>
  );
}
