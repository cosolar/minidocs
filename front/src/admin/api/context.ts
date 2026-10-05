import { API_BASE } from '@/shared/appBase'

/**
 * 组织上下文：只有一个来源 —— 地址栏里的 {@code /console/{org}/...}。
 *
 * <p>刻意不读 localStorage（规范 §3.4）：存起来的组织会在「刷新」「新开标签页」「把链接发给同事」
 * 三种场景下各自错一次。这里只允许路由守卫写入，写在 {@code beforeEach} 里，因此任何组件发起
 * 请求时它都已经与地址栏一致。</p>
 *
 * <p>只有 {@code /login} 与落地页读到 null 是正常的；组织内页面调用 {@link requireOrg} 却拿到 null，
 * 说明守卫被绕过了 —— 抛错比拼出一个 {@code /console//kbs/x} 的假请求好排查。</p>
 */
let org: string | null = null

export function setOrg(value: string | null) {
  org = value
}

export function requireOrg(): string {
  if (!org) {
    throw new Error(`缺少组织上下文：当前地址 ${window.location.pathname} 不在 /console/{org}/ 下`)
  }
  return org
}

/** 组织内某个知识库的 API 前缀（相对 axios 的 baseURL）。 */
export function kbPath(kbSlug: string): string {
  return `/console/${encodeURIComponent(requireOrg())}/kbs/${encodeURIComponent(kbSlug)}`
}

/**
 * 脱离 axios 实例的完整地址：图片 {@code <img src>}、{@code window.open} 下载、
 * {@code fetch keepalive} 归还锁这三类请求都到不了拦截器，只能自己把接口前缀与组织段拼全。
 */
export function kbUrl(kbSlug: string, suffix = ''): string {
  return `${API_BASE}${kbPath(kbSlug)}${suffix}`
}

/** 组织级 API 前缀（相对 axios 的 baseURL）。 */
export function orgPath(): string {
  return `/console/${encodeURIComponent(requireOrg())}`
}
