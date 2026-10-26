// 只调用 B 系统的同源接口；远端 SSO 请求由 B 后端完成。
export async function request(path: string, method: 'GET' | 'POST' = 'GET') {
  // 1. POST 先取得本地 CSRF Token，它不是 SSO AccessToken。
  const headers: Record<string, string> = {}
  if (method === 'POST') {
    const csrfResponse = await fetch('/api/csrf')
    if (!csrfResponse.ok) {
      throw new Error('无法取得 CSRF Token')
    }
    const csrfData = await csrfResponse.json()
    headers['X-CSRF-TOKEN'] = csrfData.token
  }

  // 2. 发起同源请求，浏览器自动携带 B 系统的会话 Cookie。
  const response = await fetch(path, { method, headers })

  // 3. 先读文本，再尝试解析 JSON；Spring 默认错误页可能不是 JSON。
  const text = await response.text()
  let data: any = {}
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      // 保持空对象，后续按 HTTP 状态给出通用错误提示。
    }
  }

  // 4. 失败时优先使用后端 message，没有消息时显示状态码。
  if (!response.ok) {
    let message = data.message
    if (!message) {
      message = `请求失败 (${response.status})`
    }
    throw new Error(message)
  }
  return data
}
