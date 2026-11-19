import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const serverPort = Number(process.env.DEMO_SHOP_PORT ?? 4173);

export default defineConfig({
  root: 'src/client',
  plugins: [react()],
  build: { outDir: '../../dist/client', emptyOutDir: true },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: {
      '/api': `http://127.0.0.1:${serverPort}`,
      '/__fixture': `http://127.0.0.1:${serverPort}`,
    },
  },
});
