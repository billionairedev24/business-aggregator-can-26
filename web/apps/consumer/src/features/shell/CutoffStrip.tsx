import { Truck } from '@phosphor-icons/react';
import { PromoStrip, useFormatters, useLocale } from '@northline/ui';
import { useZone } from '../location/regions';
import { clock, runWhen, weekday } from '../shop/format';
import { useArea } from '../shop/market';
import { minutesUntil, useNextRun, useNow } from '../shop/run';
import { useShellT } from './messages';

/**
 * The delivery cut-off strip under the header (E-15, design/revamp/a.html): "Tonight's pooled run to <area> closes at
 * 5:30 p.m. Mix shops for one $2.99 delivery." and the time left at the end — where data exists: the visitor's market
 * has a next run whose order-by time is still ahead. The countdown runs in the browser only (a server-rendered clock
 * would not match the browser's).
 */
export function CutoffStrip() {
  const { run, market } = useNextRun();
  const now = useNow();
  if (!run || !market) return null;
  if (now !== null && minutesUntil(run.orderBy, now) <= 0) return null;
  return <CutoffLine run={run} market={market} now={now} />;
}

function CutoffLine({ run, market, now }: { run: NonNullable<ReturnType<typeof useNextRun>['run']>; market: string; now: number | null }) {
  const t = useShellT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const zone = useZone(market);
  const area = useArea(market);
  const when = runWhen(run, zone);
  const left = now === null ? null : minutesUntil(run.orderBy, now);
  return (
    <PromoStrip icon={<Truck size={18} />} label={t('cutoffLabel')}
      end={left !== null ? t('cutoffLeft', { hours: Math.floor(left / 60), minutes: left % 60 }) : null}>
      <strong>{t('cutoffLead', { when, weekday: weekday(run.startsAt, locale, zone), area, time: clock(run.orderBy, locale, zone) })}</strong>{' '}
      {t('cutoffRest', { fee: money(run.feeCents) })}
    </PromoStrip>
  );
}
