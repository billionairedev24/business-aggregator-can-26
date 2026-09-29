import { useEffect, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Dialog, EmptyState, ErrorState, Field, PageSkeleton, Select, Skeleton, TextInput, useFormatters } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId, useRole } from '../shell/api';
import { groupsQuery, menuQuery, menusQuery, useAddSection, useCreateMenu, useImportCsv, useMenuState, useRenameSection, useReorderSections, useSoldOut, type MenuDetail, type MenuItem } from './api';
import { ItemPhoto } from './ItemPhoto';
import { MenuItemEditor } from './MenuItemEditor';
import { useKitchenT, type KitchenT } from './messages';
import { menuOptionText } from './model';
import './Kitchen.css';

type Editing = { kind: 'item'; item?: MenuItem; sectionId?: string } | { kind: 'section'; sectionId?: string; name?: string } | null;
const NEW_MENU = '__new__';

/** Kitchen · Menu builder (design 02 lines 841–876): menus → sections → items, sold out, editor, import, publish. */
export function MenuBuilderScreen() {
  const merchantId = useMerchantId();
  const role = useRole();
  const t = useKitchenT();
  const canEdit = role !== 'bookkeeper';
  const menus = useQuery(menusQuery(merchantId));
  const [menuId, setMenuId] = useState('');
  const current = menuId || menus.data?.[0]?.id || '';
  const menu = useQuery(menuQuery(merchantId, current));
  const groups = useQuery(groupsQuery(merchantId));
  const state = useMenuState(merchantId);
  const [editing, setEditing] = useState<Editing>(null);
  const [newMenu, setNewMenu] = useState(false);
  const [importing, setImporting] = useState(false);
  useEffect(() => setEditing(null), [current]);

  if (menus.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (menus.isError) return <><span className="nl-k-kicker">{t('menuKicker')}</span><ErrorState message={t('loadError')} onRetry={() => void menus.refetch()} /></>;
  if (menus.data.length === 0) {
    return (
      <div className="nl-kitchen">
        <span className="nl-k-kicker">{t('menuKicker')}</span>
        <EmptyState action={canEdit ? <button type="button" className="btn btn-primary" onClick={() => setNewMenu(true)}>{t('createMenu')}</button> : undefined}>{t('emptyMenus')}</EmptyState>
        <NewMenuDialog open={newMenu} merchantId={merchantId} onClose={id => { setNewMenu(false); if (id) setMenuId(id); }} />
      </div>
    );
  }
  const summary = menus.data.find(m => m.id === current) ?? menus.data[0]!;
  const d = menu.data;
  return (
    <div className="nl-kitchen">
      <div className="nl-k-head nl-k-head-tight">
        <div>
          <span className="nl-k-kicker">{t('menuKicker')}</span>
          <h1 className="nl-k-title">{t('menuTitle', { name: summary.name, status: t(`ms_${summary.status}`) })}</h1>
        </div>
        <div className="nl-k-actions">
          <Select aria-label={t('menuPicker')} className="nl-k-picker" value={current} onChange={e => (e.target.value === NEW_MENU ? setNewMenu(true) : setMenuId(e.target.value))}
            options={[...menus.data.map(m => ({ value: m.id, label: menuOptionText(m, t) })), ...(canEdit ? [{ value: NEW_MENU, label: t('newMenuOpt') }] : [])]} />
          {canEdit ? <>
            <button type="button" className="btn btn-secondary" onClick={() => setImporting(true)}>{t('importBtn')}</button>
            <button type="button" className="btn btn-primary" onClick={() => setEditing({ kind: 'section' })}>{t('addSection')}</button>
            {summary.status === 'live'
              ? <button type="button" className="btn btn-ghost" disabled={state.isPending} onClick={() => state.mutate({ menuId: current, action: 'hide' })}>{t('hideMenu')}</button>
              : <button type="button" className="btn btn-ghost" disabled={state.isPending || d?.kitchenApproved === false} onClick={() => state.mutate({ menuId: current, action: 'publish' })}>{t('publishMenu')}</button>}
          </> : null}
        </div>
      </div>
      <p className="nl-k-lede">{t('menuLede')}</p>
      {d && !d.kitchenApproved ? <Alert tone="highlight">{t('notApproved')}</Alert> : null}
      {state.isError ? <Alert tone="error" role="alert">{state.error.message || t('saveError')}</Alert> : null}
      <div className="nl-k-builder">
        <div>
          {menu.isPending ? <div className="nl-k-stack">{[0, 1, 2].map(i => <Skeleton key={i} height={64} />)}</div>
            : menu.isError ? <ErrorState message={t('loadError')} onRetry={() => void menu.refetch()} />
            : d!.sections.length === 0 ? <EmptyState action={canEdit ? <button type="button" className="btn btn-primary" onClick={() => setEditing({ kind: 'section' })}>{t('addSection')}</button> : undefined}>{t('emptyMenu')}</EmptyState>
            : <Sections merchantId={merchantId} menu={d!} canEdit={canEdit} onEdit={setEditing} />}
        </div>
        <aside className="nl-k-aside">
          {editing && d ? (
            <div className="nl-k-panel">
              <div className="nl-k-panel-head">
                <h3>{editing.kind === 'section' ? (editing.sectionId ? t('editSection') : t('newSection')) : editing.item ? t('editItem', { name: editing.item.name }) : t('newItem')}</h3>
                <button type="button" className="btn btn-ghost nl-k-x" aria-label={t('close')} onClick={() => setEditing(null)}>×</button>
              </div>
              {editing.kind === 'section'
                ? <SectionEditor key={editing.sectionId ?? 'new'} merchantId={merchantId} menuId={d.id} sectionId={editing.sectionId} name={editing.name} onClose={() => setEditing(null)} />
                : <MenuItemEditor key={editing.item?.id ?? `new-${editing.sectionId}`} merchantId={merchantId} menu={d} groups={groups.data ?? []} item={editing.item} sectionId={editing.sectionId} canDelete={role === 'owner'} onClose={() => setEditing(null)} />}
            </div>
          ) : null}
          <div className="nl-k-note"><strong>{t('vettingBox')}</strong> {t('vettingText')}</div>
          <div className="nl-k-note"><strong>{t('whereBox')}</strong> {t('whereText')}</div>
        </aside>
      </div>
      <NewMenuDialog open={newMenu} merchantId={merchantId} onClose={id => { setNewMenu(false); if (id) setMenuId(id); }} />
      <ImportDialog open={importing} merchantId={merchantId} menuId={current} onClose={() => setImporting(false)} />
    </div>
  );
}

function Sections({ merchantId, menu, canEdit, onEdit }: { merchantId: string; menu: MenuDetail; canEdit: boolean; onEdit: (e: Editing) => void }) {
  const t = useKitchenT();
  const f = useFormatters();
  const soldOut = useSoldOut(merchantId, menu.id);
  const reorder = useReorderSections(merchantId);
  const [order, setOrder] = useState<string[] | null>(null);
  const [dragging, setDragging] = useState<string | null>(null);
  const ids = order ?? menu.sections.map(s => s.id);
  useEffect(() => setOrder(null), [menu]);
  const sections = ids.map(id => menu.sections.find(s => s.id === id)).filter(s => !!s);
  const move = (id: string, to: number) => {
    const next = ids.filter(x => x !== id);
    next.splice(Math.max(0, Math.min(next.length, to)), 0, id);
    if (next.join() === ids.join()) return;
    setOrder(next);
    reorder.mutate({ menuId: menu.id, sectionIds: next }, { onError: () => setOrder(null) });
  };
  const onHandleKey = (id: string, i: number) => (e: KeyboardEvent) => {
    if (e.key === 'ArrowUp') { e.preventDefault(); move(id, i - 1); }
    if (e.key === 'ArrowDown') { e.preventDefault(); move(id, i + 1); }
  };
  return (
    <>
      {reorder.isError ? <Alert tone="error" role="alert">{t('saveError')}</Alert> : null}
      {soldOut.isError ? <Alert tone="error" role="alert">{t('saveError')}</Alert> : null}
      {sections.map((sec, i) => (
        <section key={sec.id} className="nl-k-section" aria-label={sec.name}
          draggable={canEdit} onDragStart={() => setDragging(sec.id)} onDragEnd={() => setDragging(null)}
          onDragOver={e => { if (dragging) e.preventDefault(); }} onDrop={() => { if (dragging) move(dragging, i); setDragging(null); }}>
          <div className="nl-k-section-head">
            <h2>
              {canEdit ? <button type="button" className="nl-k-handle" aria-label={t('reorder', { name: sec.name })} onKeyDown={onHandleKey(sec.id, i)}>⋮⋮</button> : <span className="nl-k-handle" aria-hidden>⋮⋮</span>}
              {canEdit ? <button type="button" className="nl-k-linkish" aria-label={t('renameSection', { name: sec.name })} onClick={() => onEdit({ kind: 'section', sectionId: sec.id, name: sec.name })}>{sec.name}</button> : sec.name}
            </h2>
            <span className="nl-k-muted">{t('itemCount', { n: sec.items.length })}{canEdit ? <> · <button type="button" className="nl-k-link" aria-label={t('addItemIn', { section: sec.name })} onClick={() => onEdit({ kind: 'item', sectionId: sec.id })}>{t('addItem')}</button></> : null}</span>
          </div>
          {sec.items.length === 0 ? <p className="nl-k-muted nl-k-empty-line">{t('emptySection')}</p> : null}
          {sec.items.map(it => (
            <div key={it.id} className="nl-k-item">
              <ItemPhoto merchantId={merchantId} itemId={it.id} hasPhoto={it.hasPhoto} version={it.updatedAt} />
              <div className="nl-k-item-body">
                <div className="nl-k-item-title"><strong>{it.name}</strong><span>{f.money(it.priceCents)}</span>{it.visibility !== 'live' ? <span className={`tag ${it.visibility === 'draft' ? 'tag-neutral' : 'tag-accent-2'} nl-k-small-tag`}>{t(`vis_${it.visibility}`)}</span> : null}</div>
                <div className="nl-k-item-desc">{it.description || t('noDesc')}</div>
                <div className="nl-k-item-meta">{t('modifiers', { list: it.modifierGroups.map(g => g.name).join(', ') || t('none') })}{tags(it, t).length ? ' · ' : ''}{tags(it, t).map(x => <span key={x} className="tag tag-neutral nl-k-mini-tag">{x}</span>)}</div>
              </div>
              <div className="nl-k-item-actions">
                <button type="button" className={`nl-k-avail${it.soldOut ? '' : ' is-on'}`} aria-pressed={!it.soldOut} disabled={!canEdit} onClick={() => soldOut.mutate({ id: it.id, soldOut: !it.soldOut })}>{it.soldOut ? t('soldOut') : t('available')}</button>
                {canEdit ? <button type="button" className="btn btn-ghost nl-k-edit" aria-label={`${t('edit')} ${it.name}`} onClick={() => onEdit({ kind: 'item', item: it })}>{t('edit')}</button> : null}
              </div>
            </div>
          ))}
        </section>
      ))}
    </>
  );
}

/** Tags under an item: dietary labels, then "Contains …" per declared allergen. */
export function tags(it: MenuItem, t: KitchenT): string[] {
  return [...it.dietary.map(d => t(`dt_${d}` as 'dt_vegan')), ...(it.allergens ?? []).map(a => t('contains', { allergen: t(`al_${a}` as 'al_milk').toLowerCase() }))];
}

function SectionEditor({ merchantId, menuId, sectionId, name: initial, onClose }: { merchantId: string; menuId: string; sectionId?: string; name?: string; onClose: () => void }) {
  const t = useKitchenT();
  const add = useAddSection(merchantId);
  const rename = useRenameSection(merchantId);
  const [name, setName] = useState(initial ?? '');
  const [tried, setTried] = useState(false);
  const m = sectionId ? rename : add;
  const local = !name.trim() ? t('v_sectionName') : name.trim().length > 60 ? t('v_at60') : undefined;
  const server = m.error instanceof ValidationError ? m.error.errors[0]?.message : undefined;
  const submit = () => {
    setTried(true);
    if (local) return;
    if (sectionId) rename.mutate({ menuId, sectionId, name: name.trim() }, { onSuccess: onClose });
    else add.mutate({ menuId, name: name.trim() }, { onSuccess: onClose });
  };
  return (
    <form className="nl-k-editor-form" noValidate onSubmit={e => { e.preventDefault(); submit(); }}>
      <Field label={t('sectionName')} error={(tried ? local : undefined) ?? server}><TextInput value={name} placeholder={t('sectionNamePh')} onChange={e => setName(e.target.value)} /></Field>
      {m.isError && !server ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      <div className="nl-k-editor-actions"><button type="submit" className="btn btn-primary" disabled={m.isPending}>{sectionId ? t('saveSection') : t('addSection')}</button></div>
    </form>
  );
}

function NewMenuDialog({ open, merchantId, onClose }: { open: boolean; merchantId: string; onClose: (id?: string) => void }) {
  const t = useKitchenT();
  const create = useCreateMenu(merchantId);
  const [name, setName] = useState('');
  const [tried, setTried] = useState(false);
  const local = !name.trim() ? t('v_menuName') : name.trim().length > 60 ? t('v_at60') : undefined;
  const server = create.error instanceof ValidationError ? create.error.errors[0]?.message : undefined;
  const submit = () => { setTried(true); if (!local) create.mutate(name.trim(), { onSuccess: m => { setName(''); setTried(false); onClose(m.id); } }); };
  return (
    <Dialog open={open} onClose={() => onClose()} title={t('newMenu')} actions={<><button type="button" className="btn btn-ghost" onClick={() => onClose()}>{t('cancel')}</button><button type="button" className="btn btn-primary" disabled={create.isPending} onClick={submit}>{t('createMenuBtn')}</button></>}>
      <Field label={t('menuName')} error={(tried ? local : undefined) ?? server}><TextInput value={name} placeholder={t('menuNamePh')} onChange={e => setName(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') submit(); }} /></Field>
      {create.isError && !server ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
    </Dialog>
  );
}

function ImportDialog({ open, merchantId, menuId, onClose }: { open: boolean; merchantId: string; menuId: string; onClose: () => void }) {
  const t = useKitchenT();
  const run = useImportCsv(merchantId);
  const [file, setFile] = useState<File | null>(null);
  const [tried, setTried] = useState(false);
  const rowErrors = run.error instanceof ValidationError ? run.error.errors.map(e => {
    const row = /^rows\[(\d+)\]/.exec(e.field)?.[1];
    return row ? t('importRow', { row, message: e.message }) : e.message;
  }) : [];
  const close = () => { setFile(null); setTried(false); run.reset(); onClose(); };
  return (
    <Dialog open={open} onClose={close} title={t('importTitle')} width={560}
      actions={<><button type="button" className="btn btn-ghost" onClick={close}>{run.isSuccess ? t('close') : t('cancel')}</button>{run.isSuccess ? null : <button type="button" className="btn btn-primary" disabled={run.isPending} onClick={() => { setTried(true); if (file) run.mutate({ menuId, file }); }}>{t('importGo')}</button>}</>}>
      <p className="nl-k-muted">{t('importHelp')}</p>
      <Field label={t('importCsv')} error={tried && !file ? t('chooseFile') : undefined}><input type="file" className="input" accept=".csv,text/csv" onChange={e => { setFile(e.target.files?.[0] ?? null); run.reset(); }} /></Field>
      {rowErrors.length ? <div role="alert" className="nl-error"><ul className="nl-k-errlist">{rowErrors.map((m, i) => <li key={i}>{m}</li>)}</ul></div> : null}
      {run.isError && !rowErrors.length ? <div role="alert" className="nl-error">{t('saveError')}</div> : null}
      {run.isSuccess ? <Alert tone="info" role="status">{t('importDone', { n: run.data.itemsCreated })}</Alert> : null}
      <p className="nl-k-muted">{t('importPos')}</p>
    </Dialog>
  );
}
