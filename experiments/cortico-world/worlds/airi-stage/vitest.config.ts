import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// `cortico/<path>` resolves to the pinned checkout's src (scripts/cortico-experiment setup).
const corticoSrc = process.env.CORTICO_SRC
  ?? fileURLToPath(new URL('../../../../.cortico-experiment/cortico/src', import.meta.url));

export default defineConfig({
  resolve: { alias: [{ find: /^cortico\/(.*)$/, replacement: `${corticoSrc}/$1` }] },
  test: {
    include: ['tests/**/*.test.ts'],
    env: { CORTICO_LANGUAGE: 'en' },
    testTimeout: 20000,
    pool: 'forks',
  },
});
