import { useState } from 'react';
import { BookmarkSimple, CaretDown } from '@phosphor-icons/react';
import { Button, Dialog, Field, Menu, TextInput, defineMessages, type MenuEntry, type StatusTone, type DataTableTone } from '@northline/ui';

/*
 * The console's table pages (S-177, design/revamp/a.html screen 9): every queue is views as tabs with counts, quick
 * filters in the toolbar (a department segmented control, filter chips), saved views, dense rows, status tags and a
 * bulk bar whose destructive action is red. The tabs and chips are @northline/ui's; this file holds what only the
 * console needs: saved views (this device) and the status tone helper.
 */

const useT = defineMessages({
  en: {
    saved: 'Saved views', save: 'Save this view…', forget: 'Forget “{name}”', none: 'No saved view yet', name: 'Name of the view', cancel: 'Cancel', confirm: 'Save view',
    hint: 'The tab, filters and search you have now. Saved on this device.',
  },
  fr: {
    saved: 'Vues enregistrées', save: 'Enregistrer cette vue…', forget: 'Oublier « {name} »', none: 'Aucune vue enregistrée', name: 'Nom de la vue', cancel: 'Annuler', confirm: 'Enregistrer la vue',
    hint: 'L’onglet, les filtres et la recherche actuels. Enregistrée sur cet appareil.',
  },
});

/** A view's state: the screen's own URL search (tab, quick filters, search), never an open record. */
export type ViewState = Record<string, string | boolean | undefined>;
interface Saved { name: string; state: ViewState }

const key = (screen: string) => `nl.console.views.${screen}`;
const load = (screen: string): Saved[] => {
  try {
    const v = JSON.parse(localStorage.getItem(key(screen)) ?? '[]') as Saved[];
    return Array.isArray(v) ? v.filter(x => x && typeof x.name === 'string' && x.state && typeof x.state === 'object') : [];
  } catch { return []; }
};
const store = (screen: string, list: Saved[]) => { try { localStorage.setItem(key(screen), JSON.stringify(list)); } catch { /* private mode: kept for the visit */ } };
const clean = (s: ViewState) => Object.fromEntries(Object.entries(s).filter(([, v]) => v !== undefined && v !== '' && v !== false)) as ViewState;
const same = (a: ViewState, b: ViewState) => JSON.stringify(Object.entries(clean(a)).sort()) === JSON.stringify(Object.entries(clean(b)).sort());

/**
 * Saved views of a table page: the viewer names the tab + filters + search they have and gets back to it from this
 * menu. A per-viewer convenience kept on this device (localStorage); nothing is sent to the api.
 */
export function SavedViews({ screen, current, onApply }: { screen: string; current: ViewState; onApply: (s: ViewState) => void }) {
  const t = useT();
  const [list, setList] = useState(() => load(screen));
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState('');
  const active = list.find(v => same(v.state, current));
  const update = (next: Saved[]) => { setList(next); store(screen, next); };
  const items: MenuEntry[] = [
    ...(list.length ? list.map((v): MenuEntry => ({ label: v.name, checked: v === active, onSelect: () => onApply(v.state) })) : [{ kind: 'custom', render: <p className="nl-views-none">{t('none')}</p> } as MenuEntry]),
    { kind: 'separator' },
    { label: t('save'), onSelect: () => { setName(''); setNaming(true); } },
    ...(active ? [{ label: t('forget', { name: active.name }), onSelect: () => update(list.filter(v => v !== active)) }] : []),
  ];
  const save = () => {
    const n = name.trim();
    if (!n) return;
    update([...list.filter(v => v.name !== n), { name: n, state: clean(current) }]);
    setNaming(false);
  };
  return (
    <>
      <Menu label={t('saved')} align="end" width={260} items={items} trigger={({ props }) => (
        <button type="button" className="btn btn-secondary" {...props}>
          <BookmarkSimple size={16} aria-hidden />{active ? active.name : t('saved')}<CaretDown size={13} aria-hidden />
        </button>
      )} />
      <Dialog open={naming} onClose={() => setNaming(false)} title={t('save').replace('…', '')}
        actions={<><Button variant="secondary" onClick={() => setNaming(false)}>{t('cancel')}</Button><Button disabled={!name.trim()} onClick={save}>{t('confirm')}</Button></>}>
        <form onSubmit={e => { e.preventDefault(); save(); }}>
          <Field label={t('name')} hint={t('hint')}><TextInput value={name} maxLength={40} autoFocus onChange={e => setName(e.target.value)} /></Field>
        </form>
      </Dialog>
    </>
  );
}

/** A status tone as a DataTable tag tone: a StatusTag (dot + the semantic pair). */
export const statusTone = (tone: StatusTone): DataTableTone => `status-${tone}`;
