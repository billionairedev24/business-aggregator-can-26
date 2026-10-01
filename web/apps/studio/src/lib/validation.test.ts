import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { QUOTE_MESSAGES_FR } from '../features/appointments/quote';
import { AVAILABILITY_MESSAGES_FR } from '../features/availability/rules';
import { FR as CATALOGUE_FR } from '../features/catalogue/validation';
import { FR as MESSAGING_FR } from '../features/messages/validation';
import { FR as REVIEWS_FR } from '../features/reviews/validation';
import { localizeAll, localizeMessage } from './validation';

/** docs/spec/validation-messages.fr-CA.tsv: the api's French for each English message (S-40). */
const apiFrench = new Map(
  readFileSync(resolve(process.cwd(), '../../../docs/spec/validation-messages.fr-CA.tsv'), 'utf8') // cwd: apps/studio
    .split('\n')
    .filter(l => l && !l.startsWith('#'))
    .map(l => l.split('\t') as [string, string, string])
    .map(([en, fr]) => [en, fr] as const),
);

describe('client validation messages in French (S-40)', () => {
  it.each([
    ['appointments', QUOTE_MESSAGES_FR],
    ['availability', AVAILABILITY_MESSAGES_FR],
    ['catalogue', CATALOGUE_FR],
    ['messages & help', MESSAGING_FR],
    ['reviews', REVIEWS_FR],
  ])('%s reads the same as the api', (_, fr) => {
    const differing = Object.entries(fr).filter(([en, f]) => apiFrench.has(en) && apiFrench.get(en) !== f);
    expect(differing.map(([en, f]) => `${en} → ${f} (api: ${apiFrench.get(en)})`)).toEqual([]);
  });

  it('has a French wording for every message the zod schemas raise', () => {
    for (const fr of [QUOTE_MESSAGES_FR, AVAILABILITY_MESSAGES_FR]) for (const [en, f] of Object.entries(fr)) expect(f, en).not.toBe(en);
  });

  it('translates English in French, leaves English and unknown or already-French messages alone', () => {
    expect(localizeMessage('Enter an amount.', 'fr', QUOTE_MESSAGES_FR)).toBe('Entrez un montant.');
    expect(localizeMessage('Enter an amount.', 'en', QUOTE_MESSAGES_FR)).toBe('Enter an amount.');
    expect(localizeMessage('Entrez un montant.', 'fr', QUOTE_MESSAGES_FR)).toBe('Entrez un montant.');
    expect(localizeMessage(undefined, 'fr', QUOTE_MESSAGES_FR)).toBeUndefined();
    expect(localizeAll({ scope: 'Describe the scope of work.' }, m => localizeMessage(m, 'fr', QUOTE_MESSAGES_FR))).toEqual({ scope: 'Décrivez la portée des travaux.' });
  });
});
