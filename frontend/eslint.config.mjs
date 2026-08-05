// @ts-check
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import secRules from './tools/eslint/sec-rules.mjs';

export default tseslint.config(
  {
    // dist/ and out-tsc/ are build output; .angular/ is the CLI cache.
    ignores: ['dist/**', 'out-tsc/**', '.angular/**', 'node_modules/**'],
  },

  // --- TypeScript ------------------------------------------------------------------------------
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...tseslint.configs.stylistic,
      ...angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    plugins: { sec: secRules },
    rules: {
      // The project prefix, so a stray component cannot ship with `app-` (angular.json "prefix").
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'sec', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'sec', style: 'kebab-case' },
      ],

      // CLAUDE.md §6: standalone components only, signal-first, OnPush is the v22 default and is
      // never declared. These are the idioms most likely to be reintroduced from memory.
      '@angular-eslint/prefer-standalone': 'error',
      '@angular-eslint/prefer-signals': 'error',
      '@angular-eslint/prefer-inject': 'error',
      '@angular-eslint/prefer-output-emitter-ref': 'error',
      '@angular-eslint/no-async-lifecycle-method': 'error',

      // CLAUDE.md §11: `strict` on, no `any`, no non-null assertions.
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-non-null-assertion': 'error',

      // R5, on inline templates and on any user-facing string.
      'sec/no-internal-namespace': 'error',
    },
  },

  // --- Templates -------------------------------------------------------------------------------
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    plugins: { sec: secRules },
    rules: {
      // CLAUDE.md §6: built-in control flow only — *ngIf / *ngFor are forbidden in new templates.
      // Both are already errors under templateRecommended; stated here so the intent is findable.
      '@angular-eslint/template/prefer-control-flow': 'error',
      '@angular-eslint/template/prefer-self-closing-tags': 'error',

      // R5: the namespace never reaches the user.
      'sec/no-internal-namespace': 'error',
    },
  },
);
