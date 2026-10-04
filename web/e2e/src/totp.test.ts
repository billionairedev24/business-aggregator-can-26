import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { base32Decode, freshTotp, STEP_MS, totpAt } from './totp';

describe('TOTP (RFC 6238, as northline-auth checks it)', () => {
  // RFC 6238 appendix B, SHA-1 key "12345678901234567890" (base32 below), truncated to 6 digits
  const rfcKey = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';

  it('matches the RFC test vectors', () => {
    expect(base32Decode(rfcKey).toString()).toBe('12345678901234567890');
    expect(totpAt(rfcKey, Math.floor(59 / 30))).toBe('287082');
    expect(totpAt(rfcKey, Math.floor(1111111109 / 30))).toBe('081804');
    expect(totpAt(rfcKey, Math.floor(1234567890 / 30))).toBe('005924');
    expect(totpAt(rfcKey, Math.floor(2000000000 / 30))).toBe('279037');
  });

  it('reads keys with spaces and lower case, refuses other characters', () => {
    expect(base32Decode('gezd gnbv gy3t qojq')).toEqual(base32Decode('GEZDGNBVGY3TQOJQ'));
    expect(() => base32Decode('GEZ1')).toThrow(/base32/);
  });

  it('never hands out the same step twice — the second code waits for the next step', async () => {
    const state = join(mkdtempSync(join(tmpdir(), 'totp-')), 'steps.json');
    let now = 40 * STEP_MS + 1_000;
    const clock = () => now;
    const first = await freshTotp(rfcKey, state, clock);
    expect(first).toBe(totpAt(rfcKey, 40));
    now = 41 * STEP_MS + 300; // already in the next step: no wait
    expect(await freshTotp(rfcKey, state, clock)).toBe(totpAt(rfcKey, 41));
  });
});
