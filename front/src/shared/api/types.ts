export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 后端 KbAction 的名字，detail 出参用 Enum::name 序列化 */
/**
 * 后端 {@code KbAction} 的逐一对应（规范 §2.1）。
 *
 * <p>这份清单是抄来的，不是判定依据 —— 前端只用它决定按钮显隐。改动后端枚举时记得同步，
 * 漏一项只会让某个按钮永远不出现，不会放开权限。</p>
 */
export type KbActionName =
  | 'KB_VIEW' | 'DOC_READ' | 'DOC_WRITE' | 'DOC_RENAME' | 'DOC_MOVE' | 'DOC_DELETE'
  | 'DOC_UPLOAD_ASSET' | 'DOC_IMPORT' | 'KB_EDIT_META' | 'KB_DELETE' | 'KB_SET_VISIBILITY'
  | 'KB_MEMBER_MANAGE' | 'KB_CREATE' | 'SHARE_CREATE' | 'SHARE_REVOKE' | 'SHARE_UPDATE' | 'SHARE_VIEW_STATS'
  | 'TENANT_VIEW' | 'TENANT_RENAME' | 'TENANT_DELETE' | 'TENANT_TRANSFER' | 'TENANT_MEMBER_MANAGE'
  | 'TENANT_APPOINT_ADMIN' | 'TENANT_JOIN_REVIEW' | 'AUDIT_READ'

export interface KbVO {
  id: number
  ownerId: number
  ownerName?: string
  name: string
  slug: string
  /** 所属组织标识。URL 与资源地址都要靠它，缺失时后端不会拼出 coverSrc */
  tenantSlug?: string
  /** 所属组织显示名（规范 F10：跨组织的公开库列表要标出它属于谁）；名称缺失时前端退回 slug */
  tenantName?: string
  description?: string
  visibility: 'public' | 'org' | 'private'
  /**
   * 维护档位（写轴），与 {@link visibility}（读轴）正交（规范 §1.2）。
   *
   * <p>成员档（members）的含义是「维护名单上的人可写」，名单本身在
   * {@code GET /kbs/{slug}/members}；档位为 owner_only 时名单不产生权利，UI 要说明这一点。</p>
   */
  maintainScope?: 'owner_only' | 'members' | 'org_all'
  coverUrl?: string
  coverSrc?: string
  tags: string[]
  docCount: number
  favored: boolean
  shareStatus?: string
  directoryPath?: string
  /**
   * 当前用户在这个库上的动作集，列表 / 详情 / 创建都会下发（规范 §4.2）。
   *
   * <p>空数组的含义是「本次没算」（门户那几条链路不给），不是「什么都不能做」；
   * 前端只用它决定按钮显隐，真判定始终在后端。</p>
   */
  myPermissions?: KbActionName[]
  createdAt?: string
  updatedAt?: string
  updatedText?: string
  /** 内容来源：local = 服务器上的工作目录，git = 绑定线上仓库（磁盘那份是工作副本） */
  sourceType?: 'local' | 'git'
  /** 云端库的绑定信息；本地库后端不下发这一块 */
  git?: GitBindingVO
}

/** 云端知识库的绑定信息（令牌只以 tokenSet 回传，明文不出后端）。 */
export interface GitBindingVO {
  url: string
  branch: string
  username?: string
  /** 是否已配置访问令牌；界面只需知道「配过没有」 */
  tokenSet: boolean
  lastSyncAt?: string
  lastSyncStatus?: string
  lastSyncOk?: boolean
}

/** GET /kbs/{slug}/git：工作副本状态。 */
export interface GitStatusVO {
  git?: GitBindingVO
  /** 目录当前是不是可用的 Git 工作副本 */
  repository: boolean
  clean: boolean
  changedCount: number
  ahead: number
  behind: number
  headCommit?: string
  changedPaths: string[]
}

/** POST /kbs/{slug}/git/pull | /git/commit：一次同步的结果。 */
export interface GitSyncVO {
  success: boolean
  message: string
  changedCount: number
  commitId?: string
  git?: GitBindingVO
}

/** 组织切换器条目（GET /api/me）。 */
export interface TenantBrief {
  id: number
  slug: string
  name: string
  type: 'TEAM' | 'PERSONAL'
  /** 我在该组织的角色：只用于显示「我是 OWNER」，按钮可用性读 {@link myPermissions} */
  role: string
  owner: boolean
  /** 后端切换器一并下发的组织级动作集，与 {@code OrgVO.myPermissions} 同口径 */
  myPermissions?: KbActionName[]
}

/** GET /api/me：应用外壳一次性装载。lastTenantSlug 只决定落地页，不参与任何权限判断。 */
export interface MeVO {
  user: UserVO
  tenants: TenantBrief[]
  lastTenantSlug?: string
}

/** GET /api/console/{org}：组织详情 + 我自己在里面的角色。 */
export interface OrgVO {
  id: number
  slug: string
  name: string
  type: 'TEAM' | 'PERSONAL'
  description?: string
  logoUrl?: string
  /** request = 开放申请；invite_only = 只由管理员直接添加 */
  joinPolicy: 'request' | 'invite_only'
  discoverable: boolean
  status: string
  myRole: 'OWNER' | 'ADMIN' | 'MEMBER'
  /**
   * 我在这个组织里的动作集（TENANT_* 与 KB_CREATE，规范 §4.2）。
   *
   * <p>{@link myRole} 留着是为了显示「我是 OWNER」，治理按钮的显隐一律读这里 ——
   * 组织详情的出参与 {@code TenantBrief} 同源，切换器能算出来的动作集这里也能算出来。</p>
   */
  myPermissions?: KbActionName[]
  memberCount: number
  kbCount: number
  createdAt?: string
  createdText?: string
}

/** 组织成员行。 */
export interface OrgMemberVO {
  userId: number
  username: string
  displayName?: string
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  orgAdmin: boolean
  joinedFrom?: string
  joinedAt?: string
}

/** 入组申请行（OWNER / ADMIN 可见）。 */
export interface JoinRequestVO {
  id: number
  userId: number
  username: string
  displayName?: string
  message?: string
  status: 'pending' | 'approved' | 'rejected'
  createdAt?: string
  reviewedAt?: string
}

/** GET /api/tenants/discover 的条目：只有公开字段。 */
export interface DiscoverOrgVO {
  slug: string
  name: string
  description?: string
  logoUrl?: string
  joinPolicy: 'request' | 'invite_only'
  memberCount: number
  publicKbCount: number
}

/**
 * 库维护名单的一行（规范 §9）。
 *
 * <p>{@code orgMember=false} 表示这个人已经不在组织里，这一行**当前不产生任何权利** ——
 * 它仍然留在表中，是因为「离开组织」与「撤销授权」是两件事；UI 必须把这行标出来，
 * 不能让人以为还在授权中。</p>
 */
export interface KbRosterVO {
  userId: number
  username: string
  displayName?: string
  role: 'EDITOR' | 'VIEWER'
  grantedBy?: number
  orgMember: boolean
  createdAt?: string
}

/** 审计流的一行。detail 是后端解回的对象，形状随 action 而变。 */
export interface AuditVO {
  id: number
  actorUserId?: number
  actor?: string
  action: string
  kbId?: number
  kbName?: string
  docPath?: string
  detail?: Record<string, unknown> | string | null
  createdAt?: string
  createdText?: string
}

export interface StatsVO {
  kbTotal: number
  kbPublic: number
  kbOrg: number
  kbPrivate: number
  publicDocTotal: number
  docTotal: number
  kbTotalDelta: number
  docTotalDelta: number
}

export interface DocNode {
  name: string
  path: string
  encodedPath: string
  type: 'dir' | 'doc'
  size: number
  modifiedAt?: string
  children: DocNode[]
}

export interface DocContentVO {
  path: string
  name: string
  title: string
  content: string
  size: number
  modifiedAt?: string
}

/** 编辑锁状态（规范 §4.3）。locked=false 时持有者字段为 null，ttlSeconds 照旧下发。 */
export interface LockVO {
  path: string
  locked: boolean
  holderUserId?: number
  holder?: string
  nickname?: string
  acquiredAt?: string
  expiresAt?: string
  mine: boolean
  ttlSeconds: number
}

export interface OutlineNode {
  id: string
  text: string
  level: number
  children: OutlineNode[]
}

export interface DocMeta {
  title?: string
  summary?: string
  tags: string[]
  author?: string
  publishedAt?: string
  wordCount: number
  readingMinutes: number
  size: number
  modifiedAt?: string
}

export interface RenderVO {
  html: string
  outline: OutlineNode[]
  frontmatter: Record<string, unknown>
  meta: DocMeta
  etag: string
  rawContent: string
}

export interface PathChangeVO {
  path: string
  name: string
}

export interface ImportResult {
  imported: number
  skipped: number
  failed: number
  messages: string[]
}

/**
 * 分享页顶部导航条的一项（后端 {@code NavMenuItem}）。
 *
 * <p>存库的只有 {@code type} 与 {@code path}：{@code name} 由后端按路径末段推导后下发，
 * 目录改名后不会留下一份漂移的旧名字。{@code dir} 项点开的是该目录下的第一篇文档，
 * 并把左侧目录树收窄到这一支。</p>
 */
export interface NavMenuItem {
  type: 'dir' | 'doc'
  path: string
  name?: string
}

export interface ShareVO {
  id: number
  token: string
  url?: string
  kbId: number
  /** 目标库标识：分享管理台与列表都按 slug 认库（规范 §4.2），id 只用于跳转前的兜底 */
  kbSlug?: string
  kbName?: string
  scope: 'kb' | 'doc'
  docPath?: string
  docName?: string
  encrypted: boolean
  expiresIn: string
  expiresAt?: string
  views: number
  uv: number
  status: string
  /**
   * 这一条我能不能改 / 撤销：后端按「分享创建者或组织管理员」给的结论（规范 §2.4）。
   *
   * <p>前端看不到 owner 也看不到组织档位，缺了它就只能把所有行的撤销按钮都点亮。</p>
   */
  canGovern?: boolean
  /** 导航菜单配置；整库分享才有，单篇分享与未配置时为空数组 */
  menu?: NavMenuItem[]
  createdAt?: string
  updatedAt?: string
}

export interface UserVO {
  id: number
  username: string
  displayName?: string
  email?: string
  role: string
  status: string
  /** 头像文件名（库里存的原文），仅用于「有没有头像」这类判断 */
  avatarUrl?: string
  /** 头像可直接放进 img src 的地址；无头像时为空 */
  avatarSrc?: string
  createdAt?: string
}

export interface AdminUserVO extends UserVO {
  kbCount: number
}

export interface LoginResponse {
  token: string
  expiresIn: number
  user: UserVO
  settings: Record<string, unknown>
}

/**
 * 站点品牌信息（后端 {@code SiteConfigVO}）。
 *
 * <p>全部署一份，门户顶栏、阅读页、分享页、后台侧栏共用。{@code logo} 是库里存的文件名，
 * {@code logoSrc} 才是可直接放进 {@code <img src>} 的地址；没配 Logo 时两者都缺键。</p>
 */
export interface SiteConfig {
  name: string
  subtitle?: string
  logo?: string
  logoSrc?: string
}

export interface SuggestItem {
  type: string
  title: string
  subtitle?: string
  url: string
}

export interface SearchHit {
  kbId: string
  kbName: string
  path: string
  title: string
  snippet: string
  /** 服务端拼好的门户地址（{@code /kb/{org}/{slug}?path=...}），前端拿到什么跳什么 */
  url: string
}
