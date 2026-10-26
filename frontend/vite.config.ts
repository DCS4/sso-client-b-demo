import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// 与后端默认端口一致；更改后端端口时同步修改这里。
const backendUrl = 'http://localhost:18085'
export default defineConfig({
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': backendUrl,
      // 真实页面 URL 必须到达 B 后端守卫，不能由 Vite 静态回退绕过。
      '/pages': backendUrl,
    },
  },
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
