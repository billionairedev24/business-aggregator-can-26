import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, Field, Select, Skeleton, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useShellT, type ShellKey } from '../shell/messages';
import { auditQuery, type AuditFilter, type AuditRow, type Member } from './api';
import { useTeamT, type TeamKey } from './messages';

const AREAS = ['console', 'merchant', 'payments', 'region', 'catalogue', 'trust', 'support', 'api_key', 'fulfilment'] as const;

/**
 * The audit log (S-96): newest first, 50 at a time, filtered by action area, person, business and dates (`mine` = my
 * own trail, unfiltered). Entries show codes and ids as the log keeps them — never personal data.
 */
export function AuditLog({ mine = false, staff = [] }: { mine?: boolean; staff?: readonly Member[] }) {
  const t = useTeamT();
  const [filter, setFilter] = useState<AuditFilter>({});
  const [pages, setPages] = useState<string[]>([]);
  const before = pages.at(-1);
  const query = useQuery(auditQuery(filter, before, mine));
  const set = (patch: Partial<AuditFilter>) => { setFilter(f => ({ ...f, ...patch })); setPages([]); };
  const error = query.error instanceof ValidationError ? Object.values(query.error.byField())[0] : undefined;
  return (
    <div className="nl-tm-audit">
      {!mine ? (
        <div className="nl-tm-filters">
          <Field label={t('f_action')}>
            <Select value={filter.action ?? ''} placeholder={t('anyAction')} onChange={e => set({ action: e.target.value || undefined })}
              options={AREAS.map(a => ({ value: `${a}.`, label: t(`a_${a}` as TeamKey) }))} />
          </Field>
          <Field label={t('f_actor')}>
            <Select value={filter.actor ?? ''} placeholder={t('anyone')} onChange={e => set({ actor: e.target.value || undefined })}
              options={staff.map(m => ({ value: m.id, label: m.name }))} />
          </Field>
          <Field label={t('f_business')}>
            <TextInput value={filter.business ?? ''} onChange={e => set({ business: e.target.value.trim() || undefined })} />
          </Field>
          <Field label={t('f_from')}>
            <TextInput type="date" value={filter.from?.slice(0, 10) ?? ''} onChange={e => set({ from: e.target.value ? `${e.target.value}T00:00:00Z` : undefined })} />
          </Field>
          <Field label={t('f_to')}>
            <TextInput type="date" value={filter.to?.slice(0, 10) ?? ''} onChange={e => set({ to: e.target.value ? `${e.target.value}T23:59:59Z` : undefined })} />
          </Field>
        </div>
      ) : null}
      {error ? <p role="alert" className="nl-tm-error">{error}</p> : null}
      {query.isPending ? <Skeleton height={160} /> : query.isError && !error ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} /> : (
        <>
          {query.data?.items.length ? <ol className="nl-tm-log">{query.data.items.map(e => <Entry key={e.id} entry={e} />)}</ol> : <p className="nl-tm-sub">{t('noEntries')}</p>}
          <div className="nl-tm-actions">
            {pages.length ? <Button variant="ghost" onClick={() => setPages([])}>{t('newest')}</Button> : null}
            {query.data?.next ? <Button variant="secondary" onClick={() => setPages(p => [...p, query.data!.next!])}>{t('older')}</Button> : null}
          </div>
        </>
      )}
    </div>
  );
}

function Entry({ entry: e }: { entry: AuditRow }) {
  const t = useTeamT();
  const shell = useShellT();
  const fmt = useFormatters();
  const role = (e.role ?? '').split(',').filter(Boolean).map(r => (['admin', 'trust_safety', 'dispatch', 'finance', 'support', 'support_lead', 'analyst', 'privacy'].includes(r) ? shell(`role_${r}` as ShellKey) : r)).join(', ');
  const who = e.actorName ?? (e.actorId === 'system' || !e.actorId ? t('system') : e.actorId);
  const target = [e.businessName, e.targetType && e.targetId ? `${e.targetType} ${e.targetId}` : undefined].filter(Boolean).join(' · ');
  return (
    <li className="nl-tm-entry">
      <time dateTime={e.at}>{fmt.date(e.at, 'dateTime')}</time>
      <span><strong>{who}</strong>{role ? ` (${role})` : ''} · <code>{e.action}</code>{target ? <span className="nl-tm-sub"> · {target}</span> : null}</span>
    </li>
  );
}
