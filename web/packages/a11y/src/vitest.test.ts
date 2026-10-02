import { afterEach, describe, expect, it } from 'vitest';
import { axeViolations, describeViolations, expectNoAxeViolations } from './vitest';

afterEach(() => { document.body.innerHTML = ''; });

describe('expectNoAxeViolations', () => {
  it('passes accessible markup', async () => {
    document.body.innerHTML = '<label for="q">Search</label><input id="q"><button type="button">Go</button>';
    await expectNoAxeViolations(document.body);
  });

  it('fails on an unnamed button and an unlabelled field, most severe first', async () => {
    document.body.innerHTML = '<input id="q"><button type="button"></button>';
    const violations = await axeViolations(document.body);
    expect(violations.map(v => v.id).sort()).toEqual(['button-name', 'label']);
    expect(describeViolations(violations).split('\n')[0]).toMatch(/^critical (button-name|label):/);
    await expect(expectNoAxeViolations(document.body)).rejects.toThrow(/2 accessibility violation/);
  });

  it('can switch a rule off for one check', async () => {
    document.body.innerHTML = '<button type="button"></button>';
    await expectNoAxeViolations(document.body, { disable: ['button-name'] });
  });
});
