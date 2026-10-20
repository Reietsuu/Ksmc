/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// En développement, l'API est celle d'un controller local (KSMC_API, défaut http://localhost:8080).
const api = process.env.KSMC_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 4000,
    rollupOptions: {
      output: {
        manualChunks: { monaco: ['monaco-editor'], xterm: ['@xterm/xterm', '@xterm/addon-fit'] },
      },
    },
  },
  server: {
    proxy: {
      '/api': { target: api, ws: true, changeOrigin: true },
      '/modules': { target: api, changeOrigin: true },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    css: false,
  },
});
