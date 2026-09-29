import { defineConfig } from 'vite';
import { tanstackStart } from '@tanstack/react-start/plugin/vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [tanstackStart(), react()],
  server: { port: 3000, proxy: { '/api': 'http://localhost:8080', '/bff': 'http://localhost:8081' } },
});
