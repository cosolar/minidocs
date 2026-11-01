import { ref } from 'vue'

/**
 * 阅读主题偏好（纯浏览器端）。
 *
 * <p>主题包由后端存成 JSON（{@code data/site/themes/*.json}），每套自带明暗两档令牌；
 * 这里只负责「读列表 → 校验 → 拼一段 &lt;style&gt; 写到页面上」。主题包不发 CSS，
 * 因此不存在选择器注入，也不会碰到脚本。</p>
 *
 * <p><b>作用范围与正文字体一致，是三个宿主：</b>门户阅读页、分享页、后台编辑器预览。
 * 令牌挂在 {@code body.md-page--read}（阅读页整页换肤，含顶栏与侧栏）与
 * {@code .md-ws__editor .markdown-body}（只换正文预览，后台工具栏保持管理端配色）。
 * 暗色令牌挂在 {@code [data-theme='dark']} 下，与现有的明亮/暗黑切换正交。</p>
 *
 * <p><b>代码高亮令牌单列一组。</b>它们只在「代码块主题 = 跟随阅读主题」时生效 —— 在管理端
 * 账号设置里显式选了 GitHub / Monokai 的人，不该因为换了个阅读主题就被改掉代码配色。
 * 选择器的优先级也是为这件事服务的：普通令牌写在 body 上，代码令牌多带一个
 * {@code [data-code-theme='theme']} 前提。</p>
 *
 * <p>偏好写 localStorage 而不是设置 store：主题是「这台设备上读起来顺眼的那一套」，
 * 分享页的读者又是访客，服务端没有他的账号可写。列表另存一份缓存，断网或后端还没起来时，
 * 上次见过的主题仍然可用。</p>
 */

const STORAGE_KEY = 'minidocs.reader-theme'
const CACHE_KEY = 'minidocs.reader-themes'
const STYLE_ID = 'md-reader-theme'

export interface ThemePack {
  id: string
  name: string
  description?: string | null
  author?: string | null
  version?: string | null
  license?: string | null
  light: Record<string, string>
  dark: Record<string, string>
  builtin?: boolean
}

/** 内置默认主题：令牌为空，样式仍由 variables.css 提供，不另存一份色值。 */
export const DEFAULT_THEME: ThemePack = {
  id: 'default',
  name: '澄澈',
  description: 'MiniDocs 默认主题：雾蓝底、蓝青强调，明亮与暗黑都按长文阅读调过对比度。',
  author: 'MiniDocs',
  version: '1.0.0',
  license: 'MIT',
  light: {},
  dark: {},
  builtin: true
}

const TOKEN_NAME = /^--md-[a-z0-9-]{1,60}$/

/** 与后端 ThemeServiceImpl 的同名规则保持一致：两侧都拦，装到一半的主题也不会被执行。 */
const UNSAFE_VALUE = /url\s*\(|image-set\s*\(|expression\s*\(|javascript:|@import|behavior\s*:|-moz-binding|!important|[;{}<>\\]|[\u0000-\u001f\u007f]/i

/** 读者自己的字体 / 字号 / 宽度偏好由各自的控件说了算，主题包不许盖。 */
const RESERVED_TOKENS = new Set([
  '--md-read-font',
  '--md-read-scale',
  '--md-read-size',
  '--md-doc-width',
  '--md-content-width',
  '--md-aside-width',
  '--md-outline-width',
  '--md-topbar-height'
])

const MAX_TOKEN_VALUE = 240

/** 已安装主题（含默认那套），面板与后台主题卡片都读它。 */
export const themePacks = ref<ThemePack[]>([DEFAULT_THEME])
export const currentThemeId = ref<string>(DEFAULT_THEME.id)

export interface ThemeGateway {
  list(): Promise<ThemePack[]>
}

let gateway: ThemeGateway | null = null

export function registerThemeGateway(impl: ThemeGateway) {
  gateway = impl
}

function safeTokens(raw: unknown): Record<string, string> {
  if (!raw || typeof raw !== 'object') return {}
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (!TOKEN_NAME.test(key) || RESERVED_TOKENS.has(key)) continue
    const text = typeof value === 'string' ? value.trim() : ''
    if (!text || text.length > MAX_TOKEN_VALUE || UNSAFE_VALUE.test(text)) continue
    result[key] = text
  }
  return result
}

/**
 * 把服务端 / 缓存里的一条记录归一成主题包。
 *
 * <p>明暗两档必须同一组令牌：缺一条时补齐并不会更安全，反而会让浅色下看得见的颜色在暗色下
 * 悄悄回落到默认值，看着像「这套主题暗色没做完」。校验不过的包整条丢弃。</p>
 */
function normalizePack(raw: unknown): ThemePack | null {
  if (!raw || typeof raw !== 'object') return null
  const item = raw as Record<string, unknown>
  const id = typeof item.id === 'string' ? item.id.trim() : ''
  const name = typeof item.name === 'string' ? item.name.trim() : ''
  if (!/^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$/.test(id) || !name) return null
  const light = safeTokens(item.light)
  const dark = safeTokens(item.dark)
  const lightKeys = Object.keys(light)
  if (!lightKeys.length || lightKeys.length !== Object.keys(dark).length) return null
  if (lightKeys.some((key) => !(key in dark))) return null
  return {
    id,
    name: name.slice(0, 40),
    description: typeof item.description === 'string' ? item.description.trim().slice(0, 200) : null,
    author: typeof item.author === 'string' ? item.author.trim().slice(0, 40) : null,
    version: typeof item.version === 'string' ? item.version.trim().slice(0, 20) : null,
    license: typeof item.license === 'string' ? item.license.trim().slice(0, 30) : null,
    light,
    dark,
    // default 永远内置：即便后端是旧版、列表里没带它，前端也要保证它不可卸载
    builtin: item.builtin === true || id === DEFAULT_THEME.id
  }
}

function sortPacks(packs: ThemePack[]): ThemePack[] {
  return [...packs].sort((a, b) => {
    if (a.builtin !== b.builtin) return a.builtin ? -1 : 1
    return a.name.localeCompare(b.name, 'zh-Hans-CN')
  })
}

/**
 * 合并默认主题 + 去重 + 排序。默认永远排第一。
 *
 * <p>default 现在也是一份 JSON 包（含全套令牌），服务端列表里会带着它；旧后端 / 离线时
 * 列表没有 default，这里补回零令牌的兜底版（样式由 variables.css 提供）。</p>
 */
function mergePacks(raw: unknown): ThemePack[] {
  const byId = new Map<string, ThemePack>([[DEFAULT_THEME.id, DEFAULT_THEME]])
  if (Array.isArray(raw)) {
    for (const entry of raw) {
      const pack = normalizePack(entry)
      if (pack) byId.set(pack.id, pack)
    }
  }
  const rest = [...byId.values()].filter((pack) => pack.id !== DEFAULT_THEME.id)
  return [byId.get(DEFAULT_THEME.id)!, ...sortPacks(rest)]
}

export function readerThemeById(id: string | null | undefined): ThemePack {
  return themePacks.value.find((pack) => pack.id === id) || DEFAULT_THEME
}

function readStoredId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

/**
 * 服务端下发的「默认阅读主题」（管理员在站点设置里选的）。
 *
 * <p>它只对没选过主题的访客生效：读者一旦在本地选过，就永远以读者的为准 —— 所以这里
 * 不写 localStorage，管理员以后换默认主题，这批「没选过」的访客也能跟着换。</p>
 */
let serverDefaultId: string | null = null

export function setServerDefaultTheme(id: string | null | undefined) {
  serverDefaultId = id && id !== DEFAULT_THEME.id ? id : null
  if (!readStoredId()) applyReaderTheme(preferredId(), false)
}

function preferredId(): string {
  return readStoredId() || serverDefaultId || DEFAULT_THEME.id
}

function readCache(): ThemePack[] | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    return raw ? mergePacks(JSON.parse(raw)) : null
  } catch {
    return null
  }
}

function writeCache(packs: ThemePack[]) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(packs.filter((pack) => pack.id !== DEFAULT_THEME.id)))
  } catch {
    /* 存不下就算了，列表下次再拉 */
  }
}

/** 把一组令牌拼成声明块；值已在 normalize 阶段筛过，不含分号与花括号。 */
function declarations(tokens: Record<string, string>): string {
  return Object.entries(tokens)
    .map(([key, value]) => `${key}:${value};`)
    .join('')
}

/**
 * 写主题样式表。
 *
 * <p>每次切换都整段替换，不累加：两套主题的令牌同名，叠加只会变成「谁后写谁赢」，
 * 上一套漏删一条就会在下一套里留下一个孤立的颜色。</p>
 */
function renderTheme(pack: ThemePack) {
  const style = document.getElementById(STYLE_ID) || document.createElement('style')
  style.id = STYLE_ID
  if (!style.parentNode) document.head.appendChild(style)

  // default 也是普通包了：有令牌就渲染，没有（兜底版）则各段为空，样式自然回落到 variables.css
  const baseLight: Record<string, string> = {}
  const baseDark: Record<string, string> = {}
  const codeLight: Record<string, string> = {}
  const codeDark: Record<string, string> = {}
  for (const [key, value] of Object.entries(pack.light)) {
    (key.startsWith('--md-tk-') ? codeLight : baseLight)[key] = value
  }
  for (const [key, value] of Object.entries(pack.dark)) {
    (key.startsWith('--md-tk-') ? codeDark : baseDark)[key] = value
  }

  const parts: string[] = []
  if (Object.keys(baseLight).length) {
    parts.push(`body.md-page--read,.md-ws__editor .markdown-body{${declarations(baseLight)}}`)
  }
  if (Object.keys(baseDark).length) {
    parts.push(
      `[data-theme='dark'] body.md-page--read,[data-theme='dark'] .md-ws__editor .markdown-body{${declarations(baseDark)}}`
    )
  }
  if (Object.keys(codeLight).length) {
    parts.push(
      `html[data-code-theme='theme'] body.md-page--read,html[data-code-theme='theme'] .md-ws__editor .markdown-body{${declarations(codeLight)}}`
    )
  }
  if (Object.keys(codeDark).length) {
    parts.push(
      `html[data-theme='dark'][data-code-theme='theme'] body.md-page--read,html[data-theme='dark'][data-code-theme='theme'] .md-ws__editor .markdown-body{${declarations(codeDark)}}`
    )
  }
  style.textContent = parts.join('\n')
}

/**
 * 应用主题：写样式表与 localStorage。
 *
 * <p>传进来的 id 不存在（主题被管理员卸载、或 localStorage 里是旧值）时回落到默认主题，
 * 不把页面留在「样式表还是旧主题、状态却显示默认」的半截状态。</p>
 *
 * <p><code>persist</code> 关掉时不写 localStorage：套用服务端默认主题属于「访客还没选」，
 * 落了盘就成了读者自己的选择，管理员以后换默认主题就够不到他了。持久化只发生在
 * 读者显式选择（ReaderPrefsMenu）与回放自己存储的那一次。</p>
 */
export function applyReaderTheme(id: string, persist = true) {
  const exists = themePacks.value.some((pack) => pack.id === id)
  const pack = exists ? readerThemeById(id) : DEFAULT_THEME
  currentThemeId.value = pack.id
  renderTheme(pack)
  if (persist && (exists || id === DEFAULT_THEME.id)) {
    try {
      localStorage.setItem(STORAGE_KEY, pack.id)
    } catch {
      /* 存不下就算了，本次会话内仍然生效 */
    }
  }
}

/**
 * 应用启动时调用：先用缓存把上次的主题套上，网络请求回来后再校正一次。
 *
 * <p>先读缓存是为了首屏不闪默认色 —— 主题接口是公开的，但仍要走一次网络；
 * 等它回来再上色，读者会先看到一帧默认主题。</p>
 */
export function initReaderTheme() {
  const cached = readCache()
  if (cached) themePacks.value = cached
  // 回放本地选择时才允许落盘；落到服务端默认的那次不持久化
  applyReaderTheme(preferredId(), Boolean(readStoredId()))
}

/** 从服务端拉取已安装主题；失败时保留缓存与默认主题。 */
export async function loadReaderThemes() {
  if (!gateway) return themePacks.value
  try {
    const packs = mergePacks(await gateway.list())
    themePacks.value = packs
    writeCache(packs)
    // 选中的主题可能刚被管理员卸载：重新套用一次，让它落回默认
    applyReaderTheme(preferredId(), Boolean(readStoredId()))
  } catch {
    /* 离线或接口异常：沿用缓存，页面不该因为主题列表拿不到就白屏 */
  }
  return themePacks.value
}

/** 主题卡片上的三色小样：底色 + 主强调 + 次强调。 */
export interface ThemeSwatch {
  bg: string
  primary: string
  accent: string
}

export function themeSwatch(pack: ThemePack, mode: 'light' | 'dark'): ThemeSwatch {
  const tokens = mode === 'dark' ? pack.dark : pack.light
  const fallback = mode === 'dark'
    ? { bg: '#161922', primary: '#4dbfa8', accent: '#2dd4bf' }
    : { bg: '#ffffff', primary: '#128577', accent: '#14b8a6' }
  return {
    bg: tokens['--md-surface'] || tokens['--md-bg'] || fallback.bg,
    primary: tokens['--md-ink-blue'] || tokens['--md-primary'] || fallback.primary,
    accent: tokens['--md-accent'] || tokens['--md-ink-teal'] || fallback.accent
  }
}
