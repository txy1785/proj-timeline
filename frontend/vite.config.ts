import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { env } from 'node:process'

export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, proxy: { '/api': env.API_PROXY_TARGET || 'http://localhost:58080', '/actuator': env.API_PROXY_TARGET || 'http://localhost:58080' } },
  build: { sourcemap: true }
})
