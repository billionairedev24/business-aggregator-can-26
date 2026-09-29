/**
 * Sample data from design/02 Provider Studio.dc.html (`DT` object and `_tables()`), typed.
 * Money is stored in cents. Used by the stories only.
 */
import { EyeSlash, Eye, Package } from '@phosphor-icons/react';
import type { DataTableAction, DataTableColumn, DataTablePermissions, DataTableTone } from './types';

export const CAN = {
  /** record kind: owner/staff edit, only owner deletes */
  record: (role: string): DataTablePermissions => ({ create: role !== 'bookkeeper', update: role !== 'bookkeeper', delete: role === 'owner', export: true }),
  ledger: { create: false, update: false, delete: false, export: true } satisfies DataTablePermissions,
  owner: (role: string): DataTablePermissions => ({ create: role === 'owner', update: role === 'owner', delete: role === 'owner', export: true }),
};

// ── products / listings ─────────────────────────────────────────────────────
export interface Listing { id: string; name: string; sku: string; type: string; price: number; stock: number | null; sales: number; vet: string; vetTone: DataTableTone; live: string }
const raw: [string, string, string, number, number | null, number, string, DataTableTone, boolean][] = [
  ['Brake inspection', 'SVC-BI', 'Service', 89, null, 31, 'Approved', 'tag-accent', true],
  ['Oil & filter', 'SVC-OF', 'Service', 79, null, 28, 'Approved', 'tag-accent', true],
  ['Diagnostic scan', 'SVC-DG', 'Service', 120, null, 14, 'Approved', 'tag-accent', true],
  ['Pre-purchase inspection', 'SVC-PP', 'Service', 160, null, 9, 'Approved', 'tag-accent', true],
  ['Winter tire swap', 'SVC-TS', 'Service', 99, null, 0, 'Approved', 'tag-accent', false],
  ['Brake pads · ceramic (front)', 'BP-CER-F', 'Product', 68, 14, 12, 'Approved', 'tag-accent', true],
  ['Synthetic oil 5W-30 · 5 L', 'OIL-5W30', 'Product', 42, 3, 19, 'Approved', 'tag-accent', true],
  ['Wiper blades · 22"', 'WB-22', 'Product', 19, 22, 7, 'Pending · 2 min', 'tag-accent-2', false],
  ['Cabin air filter', 'CAF-01', 'Product', 24, 0, 4, 'Draft', 'tag-neutral', false],
];
export const listings: Listing[] = raw.map((p) => ({
  id: p[1], name: p[0], sku: p[1], type: p[2], price: p[3] * 100, stock: p[4], sales: p[5], vet: p[6], vetTone: p[7], live: p[6] === 'Draft' ? 'Draft' : p[8] ? 'Live' : 'Hidden',
}));
export const listingColumns: DataTableColumn<Listing>[] = [
  { key: 'name', label: 'Listing', sub: 'sku', subLabel: 'SKU', required: true },
  { key: 'type', label: 'Type', type: 'tag', options: ['Service', 'Product'], tones: { Service: 'tag-neutral', Product: 'tag-neutral' } },
  { key: 'price', label: 'Price', type: 'money', required: true },
  { key: 'stock', label: 'Stock', type: 'num' },
  { key: 'sales', label: '30-day sales', type: 'num', editable: false },
  { key: 'vet', label: 'Vetting', type: 'tag', editable: false },
  { key: 'live', label: 'Status', type: 'tag', options: ['Live', 'Hidden', 'Draft'], tones: { Live: 'tag-accent', Hidden: 'tag-neutral', Draft: 'tag-neutral' } },
];
export const listingActions: DataTableAction<Listing>[] = [
  { id: 'pub', label: 'Publish', icon: Eye, perm: 'update', when: { key: 'live', in: ['Hidden'] } },
  { id: 'hide', label: 'Hide', icon: EyeSlash, perm: 'update', when: { key: 'live', in: ['Live'] } },
];

// ── French listing copy (fr-CA) ─────────────────────────────────────────────
const frType: Record<string, string> = { Service: 'Service', Product: 'Produit' };
const frLive: Record<string, string> = { Live: 'En ligne', Hidden: 'Masquée', Draft: 'Brouillon' };
const frVet: Record<string, string> = { Approved: 'Approuvée', 'Pending · 2 min': 'En attente · 2 min', Draft: 'Brouillon' };
const frName: Record<string, string> = {
  'Brake inspection': 'Inspection des freins', 'Oil & filter': 'Huile et filtre', 'Diagnostic scan': 'Diagnostic électronique', 'Pre-purchase inspection': 'Inspection avant achat',
  'Winter tire swap': 'Pose de pneus d’hiver', 'Brake pads · ceramic (front)': 'Plaquettes de frein · céramique (avant)', 'Synthetic oil 5W-30 · 5 L': 'Huile synthétique 5W-30 · 5 L',
  'Wiper blades · 22"': 'Balais d’essuie-glace · 22 po', 'Cabin air filter': 'Filtre à air d’habitacle',
};
export const listingsFr: Listing[] = listings.map((l) => ({ ...l, name: frName[l.name] ?? l.name, type: frType[l.type]!, live: frLive[l.live]!, vet: frVet[l.vet]! }));
export const listingColumnsFr: DataTableColumn<Listing>[] = [
  { key: 'name', label: 'Annonce', sub: 'sku', subLabel: 'UGS', required: true },
  { key: 'type', label: 'Type', type: 'tag', options: ['Service', 'Produit'], tones: { Service: 'tag-neutral', Produit: 'tag-neutral' } },
  { key: 'price', label: 'Prix', type: 'money', required: true },
  { key: 'stock', label: 'Stock', type: 'num' },
  { key: 'sales', label: 'Ventes (30 j)', type: 'num', editable: false },
  { key: 'vet', label: 'Contrôle', type: 'tag', editable: false },
  { key: 'live', label: 'Statut', type: 'tag', options: ['En ligne', 'Masquée', 'Brouillon'], tones: { 'En ligne': 'tag-accent', Masquée: 'tag-neutral', Brouillon: 'tag-neutral' } },
];
export const listingActionsFr: DataTableAction<Listing>[] = [
  { id: 'pub', label: 'Publier', icon: Eye, perm: 'update', when: { key: 'live', in: ['Masquée'] } },
  { id: 'hide', label: 'Masquer', icon: EyeSlash, perm: 'update', when: { key: 'live', in: ['En ligne'] } },
];

// ── orders (seller) ─────────────────────────────────────────────────────────
export interface Order { id: string; who: string; items: string; run: string; total: number; state: string }
export const orders: Order[] = (
  [
    ['NL-48213', 'A. Osei', 'Wiper blades ×2', 'Tonight 6 pm', 3990, 'To pack'],
    ['NL-48219', 'D. Kowalski', 'Brake pads (front)', 'Tonight 6 pm', 7140, 'To pack'],
    ['NL-48224', 'S. Bouchard', 'Synthetic oil 5 L', 'Tonight 6 pm', 4410, 'To pack'],
    ['NL-48230', 'L. Cardinal', 'Wiper blades, oil', 'Tomorrow 8 am', 6405, 'To pack'],
    ['NL-48201', 'M. Tran', 'Brake pads (rear)', 'Tonight 6 pm', 7140, 'Awaiting pickup'],
    ['NL-48188', 'P. Nguyen', 'Wiper blades', 'Delivered 6:40', 1995, 'Issue · wrong size'],
  ] as const
).map(([id, who, items, run, total, state]) => ({ id, who, items, run, total, state }));
export const orderTone = (o: Order): { state: DataTableTone } => ({ state: o.state === 'To pack' ? 'tag-accent' : o.state.startsWith('Issue') ? 'tag-accent-2' : 'tag-neutral' });
export const orderColumns: DataTableColumn<Order>[] = [
  { key: 'id', label: 'Order' },
  { key: 'who', label: 'Customer' },
  { key: 'items', label: 'Items' },
  { key: 'run', label: 'Run', filter: 'facet' },
  { key: 'total', label: 'Total', type: 'money' },
  { key: 'state', label: 'Status', type: 'tag' },
];
export const orderActions: DataTableAction<Order>[] = [{ id: 'pack', label: 'Mark packed', icon: Package, perm: 'update', inline: true, when: { key: 'state', in: ['To pack'] } }];

// ── earnings (ledger) ───────────────────────────────────────────────────────
export interface LedgerEntry { id: string; job: string; customer: string; gross: number; fee: number; net: number; state: string; tone: DataTableTone }
export const earnings: LedgerEntry[] = (
  [
    ['Brake pads · Sep 6', 'D. Kowalski', 24700, 2223, 22477, 'Released', 'tag-accent'],
    ['Diagnostic · Sep 6', 'M. Tran', 12000, 1080, 10920, 'Escrow · 31 h', 'tag-neutral'],
    ['Wiper blades ×2 · NL-48190', 'S. Bouchard', 3800, 342, 3458, 'Released', 'tag-accent'],
    ['Pre-purchase · Sep 4', 'A. Osei', 16000, 1440, 14560, 'Disputed · on hold', 'tag-accent-2'],
    ['Oil & filter · Sep 5', 'S. Bouchard', 7900, 711, 7189, 'Released', 'tag-accent'],
  ] as const
).map(([job, customer, gross, fee, net, state, tone], i) => ({ id: `r${i}`, job, customer, gross, fee, net, state, tone }));
export const earningColumns: DataTableColumn<LedgerEntry>[] = [
  { key: 'job', label: 'Job / order' },
  { key: 'customer', label: 'Customer' },
  { key: 'gross', label: 'Gross', type: 'money' },
  { key: 'fee', label: 'Fee', type: 'money' },
  { key: 'net', label: 'Net', type: 'money' },
  { key: 'state', label: 'State', type: 'tag' },
];

// ── team (owner-only) ───────────────────────────────────────────────────────
export interface Member { id: string; name: string; role: string; scope: string; mfa: string }
export const team: Member[] = [
  { id: 'r0', name: 'Ravi Sandhu', role: 'Owner', scope: 'Everything incl. payouts', mfa: 'Passkey' },
  { id: 'r1', name: 'Jas Gill', role: 'Technician', scope: 'Own jobs, messages, complete & photo', mfa: 'App' },
  { id: 'r2', name: 'Priya Sandhu', role: 'Bookkeeper', scope: 'Reports, payouts (read)', mfa: 'SMS only' },
];
export const teamColumns: DataTableColumn<Member>[] = [
  { key: 'name', label: 'Member', required: true },
  { key: 'role', label: 'Role', options: ['Owner', 'Technician', 'Bookkeeper'], filter: 'facet' },
  { key: 'scope', label: 'Can', editable: false },
  { key: 'mfa', label: '2FA', type: 'tag', tones: { Passkey: 'tag-accent', App: 'tag-accent', 'SMS only': 'tag-accent-2' }, editable: false },
];

// ── the other Studio call sites ─────────────────────────────────────────────
export interface Payout { id: string; date: string; amount: number; jobs: number; status: string }
export const payouts: Payout[] = [
  { id: 'r0', date: 'Sep 4', amount: 191240, jobs: 14, status: 'Paid' },
  { id: 'r1', date: 'Aug 28', amount: 220415, jobs: 16, status: 'Paid' },
  { id: 'r2', date: 'Aug 21', amount: 165590, jobs: 12, status: 'Paid' },
  { id: 'r3', date: 'Aug 14', amount: 198000, jobs: 13, status: 'Paid' },
];
export const payoutColumns: DataTableColumn<Payout>[] = [
  { key: 'date', label: 'Date' },
  { key: 'amount', label: 'Amount', type: 'money' },
  { key: 'jobs', label: 'Jobs', type: 'num' },
  { key: 'status', label: 'Status', type: 'tag', tones: { Paid: 'tag-accent' } },
];

export interface HelpCase { id: string; title: string; meta: string; sla: string; tone: DataTableTone }
export const helpCases: HelpCase[] = [
  { id: 'HD-4471', title: 'WCB clearance letter uploaded — awaiting re-verification', meta: 'Open · agent Dev K. · replied 2 h ago', sla: 'Reply by tomorrow 10 am', tone: 'tag-accent-2' },
  { id: 'HD-4402', title: 'Payout of $1,912.40 arrived a day late', meta: 'Resolved · Sep 5 · bank holiday', sla: '', tone: 'tag-neutral' },
  { id: 'HD-4298', title: 'Customer disputed diagnostic (DS-1102)', meta: 'Resolved · Aug 20 · in your favour', sla: '', tone: 'tag-neutral' },
];
export const helpCaseColumns: DataTableColumn<HelpCase>[] = [
  { key: 'id', label: 'Case', editable: false },
  { key: 'title', label: 'Subject', required: true },
  { key: 'meta', label: 'Status', type: 'tag', editable: false },
  { key: 'sla', label: 'Next', editable: false },
];

export interface TaxRow { id: string; name: string; amt: number; note: string }
export const taxRows: TaxRow[] = [
  { id: 'ab', name: 'Alberta · GST 5%', amt: 189240, note: 'Remitted by Northline' },
  { id: 'bc', name: 'BC pilot · GST 5% + PST 7%', amt: 0, note: 'Not selling in BC' },
  { id: 'fee', name: 'Platform fee GST (on your 9%)', amt: 6140, note: 'Charged on invoice' },
];
export const taxColumns: DataTableColumn<TaxRow>[] = [
  { key: 'name', label: 'Jurisdiction' },
  { key: 'amt', label: 'Collected · Q3', type: 'money' },
  { key: 'note', label: 'Handling', filter: 'facet' },
];
