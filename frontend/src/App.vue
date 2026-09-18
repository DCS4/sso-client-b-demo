<script setup lang="ts">
import { onMounted, ref } from 'vue'

type User = {
  id: string
  userCode: string
  name: string
  companyCode?: string
  authMode: string
  authorizedTokenCount: number
}
type Page = { code: string; name: string; path: string }

const user = ref<User | null>(null)
const pages = ref<Page[]>([])
const events = ref<string[]>([])
const busy = ref(false)

function log(message: string) {
  events.value.unshift(`${new Date().toLocaleTimeString()} ${message}`)
  events.value = events.value.slice(0, 20)
}

async function request(path: string, method: 'GET' | 'POST' = 'GET') {
  const headers: Record<string, string> = {}
  if (method === 'POST') {
    const csrf = await fetch('/api/csrf')
    if (!csrf.ok) throw new Error('无法取得 CSRF Token')
    headers['X-CSRF-TOKEN'] = (await csrf.json()).token
  }
  const response = await fetch(path, { method, headers })
  const text = await response.text()
  const data = text ? JSON.parse(text) : {}
  if (!response.ok) throw new Error(data.message || `请求失败 (${response.status})`)
  return data
}

async function refresh() {
  pages.value = await request('/api/pages')
  try {
    user.value = await request('/api/auth/me')
  } catch {
    user.value = null
  }
}

async function logoutSystem() {
  busy.value = true
  try {
    const result = await request('/api/auth/logout/system', 'POST')
    log(`${result.message}，SSO 确认 ${result.confirmed}/${result.attempted}`)
  } catch (error) {
    log(error instanceof Error ? error.message : '退出失败')
  } finally {
    await refresh()
    busy.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <main>
    <p class="eyebrow">SSO V2 · FIXED CALLBACK CLIENT</p>
    <h1>业务系统 B</h1>
    <p>一个固定后端回调，多页面 page_code，所有 Token 只保存在 B 后端。</p>

    <section>
      <h2>{{ user ? '本地会话已建立' : '尚未取得页面授权' }}</h2>
      <dl v-if="user">
        <dt>用户</dt><dd>{{ user.name }}（{{ user.userCode }}）</dd>
        <dt>模式</dt><dd>{{ user.authMode }}</dd>
        <dt>本地 Token 数</dt><dd>{{ user.authorizedTokenCount }}</dd>
      </dl>
      <p v-else>直接点击下面任一页面。B 后端会自动引导到 SSO，并从同一个固定回调返回。</p>
      <button @click="refresh">刷新本地状态</button>
      <button :disabled="busy || !user" @click="logoutSystem">退出 B 系统</button>
    </section>

    <section>
      <h2>受控页面</h2>
      <p>这些是普通链接，不携带 Token。直接输入页面地址也会经过 B 后端校验。</p>
      <div class="pages">
        <a v-for="page in pages" :key="page.code" :href="page.path">
          <strong>{{ page.name }}</strong>
          <span>{{ page.code }}</span>
          <small>{{ page.path }}</small>
        </a>
      </div>
    </section>

    <section>
      <h2>本次页面记录</h2>
      <p v-if="!events.length">暂无本地操作记录。</p>
      <ol><li v-for="(event, index) in events" :key="index">{{ event }}</li></ol>
    </section>
  </main>
</template>

<style>
*{box-sizing:border-box}body{margin:0;background:#eef2f6;color:#182c43;font-family:system-ui,sans-serif}
main{max-width:940px;margin:48px auto;padding:0 24px}.eyebrow{letter-spacing:2px;color:#476b89;font-size:12px}
h1{font-size:42px;margin:12px 0}h2{font-size:21px}section{background:white;border:1px solid #dbe3eb;border-radius:12px;padding:24px;margin:22px 0}
p{line-height:1.7;color:#53677a}button{background:#164f78;color:white;border:0;padding:11px 16px;border-radius:6px;cursor:pointer;margin:4px}
button:disabled{opacity:.45;cursor:default}dl{display:grid;grid-template-columns:160px 1fr;gap:12px}dd{margin:0}.pages{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:14px}
.pages a{display:flex;flex-direction:column;gap:7px;border:1px solid #d9e4ec;border-radius:9px;padding:18px;text-decoration:none;color:#174f77;background:#f9fbfc}.pages span,.pages small{color:#607588}
li{padding:6px 0}@media(max-width:600px){dl{grid-template-columns:1fr}main{margin:24px auto}}
</style>
