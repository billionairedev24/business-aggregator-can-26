import { describe, expect, it } from 'vitest';
import { safeNext } from './routeSupport';

describe('safeNext (S-20: no open redirect through ?next=)', () => {
  it('keeps local paths', () => {
    expect(safeNext('/b/01J9ZD3V00000000000000PWM1/orders?tab=open')).toBe('/b/01J9ZD3V00000000000000PWM1/orders?tab=open');
    expect(safeNext('/onboarding')).toBe('/onboarding');
  });

  it('refuses other hosts and schemes', () => {
    for (const next of ['https://evil.example', '//evil.example', '/\\evil.example', 'evil.example', 'javascript:alert(1)', '', undefined]) {
      expect(safeNext(next)).toBeUndefined();
    }
  });

  it('refuses control characters, which the URL parser drops (/\\t/evil → //evil)', () => {
    for (const next of ['/\t/evil.example', '/\n/evil.example', '/\r/evil.example', '/a\u0000b', '/a\u007fb']) {
      expect(safeNext(next)).toBeUndefined();
    }
    // why: the browser's URL parser turns the tab version into a protocol-relative URL
    expect(new URL('/\t/evil.example', 'https://studio.northline.ca').host).toBe('evil.example');
  });
});
