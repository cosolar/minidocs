import { request } from './http'
import { kbPath, kbUrl, orgPath } from './context'
import type {
  AdminUserVO, AuditVO, DiscoverOrgVO, DocContentVO, DocNode, GitStatusVO, GitSyncVO, ImportResult,
  KbConfigVO, KbDisplay, KbRosterVO, KbVO, LockVO,
  LoginResponse, MeVO, OrgMemberVO, OrgVO, PageResult, PathChangeVO, JoinRequestVO, RenderVO,
  ShareVO, SiteConfig, StatsVO, UserVO
} from '@/shared/api/types'

/* ------------------------------------------------------------------ 认证 */
export const authApi = {
  /**
   * 登录。
   *
   * <p>{@code silentError} 是必需的：全局 onError 故意不弹 401（那套语义是「登录态失效，
   * 交给 onUnauthorized 跳转」），而后端「用户名或密码错误」也正好返回 401，于是密码敲错时
   * 全链路一声不吭。所以这个请求自己处理提示，由登录页 catch 后弹。</p>
   */
  login: (data: { username: string; password: string }) =>
    request<LoginResponse>({ url: '/auth/login', method: 'post', data, silentError: true }),
  /**
   * 自助注册。后端**不回 token**（它只建账号 + 个人组织），所以注册成功后要用同一组
   * 凭据再走一次 {@link login}，别在这里猜一个「注册即登录」的行为。
   *
   * <p>同样关掉全局提示：注册失败（用户名已占用等）由注册页自己呈现，那边要展示得更具体。</p>
   */
  register: (data: { username: string; password: string; displayName?: string; email?: string }) =>
    request<UserVO>({ url: '/auth/register', method: 'post', data, silentError: true }),
  me: () => request<UserVO>({ url: '/auth/me' }),
  updateAccount: (data: { username?: string; displayName?: string; email?: string }) =>
    request<UserVO>({ url: '/auth/account', method: 'put', data }),
  updatePassword: (data: { oldPassword: string; newPassword: string }) =>
    request<void>({ url: '/auth/password', method: 'put', data }),
  logout: () => request<void>({ url: '/auth/logout', method: 'post' })
}

/* ------------------------------------------------------------------ 头像 */
/**
 * 账号头像：属于「人」而非组织，故不带组织段（{@code /api/avatar}）。
 * 上传与移除都回吐更新后的 {@link UserVO}，前端直接替换会话里的 user 即可。
 */
export const avatarApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<UserVO>({
      url: '/avatar', method: 'post', data: form,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  remove: () => request<UserVO>({ url: '/avatar', method: 'delete' })
}

/* ------------------------------------------------------------------ 组织上下文 */
/**
 * 外壳装载：账号 + 我的组织 + 上次所在组织。
 *
 * <p>与 {@code authApi.me} 的分工是后端 {@code /api/auth/me} 与 {@code /api/me} 的分工：
 * 前者只管登录态，后者才管顶栏。登录后的落地组织由这里决定。</p>
 */
export const meApi = {
  me: () => request<MeVO>({ url: '/me' })
}

/* ------------------------------------------------------------------ 知识库 */
/** 地址里的知识库段就是 slug（规范 §4.2），因此下列方法的第一个参数一律是 kbSlug。 */
export const kbApi = {
  page: (params: Record<string, unknown>) => request<PageResult<KbVO>>({ url: `${orgPath()}/kbs`, params }),
  /** 门户统计卡：跨组织口径，不经组织门（规范 §4.4） */
  stats: () => request<StatsVO>({ url: '/portal/stats' }),
  detail: (kbSlug: string) => request<KbVO>({ url: `${orgPath()}/kbs/${encodeURIComponent(kbSlug)}` }),
  create: (data: Record<string, unknown>) =>
    request<KbVO>({ url: `${orgPath()}/kbs`, method: 'post', data }),
  update: (kbSlug: string, data: Record<string, unknown>) =>
    request<KbVO>({ url: `${kbPath(kbSlug)}`, method: 'put', data }),
  /**
   * 库级配置：隐藏规则 + **未过滤**的完整树。
   *
   * <p>与 {@link page} 的目录树分开是因为两者要的正好相反：那边给「读者该看到的」（已过滤），
   * 这边给「作者该配置的」（未过滤）。合成一个就会出现「已隐藏的项无法取消隐藏」。</p>
   */
  config: (kbSlug: string) => request<KbConfigVO>({ url: `${kbPath(kbSlug)}/config` }),
  /**
   * 保存库级配置（整份覆盖，不是增量）。
   *
   * <p>{@code display} 省略或传 undefined 时后端不动那一分区，所以只改隐藏规则的旧调用
   * 不会被当成「把后缀关掉」—— 后者与前者缺省方向相反，不是疏忽。</p>
   */
  saveConfig: (kbSlug: string, hidden: string[], display?: KbDisplay) =>
    request<string[]>({ url: `${kbPath(kbSlug)}/config`, method: 'put', data: { hidden, display } }),
  remove: (kbSlug: string) => request<void>({ url: `${kbPath(kbSlug)}`, method: 'delete' }),
  favorite: (kbSlug: string, favored: boolean) =>
    request<{ favored: boolean }>({ url: `${kbPath(kbSlug)}/favorite`, method: favored ? 'delete' : 'post' }),
  uploadCover: (kbSlug: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ coverUrl: string }>({
      url: `${kbPath(kbSlug)}/cover`, method: 'post', data: form,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  /** 库内图片等资源都要过的组织门代理（后端 coverSrc 已是绝对到 org 的地址） */
  assetUrl: (kbSlug: string, relativePath: string) => kbUrl(kbSlug, `/asset/${encodePath(relativePath)}`),
  /** 维护名单（写轴的 members 档看这张表，规范 §2.3） */
  roster: (kbSlug: string) => request<KbRosterVO[]>({ url: `${kbPath(kbSlug)}/members` }),
  /** 授权 / 改角色 / 撤销：{@code role} 传 null 就是撤销这一行 */
  grantRoster: (kbSlug: string, userId: number, role: 'EDITOR' | 'VIEWER' | null) =>
    request<KbRosterVO>({ url: `${kbPath(kbSlug)}/members`, method: 'put', data: { userId, role } }),
  /** 改维护档位（owner_only / members / org_all）—— 与 visibility 是两条正交的轴 */
  setMaintainScope: (kbSlug: string, maintainScope: 'owner_only' | 'members' | 'org_all') =>
    request<{ maintainScope: string }>({ url: `${kbPath(kbSlug)}/maintain`, method: 'put', data: { maintainScope } }),
  /** 云端库工作副本状态（改动数 / 领先落后 / HEAD） */
  gitStatus: (kbSlug: string) => request<GitStatusVO>({ url: `${kbPath(kbSlug)}/git` }),
  /** 拉取远程更新到工作副本。本地脏或远程冲突时后端给 409，由调用方提示 */
  gitPull: (kbSlug: string) => request<GitSyncVO>({ url: `${kbPath(kbSlug)}/git/pull`, method: 'post' }),
  /** 提交并推送工作副本的改动 */
  gitCommit: (kbSlug: string, message?: string) =>
    request<GitSyncVO>({ url: `${kbPath(kbSlug)}/git/commit`, method: 'post', data: { message } })
}

/* ------------------------------------------------------------------ 文档 */
export const docApi = {
  tree: (kbSlug: string) => request<DocNode[]>({ url: `${kbPath(kbSlug)}/tree` }),
  read: (kbSlug: string, path: string) =>
    request<DocContentVO>({ url: `${kbPath(kbSlug)}/doc`, params: { path } }),
  create: (kbSlug: string, data: { path: string; content?: string }) =>
    request<PathChangeVO>({ url: `${kbPath(kbSlug)}/doc`, method: 'post', data }),
  /** 保存：带磁盘基线（缺列或不符 → 412），锁相关冲突（409/412）由编辑器分支处理 */
  save: (kbSlug: string, data: { path: string; content: string; baseSize?: number; baseMtime?: string }) =>
    request<PathChangeVO>({ url: `${kbPath(kbSlug)}/doc`, method: 'put', data, silentError: true }),
  /** 获取或续期编辑锁（幂等）。他人持有 → 409 + LockVO，因此静默交给调用方处理 */
  acquireLock: (kbSlug: string, path: string) =>
    request<LockVO>({ url: `${kbPath(kbSlug)}/lock`, method: 'post', params: { path }, silentError: true }),
  /** 释放锁：只在切换文档 / 卸载时触发，失败由锁自身的 TTL 收尾，不值得打断用户 */
  releaseLock: (kbSlug: string, path: string) =>
    request<void>({ url: `${kbPath(kbSlug)}/lock`, method: 'delete', params: { path }, silentError: true }),
  /** 探测锁：后台每 10s 一次，网络抖动时弹吐司会变成刷屏 */
  lockState: (kbSlug: string, path: string) =>
    request<LockVO>({ url: `${kbPath(kbSlug)}/lock`, params: { path }, silentError: true }),
  /** 页面卸载时归还锁要走 {@code fetch keepalive}，到不了 axios，只能给出完整地址 */
  lockUrl: (kbSlug: string, path: string) =>
    kbUrl(kbSlug, `/lock?path=${encodeURIComponent(path)}`),
  remove: (kbSlug: string, path: string) =>
    request<void>({ url: `${kbPath(kbSlug)}/doc`, method: 'delete', params: { path } }),
  rename: (kbSlug: string, data: { path: string; name: string }) =>
    request<PathChangeVO>({ url: `${kbPath(kbSlug)}/doc/rename`, method: 'post', data }),
  move: (kbSlug: string, data: { path: string; targetDir: string }) =>
    request<PathChangeVO>({ url: `${kbPath(kbSlug)}/doc/move`, method: 'post', data }),
  /** 同目录内自定义排序：把 path 排到 targetPath 之前/之后 */
  reorder: (kbSlug: string, data: { path: string; targetPath: string; position: 'before' | 'after' }) =>
    request<void>({ url: `${kbPath(kbSlug)}/doc/reorder`, method: 'post', data }),
  createDir: (kbSlug: string, path: string) =>
    request<PathChangeVO>({ url: `${kbPath(kbSlug)}/dir`, method: 'post', data: { path } }),
  render: (kbSlug: string, path: string) =>
    request<RenderVO>({ url: `${kbPath(kbSlug)}/render`, params: { path } }),
  uploadImage: (kbSlug: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ path: string }>({
      url: `${kbPath(kbSlug)}/upload`, method: 'post', data: form,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  /**
   * 导入 Markdown / ZIP。
   *
   * <p>目录导入要还原子树，但 multipart 的 filename 只有基名，浏览器不会把
   * {@code webkitRelativePath} 塞进去，所以另发一份与 {@code files} 同序的
   * {@code paths}，由后端按下标对上号。{@code relativeOf} 省略时该字段不发，
   * 后端退回「按文件名平铺」的老口径。</p>
   */
  import: (kbSlug: string, targetDir: string, files: File[], relativeOf?: (file: File) => string) => {
    const form = new FormData()
    files.forEach((file) => form.append('files', file))
    if (relativeOf) {
      const paths = files.map((file) => relativeOf(file))
      if (paths.some((path) => path)) form.append('paths', JSON.stringify(paths))
    }
    return request<ImportResult>({
      url: `${kbPath(kbSlug)}/import`, method: 'post', params: { targetDir },
      data: form, headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  downloadUrl: (kbSlug: string, path: string) =>
    kbUrl(kbSlug, `/doc/download?path=${encodeURIComponent(path)}`)
}

/* ------------------------------------------------------------------ 分享 */
/**
 * 组织内分享管理。
 *
 * <p>目标是 {@code kbSlug} 而不是库 id：地址栏只带组织 + slug（规范 §4.2），传 id 会逼前端再存一份
 * id↔slug 映射。匿名入口 {@code /api/share/{token}} 不在此列，token 自身就是寻址手段。</p>
 */
export const shareApi = {
  create: (data: Record<string, unknown>) =>
    request<ShareVO>({ url: `${orgPath()}/shares`, method: 'post', data }),
  page: (params: Record<string, unknown>) => request<PageResult<ShareVO>>({ url: `${orgPath()}/shares`, params }),
  info: (kbSlug: string, docPath?: string) =>
    request<ShareVO | null>({ url: `${orgPath()}/shares/info`, params: { kbSlug, docPath } }),
  update: (token: string, data: Record<string, unknown>) =>
    request<ShareVO>({ url: `${orgPath()}/shares/${encodeURIComponent(token)}`, method: 'put', data }),
  revoke: (token: string) => request<void>({ url: `${orgPath()}/shares/${encodeURIComponent(token)}`, method: 'delete' })
}

/* ------------------------------------------------------------------ 组织与成员 */
/**
 * 组织内治理（{@code /api/console/{org}/**}，全部经成员门）。
 *
 * <p>没有组织参数的方法都依赖 {@link orgPath}，也就是依赖地址栏 —— 组件不要把这些调用
 * 挪到脱离 {@code /console/{org}} 的地方去。</p>
 */
export const orgApi = {
  detail: () => request<OrgVO>({ url: orgPath() }),
  update: (data: Record<string, unknown>) => request<OrgVO>({ url: orgPath(), method: 'put', data }),
  remove: () => request<void>({ url: orgPath(), method: 'delete' }),
  members: () => request<OrgMemberVO[]>({ url: `${orgPath()}/members` }),
  addMember: (username: string, role: 'ADMIN' | 'MEMBER') =>
    request<OrgMemberVO>({ url: `${orgPath()}/members`, method: 'post', data: { username, role } }),
  setRole: (userId: number, role: 'ADMIN' | 'MEMBER') =>
    request<OrgMemberVO>({ url: `${orgPath()}/members/${userId}/role`, method: 'put', data: { role } }),
  removeMember: (userId: number) => request<void>({ url: `${orgPath()}/members/${userId}`, method: 'delete' }),
  transferOwner: (userId: number) =>
    request<void>({ url: `${orgPath()}/transfer-owner`, method: 'post', data: { userId } }),
  leave: () => request<void>({ url: `${orgPath()}/leave`, method: 'post' }),
  joinRequests: () => request<JoinRequestVO[]>({ url: `${orgPath()}/join-requests` }),
  reviewJoinRequest: (id: number, approve: boolean) =>
    request<JoinRequestVO>({ url: `${orgPath()}/join-requests/${id}/${approve ? 'approve' : 'reject'}`, method: 'post' }),
  audit: (params: Record<string, unknown>) => request<PageResult<AuditVO>>({ url: `${orgPath()}/audit`, params })
}

/* ------------------------------------------------------------------ 组织级（不经成员门） */
/**
 * 建组织与发现列表：入口不带组织段，所以它们不走 {@link orgPath}。
 *
 * <p>{@code applyJoin} 是成员门的唯一例外（申请人按定义还不是成员），后端对它单独放行。</p>
 */
export const tenantApi = {
  create: (data: Record<string, unknown>) => request<OrgVO>({ url: '/tenants', method: 'post', data }),
  discover: (keyword?: string) => request<DiscoverOrgVO[]>({ url: '/tenants/discover', params: { keyword } }),
  applyJoin: (slug: string, message?: string) =>
    request<void>({ url: `/console/${encodeURIComponent(slug)}/join-request`, method: 'post', data: { message } })
}

/* ------------------------------------------------------------------ 用户 / 设置 / 搜索 */
/**
 * 平台侧账号治理：列用户、审核历史 pending 账号、启停、重置密码、删除。
 *
 * <p>地址在 {@code /api/platform/**} 而非 {@code /api/admin/**}：这条通道的门槛是**平台角色**，
 * 与任何组织的 OWNER/ADMIN 无关（规范 §3.4 把「组织管理台」和「平台超管后台」分成两处）。</p>
 */
export const userApi = {
  page: (params: Record<string, unknown>) => request<PageResult<AdminUserVO>>({ url: '/platform/users', params }),
  update: (id: number, data: Record<string, unknown>) =>
    request<AdminUserVO>({ url: `/platform/users/${id}`, method: 'put', data }),
  status: (id: number, action: 'approve' | 'reject' | 'pending' | 'disable' | 'enable') =>
    request<AdminUserVO>({ url: `/platform/users/${id}/${action}`, method: 'post' }),
  resetPassword: (id: number, newPassword: string) =>
    request<void>({ url: `/platform/users/${id}/password`, method: 'post', data: { newPassword } }),
  remove: (id: number) => request<void>({ url: `/platform/users/${id}`, method: 'delete' })
}

export const settingsApi = {
  get: () => request<Record<string, unknown>>({ url: '/settings' }),
  update: (patch: Record<string, unknown>) =>
    request<Record<string, unknown>>({ url: '/settings', method: 'put', data: patch })
}

/* ------------------------------------------------------------------ 站点配置 */
/**
 * 站点品牌（名称 / 副标题 / Logo）。
 *
 * <p>读走公开的 {@code /portal/site}，写走 {@code /platform/site} —— 门槛是平台管理员，
 * 与组织角色无关：站点是全部署一份的东西，不能由某个组织的管理员改掉。</p>
 */
export const siteApi = {
  get: () => request<SiteConfig>({ url: '/portal/site' }),
  update: (patch: Record<string, unknown>) =>
    request<SiteConfig>({ url: '/platform/site', method: 'put', data: patch }),
  uploadLogo: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<SiteConfig>({
      url: '/platform/site/logo', method: 'post', data: form,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  removeLogo: () => request<SiteConfig>({ url: '/platform/site/logo', method: 'delete' })
}

/* 全局搜索面板（Ctrl/⌘+K）在门户与管理台共用同一份，走 {@code user/api} 的 searchApi：
   两点都在 {@code /api/search*}，复制一份只会让「哪边接了全文检索」变成两份漂移的账。 */

export function encodePath(path: string) {
  return (path || '')
    .split('/')
    .map((segment) => encodeURIComponent(segment).replace(/%20/g, '%20'))
    .join('/')
}
