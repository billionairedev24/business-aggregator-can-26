import { describe, expect, it } from 'vitest';
import { QUOTE_MESSAGES, attention, newLine, toBody, totals, validate, type ComposerState } from './quote';

const state = (patch: Partial<ComposerState> = {}): ComposerState => ({
  lines: [newLine({ name: 'Diagnose charging system', kind: 'labour', qty: '0.5', amount: '65' }), newLine({ name: 'Alternator', kind: 'part', qty: '1', amount: '240' }), newLine({ name: 'Replace alternator', kind: 'labour', qty: '1.5', amount: '130' })],
  scope: 'Replace alternator.', exclusions: '', proposedAt: '', durationMin: 120, validHours: 72, deposit: 'none', warranty: 'parts_labour_12m', attachments: [], ...patch,
});

describe('quote composer model (design qSeed / qTot)', () => {
  it('totals by kind with GST 5 %', () => {
    const t = totals(state().lines);
    expect(t).toMatchObject({ labour: 22750, parts: 24000, fees: 0, discount: 0, subtotal: 46750, tax: 2338, total: 49088 });
  });

  it('travel and shop supplies count as fees; discounts subtract', () => {
    const t = totals([...state().lines, newLine({ kind: 'travel', amount: '20', name: 'Travel' }), newLine({ kind: 'fee', amount: '12', name: 'Shop' }), newLine({ kind: 'discount', amount: '10', name: 'Loyal' })]);
    expect(t.fees).toBe(3200);
    expect(t.discount).toBe(1000);
    expect(t.subtotal).toBe(46750 + 3200 - 1000);
  });

  it('validation messages match validation-rules.md § Quote', () => {
    const errors = validate(state({ lines: [newLine({ name: ' ', amount: '' }), newLine({ name: 'Pads', kind: 'part', amount: '0' }), newLine({ name: 'Goodwill', kind: 'discount', amount: '0' })], scope: '  ' }));
    expect(errors['lines[0].description']).toBe(QUOTE_MESSAGES.description);
    expect(errors['lines[0].unitCents']).toBe(QUOTE_MESSAGES.amount);
    expect(errors['lines[1].unitCents']).toBe('Enter an amount.');
    expect(errors['lines[2].unitCents']).toBeUndefined();
    expect(errors.scope).toBe('Describe the scope of work.');
    expect(attention(errors)).toBe(3); // two lines + scope, like the design's qLineErrCount + scope
  });

  it('rejects discounts larger than the other lines and an empty quote', () => {
    expect(validate(state({ lines: [newLine({ name: 'Diag', amount: '1' }), newLine({ name: 'Off', kind: 'discount', amount: '5' })] })).lines).toBe(QUOTE_MESSAGES.discount);
    expect(validate(state({ lines: [] })).lines).toBe('Add at least one line.');
  });

  it('builds the request body in cents with the 25 % deposit', () => {
    const b = toBody(state({ deposit: 'pct' }));
    expect(b.lines[0]).toEqual({ kind: 'labour', description: 'Diagnose charging system', qty: 0.5, unitCents: 6500, taxable: true });
    expect(b.depositKind).toBe('pct');
    expect(b.depositBps).toBe(2500);
    expect(b.validHours).toBe(72);
  });
});
