import { request } from './http'
import type { KbVO, PortalHomeVO, PortalReadVO, ReadView, ShareReadVO } from './types'
import type { PageResult, SearchHit, SiteConfig, SuggestItem, UserVO } from '@/shared/api/types'

/** 门户（游客可读） */
export const portalApi = {
  home: () => request<PortalHomeVO>({ url: '/portal/home' }),
  /** 门户卡片列表：一次一页，滚到底续拉下一页（只出已发布的库，access 筛公开/私有） */
  kbs: (params: { page: number; size: number; sort: string; access?: string; keyword?: string }) =>
    request<PageResult<KbVO>>({ url: '/portal/kbs', params }),
  kb: (orgSlug: string, slug: string, path?: string) =>
    request<PortalReadVO>({
      url: `/portal/kb/${encodeURIComponent(orgSlug)}/${encodeURIComponent(slug)}`,
      params: path ? { path } : undefined
    }),
  /** 私有库口令校验；通过后后端下发免密 Cookie，随后重取 kb() 即可读到正文 */
  verifyKb: (orgSlug: string, slug: string, password: string) =>
    request<void>({
      url: `/portal/kb/${encodeURIComponent(orgSlug)}/${encodeURIComponent(slug)}/verify`,
      method: 'post',
      data: { password }
    }),
  /** 老链接消歧：命中该 slug 且当前访问者可读的知识库列表。 */
  locate: (slug: string) => request<KbVO[]>({ url: '/portal/locate', params: { slug } })
}

/** 外部分享 */
export const shareApi = {
  read: (token: string, path?: string) =>
    request<ShareReadVO>({ url: `/share/${encodeURIComponent(token)}`, params: path ? { path } : undefined }),
  /** 口令错误时后端返回 401，由页面自行处理 */
  verify: (token: string, password: string) =>
    request<void>({
      url: `/share/${encodeURIComponent(token)}/verify`,
      method: 'post',
      data: { password }
    })
}

/** 登录态（Cookie 桥接，浏览器直连即可） */
export const authApi = {
  me: () => request<UserVO>({ url: '/auth/me' }),
  logout: () => request<void>({ url: '/auth/logout', method: 'post' })
}

export const searchApi = {
  suggest: (q: string) =>
    request<{ kbs: SuggestItem[]; docs: SuggestItem[]; tags: SuggestItem[] }>({
      url: '/search/suggest',
      params: { q }
    }),
  /**
   * L2 全文检索。允许匿名，返回的是当前访问者可见的库里的命中（可见集在后端裁，§2.5）。
   *
   * <p>结果自带 {@code url}，所以这里不传组织 slug：面板在门户和管理台之间是同一份，切组织时
   * 可见集跟着 Cookie 里的身份变，前端不参与判定。</p>
   */
  search: (params: { q: string; kbId?: string; page?: number; size?: number }) =>
    request<PageResult<SearchHit>>({ url: '/search', params })
}

/** 供共享设置 store 注册的读写通道 */
export const settingsApi = {
  get: () => request<Record<string, unknown>>({ url: '/settings' }),
  update: (patch: Record<string, unknown>) =>
    request<Record<string, unknown>>({ url: '/settings', method: 'put', data: patch })
}

/** 供共享站点 store 注册的读取通道（公开接口，游客也能读到品牌） */
export const siteApi = {
  get: () => request<SiteConfig>({ url: '/portal/site' })
}
