import { describe, expect, it } from 'vitest';
import { swaggerTargets } from './runtimeConfig';

describe('Swagger UI links from /config.js', () => {
  it('reads label|url entries and skips anything else', () => {
    expect(
      swaggerTargets({
        swagger: ['dev api|https://api.dev.northline.ca/docs', 'broken', 'js|javascript:alert(1)', ''],
      }),
    ).toEqual([{ label: 'dev api', url: 'https://api.dev.northline.ca/docs' }]);
  });

  it('shows nothing without configuration (production)', () => {
    expect(swaggerTargets(undefined)).toEqual([]);
    expect(swaggerTargets({ swagger: [] })).toEqual([]);
  });
});
