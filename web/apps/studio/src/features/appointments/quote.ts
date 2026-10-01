import { z } from 'zod';
import type { FrenchMessages } from '../../lib/validation';
import type { DepositKind, LineKind, Media, Quote, QuoteBody, QuoteRequest, Warranty } from './api';

/** validation-rules.md § Quote — exact messages, shared with the server (QuoteContent.java). */
export const QUOTE_MESSAGES = {
  description: "Describe this line — customers must see what they're paying for.",
  amount: 'Enter an amount.',
  scope: 'Describe the scope of work.',
  lines: 'Add at least one line.',
  tooLong: 'At most 160 characters.',
  qty: 'Quantity must be more than 0.',
  discount: "Discounts can't be more than the other lines.",
} as const;

/** Extra-work approval (JobPanel): BookingApprovals.java's messages. */
export const APPROVAL_MESSAGES = { description: 'Describe the extra parts or work.', amount: 'Enter an amount.', tooLong: 'At most 160 characters.' } as const;

/** fr-CA of the quote and approval messages — the api's French (docs/spec/validation-messages.fr-CA.tsv). */
export const QUOTE_MESSAGES_FR: FrenchMessages = {
  [QUOTE_MESSAGES.description]: 'Décrivez cette ligne — les clients doivent voir ce qu’ils paient.',
  [QUOTE_MESSAGES.amount]: 'Entrez un montant.',
  [QUOTE_MESSAGES.scope]: 'Décrivez la portée des travaux.',
  [QUOTE_MESSAGES.lines]: 'Ajoutez au moins une ligne.',
  [QUOTE_MESSAGES.tooLong]: 'Au plus 160 caractères.',
  [QUOTE_MESSAGES.qty]: 'La quantité doit être supérieure à 0.',
  [QUOTE_MESSAGES.discount]: 'Les rabais ne peuvent pas dépasser les autres lignes.',
  [APPROVAL_MESSAGES.description]: 'Décrivez les pièces ou les travaux supplémentaires.',
};

export const GST_BPS = 500;
export const VALID_HOURS = [24, 72, 168, 336] as const;
export const DURATIONS = [60, 90, 120, 240, 480] as const;

export interface ComposerLine { key: string; name: string; kind: LineKind; qty: string; amount: string }
export interface ComposerState {
  lines: ComposerLine[]; scope: string; exclusions: string; proposedAt: string; durationMin: number; validHours: number;
  deposit: 'none' | 'parts_upfront' | 'pct'; warranty: Warranty; attachments: Media[];
}

let seq = 0;
export const newLine = (tpl: Partial<ComposerLine> = {}): ComposerLine => ({ key: `l${++seq}`, name: '', kind: 'labour', qty: '1', amount: '', ...tpl });

/** Lenient number parse like the design (strips "$", spaces, commas). */
export const num = (v: string) => { const n = parseFloat(String(v).replace(/[^0-9.]/g, '')); return Number.isNaN(n) ? 0 : n; };
const cents = (dollars: string) => Math.round(num(dollars) * 100);
const lineCents = (l: ComposerLine) => Math.round(num(l.qty) * cents(l.amount));
const bps = (c: number, b: number) => Math.round((c * b) / 10000);

export interface Totals { labour: number; parts: number; fees: number; discount: number; subtotal: number; tax: number; total: number }
export function totals(lines: readonly ComposerLine[], taxBps = GST_BPS): Totals {
  const sum = (ks: LineKind[]) => lines.filter(l => ks.includes(l.kind)).reduce((a, l) => a + lineCents(l), 0);
  const labour = sum(['labour']), parts = sum(['part']), fees = sum(['fee', 'travel']), discount = sum(['discount']);
  const subtotal = labour + parts + fees - discount;
  const tax = Math.max(0, bps(subtotal, taxBps));
  return { labour, parts, fees, discount, subtotal, tax, total: subtotal + tax };
}

/** zod mirror of the server's rules; issue paths are the request's JSON paths (lines[0].unitCents …). */
export const quoteBodySchema = z.object({
  lines: z.array(z.object({
    kind: z.enum(['labour', 'part', 'fee', 'travel', 'discount']),
    description: z.string().trim().min(1, QUOTE_MESSAGES.description).max(160, QUOTE_MESSAGES.tooLong),
    qty: z.number().gt(0, QUOTE_MESSAGES.qty),
    unitCents: z.number().min(0, QUOTE_MESSAGES.amount),
    taxable: z.boolean(),
  }).superRefine((l, ctx) => { if (l.kind !== 'discount' && l.unitCents <= 0) ctx.addIssue({ code: 'custom', path: ['unitCents'], message: QUOTE_MESSAGES.amount }); })).min(1, QUOTE_MESSAGES.lines),
  scope: z.string().trim().min(1, QUOTE_MESSAGES.scope),
  validHours: z.number().refine(v => (VALID_HOURS as readonly number[]).includes(v)),
}).passthrough().superRefine((b, ctx) => {
  const ok = b.lines.every(l => l.description.trim() && (l.kind === 'discount' || l.unitCents > 0) && l.qty > 0);
  const sum = b.lines.reduce((a, l) => a + Math.round(l.qty * l.unitCents) * (l.kind === 'discount' ? -1 : 1), 0);
  if (ok && b.lines.length > 0 && sum < 0) ctx.addIssue({ code: 'custom', path: ['lines'], message: QUOTE_MESSAGES.discount });
});

export function toBody(s: ComposerState): QuoteBody {
  return {
    lines: s.lines.map(l => ({ kind: l.kind, description: l.name.trim(), qty: num(l.qty), unitCents: cents(l.amount), taxable: true })),
    scope: s.scope, exclusions: s.exclusions.trim() || undefined, proposedAt: s.proposedAt ? new Date(s.proposedAt).toISOString() : undefined,
    durationMin: s.durationMin, validHours: s.validHours, warranty: s.warranty,
    depositKind: s.deposit as DepositKind, depositBps: s.deposit === 'pct' ? 2500 : undefined, attachments: s.attachments.map(a => a.id),
  };
}

/** Field path → first message ("lines[0].description", "scope", "lines"). */
export function validate(s: ComposerState): Record<string, string> {
  const r = quoteBodySchema.safeParse(toBody(s));
  const out: Record<string, string> = {};
  if (!r.success) for (const i of r.error.issues) { const k = i.path.map((p, n) => (typeof p === 'number' ? `[${p}]` : n ? `.${String(p)}` : String(p))).join(''); out[k] ??= i.message; }
  return out;
}

/** Number of things to fix: one per line with an error, plus scope and list-level errors (design qLineErrCount). */
export function attention(errors: Record<string, string>): number {
  const lines = new Set(Object.keys(errors).filter(k => /^lines\[\d+\]/.test(k)).map(k => k.replace(/\].*$/, ']')));
  return lines.size + Object.keys(errors).filter(k => !k.startsWith('lines[')).length;
}

/** "2026-10-03T16:00:00Z" → value for <input type="datetime-local"> in the viewer's clock. */
function toLocalInput(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const dollars = (c: number) => (c % 100 === 0 ? String(c / 100) : (c / 100).toFixed(2));

/** Composer start: the quote being revised, else the draft, else one empty line (design qSeed / fallback). */
export function initialState(req: QuoteRequest, source?: Quote | null): ComposerState {
  const q = source ?? req.quote ?? null;
  if (!q) return { lines: [newLine()], scope: '', exclusions: '', proposedAt: toLocalInput(req.preferredAt), durationMin: 120, validHours: 72, deposit: 'none', warranty: 'parts_labour_12m', attachments: [] };
  return {
    lines: q.lines.length ? q.lines.map(l => newLine({ name: l.description, kind: l.kind, qty: String(l.qty), amount: dollars(l.unitCents) })) : [newLine()],
    scope: q.scope, exclusions: q.exclusions ?? '', proposedAt: toLocalInput(q.proposedAt ?? req.preferredAt), durationMin: q.durationMin ?? 120, validHours: q.validHours,
    deposit: q.depositKind, warranty: q.warranty, attachments: q.attachments,
  };
}
