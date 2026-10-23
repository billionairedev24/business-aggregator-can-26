import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, BoHeader, Button, EmptyState, ErrorState, PageSkeleton, StatusTag, formatWhen, useLocale, type StatusTone } from '@northline/ui';
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

/**
 * S-175: the stage colours of the kitchen display (one role per colour): a new order waits on the kitchen (warning),
 * cooking is in hand (info), ready is done (success); a ticket past its ready-by time turns danger.
 */
export const STAGE_TONE: Record<Ticket['stage'], StatusTone> = { new: 'warning', cooking: 'info', ready: 'success', handed_off: 'neutral', refused: 'neutral' };
const LANES = ['new', 'cooking', 'ready'] as const;
const isLate = (o: Ticket, now: Date) => o.stage === 'cooking' && !!o.readyBy && minutesUntil(o.readyBy, now) < 0;

/**
 * Kitchen · Live orders (design 02 `kds`; S-175 the kitchen display): three lanes — New → Cooking → Ready — of big,
 * glanceable tickets with the stage's colour on top, the dishes in large type and one big button for the next step;
 * busy bump and pause in the head. Phones stack the lanes.
 */
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
  if (q.isError) return <><BoHeader kicker={kicker} title={t('liveTitleShort')} /><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></>;

  const b = q.data;
  const paused = !!b.pausedUntil && new Date(b.pausedUntil) > now;
  const day = t(`dayLong_${weekdayIn(now)}` as 'dayLong_1');
  const title = t('liveTitle', { day, meal: t(`meal_${mealOf(now)}`), n: b.counts.open });
  const ticket = (o: Ticket) => {
    const cta = o.stage === 'new' ? t('cta_new') : o.stage === 'cooking' ? t('cta_cooking') : o.fulfilmentMode === 'pickup' ? t('cta_ready_pickup') : t('cta_ready_delivery');
    const action = o.stage === 'new' ? 'accept' : o.stage === 'cooking' ? 'ready' : 'handoff';
    const prep = prepText(o, t, now);
    const late = isLate(o, now);
    // 2026-10-04: an age-restricted pickup is handed over only after the counter's ID check
    const counterCheck = action === 'handoff' && o.fulfilmentMode === 'pickup' && !!o.idCheckAge;
    return (
      <li key={o.orderId} className="nl-k-ticket" data-stage={o.stage} data-late={late || undefined} aria-labelledby={`kds-${o.orderId}`}>
        <div className="nl-k-ticket-head">
          <strong className="nl-k-ref" id={`kds-${o.orderId}`}>{o.ref ?? o.orderId}</strong>
          <StatusTag size="lg" tone={late ? 'danger' : STAGE_TONE[o.stage]}>{late ? t('stageLate') : t(`stage_${o.stage}`)}</StatusTag>
        </div>
        {prep ? <div className="nl-k-timer" data-late={late || undefined}>{prep}</div> : null}
        <ul className="nl-k-lines">{o.lines.map((l, i) => <li key={i}>{lineText(l, t)}</li>)}</ul>
        <div className="nl-k-meta">{whoText(o, t, locale)}</div>
        {o.scheduledFor ? <div className="nl-k-meta">{t('scheduledFor', { time: formatWhen(o.scheduledFor, locale, 'time') })}</div> : null}
        <div className="nl-k-where">{whereText(o, t, locale, now)}</div>
        {o.idCheckAge && o.fulfilmentMode === 'pickup' ? <div><StatusTag tone="warning">{tc('checkId', { age: o.idCheckAge })}</StatusTag></div> : null}
        {canOperate ? (
          <Button size="lg" block className="nl-k-cta" aria-describedby={`kds-${o.orderId}`} disabled={act.isPending && act.variables?.orderId === o.orderId}
            onClick={() => (counterCheck ? setChecking(o) : act.mutate({ orderId: o.orderId, action }, { onError: () => void q.refetch() }))}>{cta}</Button>
        ) : null}
      </li>
    );
  };
  return (
    <div className="nl-kitchen bo-page">
      <div className="nl-sr-only" role="status" aria-live="polite" aria-atomic="true">{announcement}</div>
      <BoHeader kicker={kicker} title={title}
        sub={<>{t('prepShown')} <strong>{b.prep.bumpMin ? t('prepBumped', { min: b.prep.shownMin, bump: b.prep.bumpMin }) : t('prepMin', { min: b.prep.shownMin })}</strong></>}
        actions={canOperate ? (
          <>
            <Button variant="secondary" disabled={toggle.isPending || b.prep.bumpMin >= 30} onClick={() => toggle.mutate('bump')}>{t('busy')}</Button>
            <Button variant="tertiary" disabled={toggle.isPending || b.prep.bumpMin === 0} onClick={() => toggle.mutate('reset')}>{t('reset')}</Button>
            <Button variant={paused ? 'secondary' : 'destructive'} aria-pressed={paused} disabled={toggle.isPending} onClick={() => toggle.mutate(paused ? 'resume' : 'pause')}>{paused ? t('resume') : t('pause')}</Button>
          </>
        ) : undefined} />
      {b.autoPause?.active && b.autoPause.threshold ? <Alert tone="info" role="status"><strong>{t('autoPausedStrong')}</strong> {t('autoPausedText', { late: b.autoPause.lateOrders, limit: b.autoPause.threshold })}</Alert> : null}
      {paused ? <Alert tone="info" role="status"><strong>{t('pausedStrong')}</strong> {t('pausedText', { n: Math.max(1, minutesUntil(b.pausedUntil!, now)) })}</Alert> : null}
      {act.isError || toggle.isError ? <Alert tone="error" role="alert">{t('actionError')}</Alert> : null}
      {b.items.length === 0 ? <EmptyState>{t('liveEmpty')}</EmptyState> : (
        <div className="nl-k-lanes">
          {LANES.map(stage => {
            const items = b.items.filter(o => o.stage === stage || (stage === 'ready' && o.stage === 'refused'));
            return (
              <section key={stage} className="nl-k-lane" data-stage={stage} aria-labelledby={`lane-${stage}`}>
                <h2 className="nl-k-lane-title" id={`lane-${stage}`}>{t(`stage_${stage}`)} <span className="nl-k-lane-n">{items.length}</span></h2>
                {items.length ? <ul className="nl-k-board">{items.map(ticket)}</ul> : <p className="nl-k-lane-empty">{t(`laneEmpty_${stage}`)}</p>}
              </section>
            );
          })}
        </div>
      )}
      <p className="nl-k-foot">{t('liveFooter')}</p>
      {checking ? <CounterCheckDialog merchantId={merchantId} ticket={checking} onClose={() => setChecking(null)} /> : null}
    </div>
  );
}
