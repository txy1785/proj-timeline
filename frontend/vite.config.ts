import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { env } from 'node:process'

export default defineConfig({
  plugins: [vue()],
  //
  // host: true 监听 0.0.0.0，局域网内其他机器可用 http://<本机IP>:5173 访问；
  // /api 与 /actuator 由 Vite 服务端代理到后端，局域网客户端无需直连后端端口。
  server: {
    host: true,
    port: 5173,
    strictPort: true,
    proxy: { '/api': env.API_PROXY_TARGET || 'http://localhost:58080', '/actuator': env.API_PROXY_TARGET || 'http://localhost:58080' }
  },
  build: { sourcemap: true }
})
