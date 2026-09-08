<script setup lang="ts">
import { ref, onMounted } from 'vue'
const user = ref<Record<string, unknown> | null>(null)
const events = ref<string[]>([])
const busy = ref(false)
function log(message: string) { events.value.unshift(`${new Date().toLocaleTimeString()} ${message}`); events.value = events.value.slice(0, 30) }
async function api(path: string, post = false) {
 const headers: Record<string,string> = {}
 if (post) {
  const response = await fetch('/api/csrf')
  if (!response.ok) throw new Error('无法获取操作凭证')
  headers['X-CSRF-TOKEN'] = (await response.json()).token
 }
 const response = await fetch(path, { method: post ? 'POST' : 'GET', headers })
 const text = await response.text()
 const data = text ? JSON.parse(text) : {}
 if (!response.ok) { if (response.status === 401) user.value = null; throw new Error(data.message || `请求失败 (${response.status})`) }
 return data
}
async function refresh() { try { user.value = await api('/api/auth/me') } catch { user.value = null } }
async function action(path: string, post = false) {
 busy.value = true
 try {
  const result = await api(path, post)
  log(result.message || '操作完成')
  if (result.redirect) window.location.assign(result.redirect)
 } catch (error) { log(error instanceof Error ? error.message : '请求失败') }
 finally { await refresh(); busy.value = false }
}
onMounted(refresh)
</script>
<template>
 <main>
  <p class="eyebrow">SSO · JAVA 8 REFERENCE CLIENT</p>
  <h1>业务系统 B</h1>
  <p>统一认证，本地会话。用于接入验证与演示。</p>
  <section>
   <h2>{{ user ? '已登录' : '尚未登录' }}</h2>
   <dl v-if="user"><template v-for="(value,key) in user" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
   <a v-else class="button" href="/api/auth/login">统一登录</a>
   <button @click="refresh">刷新登录状态</button>
  </section>
  <section>
   <h2>验证操作</h2>
   <div class="actions">
    <button :disabled="busy || !user" @click="action('/api/demo/business')">调用普通业务</button>
    <button :disabled="busy || !user" @click="action('/api/demo/sensitive',true)">校验 SSO 并执行敏感操作</button>
    <button :disabled="busy || !user" @click="action('/api/auth/logout/local',true)">仅退出 B</button>
    <button :disabled="busy || !user" @click="action('/api/auth/logout/global',true)">全局退出</button>
   </div>
   <p>仅退出 B 后再次统一登录，SSO 会话仍有效时无需输入密码。全局退出失败会保留提示，请核对其它系统状态。</p>
  </section>
  <section><h2>本次页面操作记录</h2><p v-if="!events.length">点击按钮开始验证。</p><ol><li v-for="(event,i) in events" :key="i">{{ event }}</li></ol><button @click="events=[]">清除记录</button></section>
 </main>
</template>
<style>
*{box-sizing:border-box}body{margin:0;background:#eef2f6;color:#182c43;font-family:system-ui,sans-serif}main{max-width:940px;margin:48px auto;padding:0 24px}.eyebrow{letter-spacing:2px;color:#476b89;font-size:12px}h1{font-size:42px;margin:12px 0}h2{font-size:21px}section{background:white;border:1px solid #dbe3eb;border-radius:12px;padding:24px;margin:22px 0}p{line-height:1.7;color:#53677a}.actions{display:flex;gap:10px;flex-wrap:wrap}button,.button{display:inline-block;background:#164f78;color:white;border:0;padding:11px 16px;border-radius:6px;cursor:pointer;text-decoration:none;margin:4px}button:disabled{opacity:.45;cursor:default}dl{display:grid;grid-template-columns:160px 1fr;gap:12px}dd{margin:0;overflow-wrap:anywhere}li{padding:6px 0}@media(max-width:600px){dl{grid-template-columns:1fr}main{margin:24px auto}}
</style>
