import type { AxiosRequestConfig } from 'axios'
import { createHttp, request as send } from '@/shared/api/http'

/**
 * 用户侧 http 实例：不做全局提示，也不在 401 时跳转。
 *
 * <p>用户侧的状态码本身就是业务分支的一部分 —— 分享口令错误是 401、内容不存在是 404、
 * 分享过期是 410，都要交给页面渲染对应视图，而不是弹一个全局 toast 或把人踢走。</p>
 */
const instance = createHttp()

export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return send<T>(instance, config)
}

export default instance
