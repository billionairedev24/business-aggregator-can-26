// ESLint for the native apps and the shared kit (S-87). `pnpm lint` runs with --max-warnings=0: a warning fails.
import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['**/node_modules/**', '**/dist-*/**', '**/.expo/**', '**/smoke-out/**', '**/ios/**', '**/android/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    languageOptions: { globals: { ...globals.browser, ...globals.node, ...globals.jest } },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      'no-restricted-syntax': [
        'error',
        {
          selector: 'Literal[value=/^#[0-9a-fA-F]{3,8}$/]',
          message: 'No colour literals: use colors from @northline/mobile-kit (tokens.json is the only place colours are defined).',
        },
      ],
    },
  },
  {
    // the theme derives colours from the tokens; tests may name them
    files: ['packages/mobile-kit/src/theme/**', '**/__tests__/**', '**/app.config.ts'],
    rules: { 'no-restricted-syntax': 'off' },
  },
  {
    files: ['**/jest.setup.ts'],
    rules: { '@typescript-eslint/consistent-type-imports': 'off' },
  },
  {
    files: ['**/*.{js,cjs,mjs}'],
    languageOptions: { globals: { ...globals.node, ...globals.browser } },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
);
