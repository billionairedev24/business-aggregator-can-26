import { useRef, useState, type DragEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, Button, DataTable, ErrorState, Field, Select, Skeleton, useFormatters, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { importsQuery, integrationsQuery, useCommitImport, useIntegrationAction, useUploadImport, type CommerceProvider, type Connection, type ImportBatch, type ImportTemplate } from './api';
import { useCatalogueT } from './messages';
import { permissions, portalOf } from './model';
import { downloadErrorReport, downloadTemplate, templatesFor } from './templates';
import { useMessageT } from './validation';
import './catalogue.css';

interface ErrorRow { id: string; row: number; sku: string; error: string }
interface HistoryRow { id: string; file: string; rows: number; result: string; tone: DataTableTone; when: string }

/**
 * /b/$merchantId/listings/bulk — design: bulk. Template download, .xlsx/.csv upload validated before anything changes
 * (report with row numbers), import of the valid rows, upload history, Shopify / Square / Lightspeed / API.
 */
export function BulkUploadScreen() {
  const t = useCatalogueT();
  const mt = useMessageT();
  const shellT = useShellT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const role = useRole();
  const can = permissions(role);
  const portal = portalOf(merchant.type);
  const { date } = useFormatters();
  const templates = templatesFor(portal);
  const [template, setTemplate] = useState<ImportTemplate>(templates[0]!);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [done, setDone] = useState<ImportBatch | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [over, setOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const upload = useUploadImport(merchantId);
  const commit = useCommitImport(merchantId);
  const history = useQuery(importsQuery(merchantId));

  async function onFile(file: File | undefined) {
    if (!file) return;
    setProblem(null); setDone(null);
    try { setBatch(await upload.mutateAsync({ file, template })); }
    catch (e) { setProblem(e instanceof ValidationError ? (mt(e.errors[0]?.message) ?? t('uploadFailed')) : t('uploadFailed')); }
    if (input.current) input.current.value = '';
  }
  async function onImport() {
    if (!batch) return;
    try { const r = await commit.mutateAsync(batch.id); setDone(r); setBatch(null); }
    catch (e) { setProblem(e instanceof ValidationError ? (mt(e.errors[0]?.message) ?? t('actionError')) : t('actionError')); }
  }
  const onDrop = (e: DragEvent) => { e.preventDefault(); setOver(false); if (can.create) void onFile(e.dataTransfer.files[0]); };

  const errorColumns: DataTableColumn<ErrorRow>[] = [
    { key: 'row', label: t('colRow'), type: 'num' }, { key: 'sku', label: t('sku') }, { key: 'error', label: t('colError'), primary: true },
  ];
  const historyColumns: DataTableColumn<HistoryRow>[] = [
    { key: 'file', label: t('colFile'), primary: true }, { key: 'rows', label: t('colRows'), type: 'num' }, { key: 'result', label: t('colResult'), type: 'tag' }, { key: 'when', label: t('colWhen') },
  ];
  const result = (b: ImportBatch) => b.status === 'validated' ? t('resultValidated')
    : b.createCount && b.updateCount ? t('resultBoth', { created: b.createCount, updated: b.updateCount })
    : b.updateCount ? t('resultUpdated', { updated: b.updateCount }) : t('resultImported', { created: b.createCount });
  const historyRows: HistoryRow[] = (history.data ?? []).map(b => ({ id: b.id, file: b.fileName, rows: b.rowCount, result: result(b), tone: b.status === 'imported' ? 'tag-accent' : 'tag-neutral', when: date(b.createdAt) }));

  return (
    <>
      <span className="nl-kicker">{t('bulkKicker', { section: t(`kicker_${portal}`) })}</span>
      <h1 className="nl-page-title" style={{ marginBottom: 8 }}>{t('bulkTitle')}</h1>
      <p className="nl-lede" style={{ margin: '0 0 24px' }}>{t('bulkLede')}</p>
      <div className="nl-cat-editor">
        <div className="nl-cat-main">
          <Field label={t('step1')}>
            <div className="nl-cat-row">
              <Select style={{ maxWidth: 300 }} value={template} options={templates.map(v => ({ value: v, label: t(`tpl_${v}`) }))} onChange={e => setTemplate(e.target.value as ImportTemplate)} />
              <Button variant="secondary" onClick={() => downloadTemplate(template)}>{t('downloadXlsx')}</Button>
            </div>
          </Field>
          <div className="nl-field" role="group" aria-labelledby="upload-label">
            <span className="nl-label" id="upload-label">{t('step2')}</span>
            <div className="nl-cat-drop" data-over={over} onDragOver={e => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)} onDrop={onDrop}>
              {t('dropHere')}<br />
              <Button style={{ marginTop: 12 }} disabled={!can.create || upload.isPending} onClick={() => input.current?.click()}>{upload.isPending ? t('validating') : t('chooseFile')}</Button>
              <input ref={input} type="file" hidden accept=".xlsx,.csv,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" aria-label={t('chooseFile')} data-testid="bulk-input" onChange={e => void onFile(e.target.files?.[0])} />
            </div>
            {!can.create && <span className="nl-hint">{t('viewOnly', { role: shellT(`role_${role}` as Parameters<typeof shellT>[0]) })}</span>}
            {problem && <div role="alert" className="nl-error">{problem}</div>}
          </div>
          <Integrations canManage={can.manage} canSync={can.update} />
        </div>
        <div>
          {done && <div style={{ marginBottom: 16 }}><Alert tone="info" role="status">{t('imported', { created: done.createCount, updated: done.updateCount })}</Alert></div>}
          {batch ? (
            <section aria-label={t('validation', { file: batch.fileName })}>
              <h3 className="nl-cat-h3">{t('validation', { file: batch.fileName })}</h3>
              <div className="nl-cat-stats">
                <div className="nl-cat-stat" data-tone="ok"><div className="nl-cat-stat-n">{batch.createCount}</div>{t('validCreate')}</div>
                <div className="nl-cat-stat"><div className="nl-cat-stat-n">{batch.updateCount}</div>{t('updates')}</div>
                <div className="nl-cat-stat" data-tone="error"><div className="nl-cat-stat-n">{batch.errorCount}</div>{t('errorsFix')}</div>
              </div>
              {batch.errorCount > 0 && (
                <DataTable<ErrorRow> entity={t('entityError')} plural={t('pluralError')} columns={errorColumns} pageSize={5}
                  rows={batch.errors.map((e, i) => ({ id: `e${i}`, row: e.row, sku: e.sku ?? '—', error: mt(e.error) ?? e.error }))}
                  can={{ create: false, update: false, delete: false, export: true }} />
              )}
              <div className="nl-cat-row" style={{ marginTop: 14 }}>
                <Button disabled={!can.create || commit.isPending || batch.createCount + batch.updateCount === 0} onClick={() => void onImport()}>
                  {commit.isPending ? t('importing') : t('importRows', { count: batch.createCount + batch.updateCount })}
                </Button>
                {batch.errorCount > 0 && <Button variant="secondary" onClick={() => downloadErrorReport(batch, [t('colRow'), t('sku'), t('colError')])}>{t('downloadErrors')}</Button>}
                <Button variant="ghost" onClick={() => setBatch(null)}>{t('uploadAnother')}</Button>
              </div>
            </section>
          ) : (
            <section aria-label={t('recentUploads')}>
              <h3 className="nl-cat-h3">{t('recentUploads')}</h3>
              <DataTable<HistoryRow> entity={t('entityImport')} plural={t('pluralImport')} columns={historyColumns} rows={historyRows} rowTones={r => ({ result: r.tone })}
                can={{ create: false, update: false, delete: false, export: true }}
                loading={history.isPending} error={history.isError ? t('importsError') : null} onRetry={() => void history.refetch()} emptyText={t('noUploads')} />
            </section>
          )}
        </div>
      </div>
    </>
  );
}

const PROVIDERS: CommerceProvider[] = ['shopify', 'square', 'lightspeed'];

/** "Or connect": Shopify / Square / Lightspeed sync cards and the API key hint. */
function Integrations({ canManage, canSync }: { canManage: boolean; canSync: boolean }) {
  const t = useCatalogueT();
  const merchantId = useMerchantId();
  const { date } = useFormatters();
  const q = useQuery(integrationsQuery(merchantId));
  const action = useIntegrationAction(merchantId);
  const [failed, setFailed] = useState<CommerceProvider | null>(null);
  const run = (provider: CommerceProvider, a: 'connect' | 'disconnect' | 'sync') => {
    setFailed(null);
    action.mutate({ provider, action: a }, { onError: () => setFailed(provider) });
  };
  const busy = (p: CommerceProvider) => action.isPending && action.variables?.provider === p;
  return (
    <div className="nl-field" role="group" aria-labelledby="connect-label">
      <span className="nl-label" id="connect-label">{t('orConnect')}</span>
      {q.isPending ? <Skeleton height={120} /> : q.isError ? <ErrorState message={t('integrationsError')} onRetry={() => void q.refetch()} /> : (
        <ul className="nl-cat-integrations">
          {PROVIDERS.map(p => {
            const c: Connection = q.data.find(x => x.provider === p) ?? { provider: p, connected: false };
            return (
              <li key={p} className="nl-cat-integration">
                <div><strong>{t(`int_${p}`)}</strong>
                  <div className="nl-small nl-muted">{c.connected ? t('connectedAs', { account: c.accountLabel ?? '' }) : t('notConnected')}</div>
                  {c.connected && <div className="nl-small nl-muted">{c.lastSyncAt ? t('lastSync', { when: date(c.lastSyncAt, 'dateTime'), count: c.lastSyncCount ?? 0 }) : t('neverSynced')}</div>}
                  {failed === p && <div role="alert" className="nl-error">{t('actionError')}</div>}
                </div>
                <div className="nl-cat-row">
                  {c.connected ? <>
                    <Button variant="secondary" disabled={!canSync || busy(p)} onClick={() => run(p, 'sync')}>{busy(p) && action.variables?.action === 'sync' ? t('syncing') : t('syncNow')}</Button>
                    {canManage && <Button variant="ghost" disabled={busy(p)} onClick={() => run(p, 'disconnect')}>{t('disconnect')}</Button>}
                  </> : <Button variant="secondary" disabled={!canManage || busy(p)} title={canManage ? undefined : t('ownerConnects')} onClick={() => run(p, 'connect')}>{t('connect')}</Button>}
                </div>
              </li>
            );
          })}
          <li className="nl-cat-integration">
            <div><strong>{t('intApi')}</strong><div className="nl-small nl-muted">{t('apiHint')}</div></div>
            <Link className="btn btn-secondary" to="/b/$merchantId/settings" params={{ merchantId }} search={{ tab: 'api' } as never}>{t('intApi')}</Link>
          </li>
        </ul>
      )}
    </div>
  );
}
