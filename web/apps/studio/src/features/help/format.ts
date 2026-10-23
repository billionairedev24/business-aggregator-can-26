import { useCallback } from 'react';
import { formatDate, useLocale, type StatusTone, timeZone } from '@northline/ui';
import { useMerchant } from '../shell/api';
import { agoParts, useAgo } from '../messages/time';
import type { CaseSummary } from './api';
import { useHelpT } from './messages';

/**
 * Support hours and the maintenance window, kept in the platform zone (region configuration, REGION_PLATFORM_ZONE —
 * the server's SupportSla counts the same hours) and shown with that zone's name: copy never names a zone.
 */
export const SUPPORT_HOURS = ['07:00', '23:00'] as const;
export const MAINTENANCE_HOURS = ['02:00', '04:00'] as const;

/** First-reply promise shown on Help (design helpSlaNote): urgent → 15 min, Master → 1 h, everyone else → 4 business hours. */
export function useSlaNote() {
  const t = useHelpT();
  const tier = useMerchant()?.tier;
  return useCallback((urgent: boolean) => t(urgent ? 'sla_urgent' : tier === 'master' ? 'sla_master' : 'sla_normal'), [t, tier]);
}

const dayKey = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: timeZone(), year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);

/** Case table cells: "Open · agent Dev K. · replied 2 h ago" / "Resolved · Sep 5 · bank holiday", tone, "Reply by tomorrow 10 a.m.". */
export function useCaseCells() {
  const t = useHelpT();
  const ago = useAgo();
  const { locale } = useLocale();
  return useCallback((c: CaseSummary, now: number = Date.now()) => {
    const open = c.state !== 'resolved';
    let status: string;
    if (!open) {
      const date = c.resolvedAt ? formatDate(c.resolvedAt, locale, 'date') : '';
      status = c.resolutionNote ? t('status_resolvedNote', { date, note: c.resolutionNote }) : t('status_resolved', { date });
    } else if (c.agentName && c.lastAgentReplyAt) {
      const p = agoParts(c.lastAgentReplyAt, now);
      status = p.unit === 'now' ? t('status_openAgent', { agent: c.agentName }) : t('status_openReplied', { agent: c.agentName, ago: ago(c.lastAgentReplyAt, now) });
    } else if (c.agentName) {
      status = t('status_openAgent', { agent: c.agentName });
    } else {
      status = t('status_waitingAgent');
    }
    let next = '';
    if (open && c.slaDueAt) {
      const due = new Date(c.slaDueAt);
      const d = new Date(now);
      const minutes = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: timeZone(), minute: '2-digit' }).format(due);
      const time = new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: timeZone(), hour: 'numeric', ...(minutes === '00' || minutes === '0' ? {} : { minute: '2-digit' }) }).format(due);
      const when = dayKey(due) === dayKey(d) ? t('today', { time })
        : dayKey(due) === dayKey(new Date(now + 86_400_000)) ? t('tomorrow', { time })
          : t('onDate', { date: formatDate(due, locale, 'date'), time });
      next = t('next_reply', { when });
    }
    // S-176: an open case is with support (info), a resolved one done (success)
    const tone: StatusTone = open ? 'info' : 'success';
    return { status, next, tone, open };
  }, [t, ago, locale]);
}
