import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { createHttp, request as send, TOKEN_KEY } from '@/shared/api/http'
import { APP_BASE, appHref } from '@/shared/appBase'

// 管理侧常用，转出去少一层 import 路径
export { TOKEN_KEY }

/**
 * 管理侧 http 实例。
 *
 * <p>错误策略：401 清理登录态并跳登录页；其余错误统一弹 ElMessage。
 * 304 是 ETag 协商命中的正常结果，不提示。</p>
 */
const instance = createHttp({
  onUnauthorized: () => {
    localStorage.removeItem(TOKEN_KEY)
    if (!window.location.pathname.startsWith(`${APP_BASE}/login`)) {
      window.location.href = appHref('/login')
    }
  },
  onError: ({ status, message }) => {
    if (status !== 401 && status !== 304 && status !== 0) {
      ElMessage.error(message)
    }
  }
})

export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return send<T>(instance, config)
}

export default instance
