import type { DocNode, KbVO, NavMenuItem, OutlineNode, StatsVO, UserVO } from '@/shared/api/types'

export type { DocNode, KbVO, NavMenuItem, OutlineNode, StatsVO, UserVO }

/**
 * 门户统计卡片（后端 {@code PortalStatsVO}）。
 *
 * <p>分享即发布：门户只统计已发布的库，再按分享是否加密拆成公开 / 私有 —— 与后台的
 * 可见性三档（{@link StatsVO}）不是一回事，因此单独一个形状。</p>
 */
export interface PortalStatsVO {
  kbTotal: number
  kbPublic: number
  kbPrivate: number
  docTotal: number
  /** 访问量：发布分享累计 views（分享页 + 门户阅读合并） */
  viewTotal: number
  /** 本月访问（按 share_view_log 本月行数） */
  viewDelta: number
  kbTotalDelta: number
  docTotalDelta: number
}

/**
 * 阅读页视图模型，与后端 {@code cn.minims.minidocs.reader.model.ReadView} 对齐。
 *
 * <p>门户与分享共用同一结构：{@code mode} 区分来源，{@code singleDoc} 为分享单篇场景，
 * {@code assetPrefix} / {@code docLinkPrefix} 已由后端按场景算好，前端直接拼接即可。</p>
 */
export interface ReadView {
  mode: 'portal' | 'share'
  kbId?: number
  kbName: string
  kbSlug?: string
  /** 该库所属组织；分享模式没有组织上下文，所以是可选 */
  orgSlug?: string
  kbDescription?: string
  /** 封面取图地址，前缀已按门户 / 分享各自算好；库没设封面时缺键 */
  kbCoverSrc?: string
  /** 知识库最近更新时间文案，分享页左栏「共 N 篇 · 更新 …」用 */
  kbUpdatedText?: string
  kbTags: string[]
  /**
   * 平台内读权限（public/org/private）。仅供管理端判断，**不要用于读者可见的文案** ——
   * 读者关心的是「要不要输密码」，那看 {@link shareStatus}。
   */
  visibility?: string
  publicKb: boolean
  /** 发布态（unpublished/public/private）：左栏胶囊据此显示「共享 / 加密」 */
  shareStatus?: 'unpublished' | 'public' | 'private'
  /** 资源前缀（/minidocs/kb/{org}/{slug}/asset/）：预览库内图片时拼地址用 */
  assetPrefix?: string
  docCount: number
  tagCount: number
  /**
   * 目录树是否显示 {@code .md} 后缀（库级配置）。缺省按显示处理。
   *
   * <p>前端在渲染时剥，不在后端剥 {@code DocNode.name} —— 那个字段同时是文件名，
   * 管理端的重命名对话框要拿它回填。</p>
   */
  showMdSuffix?: boolean

  tree: DocNode[]
  showTree: boolean
  /** 分享页顶部导航条；分享者没配菜单时为空数组 */
  menu: NavMenuItem[]

  currentPath?: string
  currentName?: string
  empty: boolean

  html: string
  outline: OutlineNode[]
  etag?: string

  title?: string
  summary?: string
  tags: string[]
  author?: string
  publishedText?: string
  updatedText?: string
  wordCount: number
  readingMinutes: number
  /** 源码行数，底部状态栏展示 */
  lineCount: number

  views?: number
  shareToken?: string
  expiresText?: string
  singleDoc: boolean

  prevPath?: string
  prevName?: string
  prevLink?: string
  nextPath?: string
  nextName?: string
  nextLink?: string

  assetPrefix?: string
  docLinkPrefix?: string

  /**
   * 站点基址（协议 + 主机 + 端口），仅分享模式由后端下发。
   *
   * <p>顶栏「分享」按钮要用它拼绝对地址：浏览器里的 {@code window.location.origin} 在反向代理
   * 后面可能是内网地址，与后台管理台给出的分享链接对不上。</p>
   */
  siteBase?: string

  loggedIn: boolean
  canManage: boolean
}

/**
 * GET /api/portal/home
 *
 * <p>后端开启了 {@code jackson.default-property-inclusion: non_null}，
 * 因此 null 字段是「缺键」而不是显式 null，类型上统一按可选处理。</p>
 */
export interface PortalHomeVO {
  stats: PortalStatsVO
  user?: UserVO | null
}

/**
 * GET /api/portal/kb/{org}/{slug}
 *
 * <p>{@code state=password} 时只有库名，正文不下发；前端据此渲染口令弹层，校验走
 * {@code POST /api/portal/kb/{org}/{slug}/verify}。</p>
 */
export interface PortalReadVO {
  state: 'ok' | 'password' | 'login'
  kbName?: string
  view?: ReadView | null
}

/** GET /api/share/{token} */
export interface ShareReadVO {
  /** ok = 可直接阅读；password = 需要先校验口令 */
  state: 'ok' | 'password' | 'login'
  token: string
  kbName?: string
  view?: ReadView | null
}
