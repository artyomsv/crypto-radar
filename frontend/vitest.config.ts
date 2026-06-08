/// <reference types="vitest" />
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// Minimal Vitest config. Frontend tests start with pure-function utilities
// in `src/lib/` — component-rendering tests will require
// @testing-library/react + happy-dom which we'll add when the first
// component test lands.
export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, './src'),
        },
    },
    test: {
        environment: 'node',
        include: ['src/**/*.test.{ts,tsx}'],
    },
});
