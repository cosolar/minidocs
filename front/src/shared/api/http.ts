import axios, { type AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { API_BASE } from '@/shared/appBase'

/** 登录态在 localStorage 中的键（与后端 Cookie 桥接并存） */
export const TOKEN_KEY = 'minidocs.token'

/** /api/** 统一响应体 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

/** 归一化后的错误信息 */
export interface ApiErrorInfo {
  /** HTTP 状态码；网络异常时为 0 */
  status: number
  /** 业务错误码（ApiResponse.code） */
  code: number
  message: string
  /**
   * 错误随附的恢复上下文（409 的锁持有者、412 的磁盘基线）。
   *
   * <p>后端把它放在 {@code ApiResponse.data} 里，与正常响应同一位置 —— 组件按 code 分支，
   * 不必猜字段在哪。</p>
   */
  data?: unknown
}

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 由调用方自己处理错误提示（编辑锁的 409/412 要变横幅和弹窗，叠一条 toast 只会碍事） */
    silentError?: boolean
  }
}

export interface HttpHooks {
  /** 任意失败请求（含网络异常）都会回调，用于统一提示 */
  onError?: (info: ApiErrorInfo) => void
  /** 收到 401 时回调；管理侧借此清理登录态并跳转登录页 */
  onUnauthorized?: (info: ApiErrorInfo) => void
}

export function toApiErrorInfo(error: unknown): ApiErrorInfo {
  const axiosError = error as AxiosError<ApiEnvelope<unknown>>
  const status = axiosError?.response?.status ?? 0
  const body = axiosError?.response?.data
  return {
    status,
    code: typeof body?.code === 'number' ? body.code : status,
    message: body?.message || axiosError?.message || '请求失败',
    data: body?.data
  }
}

/**
 * 创建 http 实例。
 *
 * <p>只做两件通用的事：注入 Authorization 头、把 {@code {code,message,data}} 解包成 data。
 * 错误处理策略由调用方通过 hooks 注入 —— 管理侧要弹提示并跳登录页，用户侧则需安静地把
 * 401 / 404 / 410 交给页面渲染，两者行为不同，不应挤在同一个实例里。</p>
 */
export function createHttp(hooks: HttpHooks = {}): AxiosInstance {
  const instance = axios.create({ baseURL: API_BASE, timeout: 60000 })

  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })

  instance.interceptors.response.use(
    (response) => response.data?.data,
    (error) => {
      const info = toApiErrorInfo(error)
      if (info.status === 401) {
        hooks.onUnauthorized?.(info)
      }
      if ((error as AxiosError)?.config?.silentError !== true) {
        hooks.onError?.(info)
      }
      return Promise.reject(error)
    }
  )

  return instance
}

/** 以「已解包 data」的语义发起请求 */
export function request<T>(instance: AxiosInstance, config: AxiosRequestConfig): Promise<T> {
  return instance.request<any, T>(config)
}

/** 逐段编码路径，保留 / 分隔符 */
export function encodePath(path: string) {
  return (path || '')
    .split('/')
    .map((segment) => encodeURIComponent(segment).replace(/%20/g, '%20'))
    .join('/')
}
