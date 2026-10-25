import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Dialog, Field, Select, TextInput } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { sellersQuery } from '../sellers/api';
import { useEnrol, type Market } from './api';
import { useEnrolT } from './enrolMessages';

const codeOf = (e: unknown) => (e instanceof ApiError && e.body && typeof e.body === 'object' ? (e.body as { code?: string }).code : undefined);

function useDebounced(value: string, ms = 250) {
  const [v, set] = useState(value);
  useEffect(() => { const id = setTimeout(() => set(value), ms); return () => clearTimeout(id); }, [value, ms]);
  return v;
}

/**
 * S-158: enrol a business already on the platform into a market's pilot (S-120's `POST /enrolments`): find it by name
 * in the sellers directory, choose the market, enrol. The api validates, hides it from search until the market is live
 * and audit-logs the enrolment.
 */
export function EnrolDialog({ markets, market, onClose, onDone }: { markets: Market[]; market?: string; onClose: () => void; onDone: (name: string, pilotId: string) => void }) {
  const t = useEnrolT();
  const enrol = useEnrol();
  const [q, setQ] = useState('');
  const typed = useDebounced(q.trim());
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null);
  const [marketId, setMarketId] = useState(market ?? markets[0]?.id ?? '');
  const found = useQuery({ ...sellersQuery({ q: typed }), enabled: typed.length >= 2 && !picked });
  const errors = enrol.error instanceof ValidationError ? enrol.error.byField() : {};
  const other = enrol.error && !(enrol.error instanceof ValidationError) ? (codeOf(enrol.error) ? enrol.error.message : t('failed')) : null;
  const items = found.data?.items.slice(0, 8) ?? [];
  return (
    <Dialog open onClose={onClose} title={t('title')}
      actions={<><Button variant="tertiary" onClick={onClose}>{t('cancel')}</Button>
        <Button disabled={!picked || !marketId || enrol.isPending}
          onClick={() => picked && enrol.mutate({ businessId: picked.id, marketId }, { onSuccess: d => onDone(picked.name, d.row.id) })}>{t('confirm')}</Button></>}>
      <p className="nl-q-lede">{t('lede')}</p>
      {picked ? (
        <p className="nl-q-note">{t('picked', { name: picked.name })} <Button variant="link" onClick={() => setPicked(null)}>{t('search')}</Button></p>
      ) : (
        <Field label={t('search')} hint={t('searchHint')} error={errors.businessId}>
          <TextInput value={q} autoComplete="off" onChange={e => setQ(e.target.value)} />
        </Field>
      )}
      {!picked && typed.length >= 2 ? (
        found.isPending ? <p className="bo-muted" role="status">{t('loading')}</p>
          : items.length === 0 ? <p className="bo-muted">{t('noMatch')}</p>
          : <ul className="nl-q-list">{items.map(s => (
            <li key={s.id}><Button variant="tertiary" aria-label={t('pick', { name: s.name })} onClick={() => setPicked({ id: s.id, name: s.name })}>{s.name}</Button>
              <span className="bo-muted"> · {[s.city, s.province].filter(Boolean).join(', ')}</span></li>
          ))}</ul>
      ) : null}
      <Field label={t('market')} error={errors.marketId}>
        <Select value={marketId} onChange={e => setMarketId(e.target.value)} options={markets.map(m => ({ value: m.id, label: `${m.city} · ${m.province}` }))} />
      </Field>
      {other ? <p role="alert" className="nl-q-error">{other}</p> : null}
    </Dialog>
  );
}
