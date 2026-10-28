<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from './api'

type User = {
  id: string
  userCode: string
  name: string
  companyCode?: string
  authMode: string
  authorizedTokenCount: number
}
type Page = {
  code: string
  name: string
  path: string
}

const user = ref<User | null>(null)
const pages = ref<Page[]>([])
const events = ref<string[]>([])
const busy = ref(false)

function log(message: string) {
  events.value.unshift(`${new Date().toLocaleTimeString()} ${message}`)
  events.value = events.value.slice(0, 20)
}

// me 是本地会话摘要；页面链接仍需由后端实时校验。
async function refresh() {
  // 1. 读取首页显示的页面目录。
  pages.value = await request('/api/pages')
  // 2. 读取本地用户摘要；请求失败时按现有示例显示为未登录。
  try {
    user.value = await request('/api/auth/me')
  } catch {
    user.value = null
  }
}

// 退出整个服务：先由 B 后端清理本地 Token 与会话，再整页跳转到 SSO GET /logout 销毁全局会话与 SSO_SID Cookie。
async function logoutGlobal() {
  // 1. 显示处理中状态，调用 B 后端全局退出接口。
  busy.value = true
  try {
    const result = await request('/api/auth/logout/global', 'POST')
    log(result.message || '正在退出整个服务...')
    // 2. 浏览器顶层导航到 SSO 全局登出端点；SSO 清理 Cookie 后会自动 302 回跳 B 首页。
    if (result.logoutUrl) {
      window.location.href = result.logoutUrl
      return
    }
  } catch (error) {
    let message = '全局退出失败'
    if (error instanceof Error) {
      message = error.message
    }
    log(message)
  } finally {
    // 3. 若未发生整页跳转，恢复本地状态与按钮可用性。
    try {
      await refresh()
    } catch {
      log('刷新本地状态失败，请检查后端连接')
    } finally {
      busy.value = false
    }
  }
}

async function logoutSystem() {
  // 1. 显示处理中状态，再调用本地退出接口。
  busy.value = true
  try {
    const result = await request('/api/auth/logout/system', 'POST')
    log(`${result.message}，SSO 确认 ${result.confirmed}/${result.attempted}`)
  } catch (error) {
    let message = '退出失败'
    if (error instanceof Error) {
      message = error.message
    }
    log(message)
  } finally {
    // 2. 无论刷新是否成功，都要解除按钮的处理中状态。
    try {
      await refresh()
    } catch {
      log('刷新本地状态失败，请检查后端连接')
    } finally {
      busy.value = false
    }
  }
}

onMounted(refresh)
</script>

<template>
  <main>
    <p class="eyebrow">SSO V2 · FIXED CALLBACK CLIENT</p>
    <h1>业务系统 B</h1>
    <p>一个固定后端回调，配置选择普通 SSO 或页面受控模式。SSO Token 只保存在 B 后端。</p>

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
      <button :disabled="busy || !user" class="btn-global-logout" @click="logoutGlobal">退出整个服务</button>
    </section>

    <section>
      <h2>演示业务页面</h2>
      <p v-if="!user">点击页面后由 B 后端开始授权。当前运行模式由后端配置决定。</p>
      <p v-else-if="user.authMode === 'SSO_ONLY'">普通单点登录：各页面共用一组系统级 Token，每次访问仍由 B 后端向 SSO 校验身份；业务权限由 B 系统自行处理。</p>
      <p v-else>页面受控：每个页面使用独立的页面 Token，每次访问由 B 后端携带 page_code 向 SSO 校验身份和页面权限。</p>
      <div class="pages">
        <!-- 使用普通链接完成整页导航，浏览器自动跟随登录跳转。 -->
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
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: #eef2f6;
  color: #182c43;
  font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue","PingFang SC","Hiragino Sans GB","Microsoft YaHei","微软雅黑",Arial,sans-serif;
}

main {
  max-width: 940px;
  margin: 48px auto;
  padding: 0 24px;
}

.eyebrow {
  letter-spacing: 1.5px;
  color: #476b89;
  font-size: 12px;
  font-weight: 600;
}

h1 {
  font-size: 42px;
  margin: 12px 0;
}

h2 {
  font-size: 21px;
}

section {
  background: white;
  border: 1px solid #dbe3eb;
  border-radius: 12px;
  padding: 24px;
  margin: 22px 0;
}

p {
  line-height: 1.7;
  color: #53677a;
}

button {
  background: #164f78;
  color: white;
  border: 0;
  padding: 11px 16px;
  border-radius: 6px;
  cursor: pointer;
  margin: 4px;
}

button:disabled {
  opacity: .45;
  cursor: default;
}

button.btn-global-logout {
  background: #c0392b;
}

button.btn-global-logout:hover:not(:disabled) {
  background: #a93226;
}

dl {
  display: grid;
  grid-template-columns: 160px 1fr;
  gap: 12px;
}

dd {
  margin: 0;
}

.pages {
  display: grid;
  grid-template-columns: repeat(auto-fit,minmax(210px,1fr));
  gap: 14px;
}

.pages a {
  display: flex;
  flex-direction: column;
  gap: 7px;
  border: 1px solid #d9e4ec;
  border-radius: 9px;
  padding: 18px;
  text-decoration: none;
  color: #174f77;
  background: #f9fbfc;
}

.pages span,.pages small {
  color: #607588;
}

li {
  padding: 6px 0;
}

@media(max-width:600px) {
  dl {
    grid-template-columns: 1fr;
  }
  main {
    margin: 24px auto;
  }
}
</style>
