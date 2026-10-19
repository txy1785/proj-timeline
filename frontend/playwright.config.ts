import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 15_000,
  use: { baseURL: 'http://127.0.0.1:41731', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 41731 --strictPort',
    url: 'http://127.0.0.1:41731',
    timeout: 30_000,
    reuseExistingServer: false
  }
})
