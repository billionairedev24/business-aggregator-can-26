import { defineConfig } from 'vitest/config';

// Unit tests for pure logic. Storybook interaction/a11y tests run through @storybook/addon-vitest separately.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
});
