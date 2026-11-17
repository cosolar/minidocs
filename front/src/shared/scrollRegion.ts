/**
 * 滚动区的取用与位置记忆（规范 §11.13）。
 *
 * <p>页面本身不滚（{@code body { overflow: hidden }），所以 {@code window} 上的滚动事件、
 * {@code window.scrollY}、Vue Router 自带的 {@code savedPosition} 全部失效 ——
 * 它们读到的永远是 0。谁需要滚动，谁就取这里的元素，别再拿 document 当容器。</p>
 *
 * <p>默认滚动区是 {@code App.vue} 里的 {@code #md-view}。个别页面（分享页的正文列）把它做成
 * 内层滚动区，用 {@link SCROLL_ATTR} 标出来；{@link scrollEl} 负责在两者之间做选择。</p>
 */
export const VIEW_ID = 'md-view'

/** 页面内自声明的滚动区标记，值任意，只看有没有这个属性 */
export const SCROLL_ATTR = 'data-md-scroll'

export function viewEl(): HTMLElement | null {
  return document.getElementById(VIEW_ID)
}

/**
 * 当前真正在滚的那个容器。
 *
 * <p>声明了 {@link SCROLL_ATTR} 的内层区域优先，但「声明了」不等于「这次真能滚」：分享页在窄屏
 * 会把滚动退回页面级（见 reader.css 的媒体查询），内层那一栏此时是 {@code overflow: visible}，
 * 往它身上写 {@code scrollTop}、监听它的 scroll 事件都不会有反应，所以按样式再判一次。
 * 判据读的是计算样式而不是 {@code scrollHeight}：内容短时内层高度恰好等于内容，两个值相等，
 * 但窗口一放大它就该重新能滚，只有样式是稳定的依据。</p>
 */
export function scrollEl(): HTMLElement | null {
  const view = viewEl()
  if (!view) return null
  const inner = view.querySelector<HTMLElement>(`[${SCROLL_ATTR}]`)
  if (inner && getComputedStyle(inner).overflowY !== 'visible') return inner
  return view
}

/* ------------------------------------------------------------ 锚点跳转 */

/**
 * 锚点跳转的动画时长上限（毫秒）。
 *
 * <p>原生 {@code scrollIntoView({ behavior: 'smooth' })} 的时长跟着距离走：几百个标题的长文里
 * 从文末点回开头，要飘上一两秒才停，定位体验等于卡住。这里自己按帧推进，时长封顶、距离越短越快 ——
 * 点哪一节都该在同一个呼吸之间到位。</p>
 */
const JUMP_MAX_MS = 320

/** 连续点击时只保留最后一次跳转：新一跳让上一跳的帧循环自行退出 */
let jumpRun = 0

/** 最近的可滚动祖先：正文列、页面滚动区各不相同，按实际样式判断而不是猜 */
function scrollParent(el: HTMLElement): HTMLElement | null {
  let node = el.parentElement
  while (node) {
    const overflowY = getComputedStyle(node).overflowY
    if ((overflowY === 'auto' || overflowY === 'scroll') && node.scrollHeight > node.clientHeight + 1) {
      return node
    }
    node = node.parentElement
  }
  return null
}

/**
 * 把某个锚点元素滚进视图（大纲 / 标题跳转）。
 *
 * <p>对齐位置读 {@code scroll-margin-top}，与原生 {@code scrollIntoView} 的落点保持一致；
 * 动画期间一旦读者自己滚轮 / 触摸 / 按键，立刻让位 —— 人优先于程序。</p>
 */
export function jumpToElement(target: HTMLElement) {
  // 页面刚打开时的位置恢复会逐帧钉住旧位置，读者这一跳必须优先于它
  cancelRestore()
  const container = scrollParent(target)
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

  if (!container) {
    target.scrollIntoView({ block: 'start', behavior: reduce ? 'auto' : 'smooth' })
    return
  }

  const marginTop = Number.parseFloat(getComputedStyle(target).scrollMarginTop) || 0
  const dest = Math.max(
    0,
    Math.min(
      container.scrollTop + target.getBoundingClientRect().top - container.getBoundingClientRect().top - marginTop,
      container.scrollHeight - container.clientHeight,
    ),
  )
  const start = container.scrollTop
  const run = ++jumpRun

  const cleanup = () => {
    window.removeEventListener('wheel', cancel, true)
    window.removeEventListener('touchstart', cancel, true)
    window.removeEventListener('keydown', cancel, true)
  }
  const cancel = () => {
    jumpRun++
    cleanup()
  }

  if (reduce || Math.abs(dest - start) < 2) {
    container.scrollTop = dest
    return
  }

  window.addEventListener('wheel', cancel, { capture: true, passive: true })
  window.addEventListener('touchstart', cancel, { capture: true, passive: true })
  window.addEventListener('keydown', cancel, true)

  const duration = Math.min(JUMP_MAX_MS, 140 + Math.abs(dest - start) * 0.25)
  const started = performance.now()
  const easeOutCubic = (t: number) => 1 - (1 - t) ** 3
  const step = (now: number) => {
    if (run !== jumpRun) return
    const progress = Math.min(1, (now - started) / duration)
    container.scrollTop = start + (dest - start) * easeOutCubic(progress)
    if (progress < 1) {
      requestAnimationFrame(step)
    } else {
      cleanup()
    }
  }
  requestAnimationFrame(step)
}

/**
 * 阅读快照：正文位置按文档记，大纲位置按文档记，目录树位置按知识库记 —— 三栏都是滚动区，
 * 刷新只回正文不回两侧，读者看到的就是「整页状态丢了」。
 *
 * <p>存 sessionStorage：刷新、崩溃恢复都要能回原处，而多标签页互不串位、关掉标签页随之结束。
 * 目录树按 routePath（同一个库是同一棵树）而不是按文档记：切文档时它不该跟着跳，
 * 切走再回来也不该错位。</p>
 */
const STORE_KEY = 'md-scroll-positions'
const TREE_STORE_KEY = 'md-tree-positions'
/** 条数上限：按最近访问淘汰，读几百篇也不会把存储撑大 */
const MAX_ENTRIES = 80

/** 左侧目录树的滚动区（各模式 / 断点下都是这个 nav 在滚） */
export const TREE_SCROLL_ATTR = 'data-md-scroll-tree'
/** 右侧大纲的滚动区：门户是大纲栏本身，分享页是栏内的卡片体，按计算样式挑真正能滚的那个 */
export const OUTLINE_SCROLL_ATTR = 'data-md-scroll-outline'

export interface DocMemo {
  main: number
  outline: number
}

function readStore<T>(storage: Storage, key: string, fallback: T): T {
  try {
    const raw = storage.getItem(key)
    if (raw) return JSON.parse(raw) as T
  } catch {
    // 存储不可用（隐私模式等）时退回纯内存，前进/后退仍然可用
  }
  return fallback
}

function writeStore(storage: Storage, key: string, value: unknown) {
  try {
    storage.setItem(key, JSON.stringify(value))
  } catch {
    // 写不进去只影响跨刷新记忆
  }
}

function prune<T>(map: Map<string, T>) {
  // Map 的迭代顺序就是插入顺序；写入时「先删再设」把最近访问排到末尾
  while (map.size > MAX_ENTRIES) {
    const oldest = map.keys().next().value
    if (oldest === undefined) break
    map.delete(oldest)
  }
}

const saved = (() => {
  const raw = readStore<Record<string, DocMemo | number>>(sessionStorage, STORE_KEY, {})
  const map = new Map<string, DocMemo>()
  for (const [path, memo] of Object.entries(raw)) {
    map.set(
      path,
      typeof memo === 'number'
        ? { main: memo, outline: 0 }
        : { main: memo?.main ?? 0, outline: memo?.outline ?? 0 },
    )
  }
  return map
})()

const treeSaved = new Map<string, number>(
  Object.entries(readStore<Record<string, number>>(sessionStorage, TREE_STORE_KEY, {})),
)

/**
 * 文档快照的键：路径 + 查询串，**剥掉 hash**。
 *
 * <p>读者点过大纲标题后，地址栏（经 {@code history.replaceState}）就多了 {@code #标题}，
 * 而刷新时路由解析出的 {@code fullPath} 也带着它 —— 用原样的 fullPath 当键，
 * 刷新那一刻就找不回点标题之前存下的位置，整份快照等于作废。锚点属于「正文落到哪」，
 * 不是「哪一篇」。</p>
 */
function docKey(fullPath: string): string {
  const i = fullPath.indexOf('#')
  return i < 0 ? fullPath : fullPath.slice(0, i)
}

/**
 * 目录树位置的分桶键。
 *
 * <p>同一个库的路由路径在首屏是地址栏里的百分号编码、应用内跳转是解码后的，
 * 直接拿 {@code route.path} 作键会变成两把钥匙：首屏存的、跳转后读不到，
 * 刷新回来目录树就停在别处。统一解码后再当键。</p>
 */
function routeKey(routePath: string): string {
  try {
    return decodeURIComponent(routePath)
  } catch {
    return routePath
  }
}

/**
 * 侧栏滚动区：候选里挑真正在滚的那个。
 *
 * <p>和 {@link scrollEl} 同一套判据（读计算样式而不是 scrollHeight）：分享页大纲是
 * 卡片体在滚、门户是大纲栏本身在滚，两层都带标记，取文档顺序里最后一个能滚的。</p>
 */
function sideScrollEl(attr: string): HTMLElement | null {
  let found: HTMLElement | null = null
  for (const el of document.querySelectorAll<HTMLElement>(`[${attr}]`)) {
    if (getComputedStyle(el).overflowY !== 'visible') found = el
  }
  return found
}

export function treeScrollEl(): HTMLElement | null {
  return sideScrollEl(TREE_SCROLL_ATTR)
}

export function outlineScrollEl(): HTMLElement | null {
  return sideScrollEl(OUTLINE_SCROLL_ATTR)
}

/** 当前各栏位置；不可见（窄屏抽屉被 display:none）或还没渲染的一栏跳过，别拿 0 覆盖旧值 */
function readRegions() {
  const now: { main?: number; outline?: number; tree?: number } = {}
  const main = scrollEl()
  if (main && main.clientHeight > 0) now.main = main.scrollTop
  const outline = outlineScrollEl()
  if (outline && outline.clientHeight > 0) now.outline = outline.scrollTop
  const tree = treeScrollEl()
  if (tree && tree.clientHeight > 0) now.tree = tree.scrollTop
  return now
}

export function rememberScroll(routePath: string, fullPath: string) {
  // 恢复进行中的 scrollTop 可能还被未加载完的内容夹着，落盘会把真实位置覆盖成半截值
  if (restoring) return
  const now = readRegions()
  if (now.main !== undefined || now.outline !== undefined) {
    const key = docKey(fullPath)
    const prev = saved.get(key) ?? { main: 0, outline: 0 }
    saved.delete(key)
    saved.set(key, { main: now.main ?? prev.main, outline: now.outline ?? prev.outline })
    prune(saved)
    writeStore(sessionStorage, STORE_KEY, Object.fromEntries(saved))
  }
  if (now.tree !== undefined) {
    const key = routeKey(routePath)
    treeSaved.delete(key)
    treeSaved.set(key, now.tree)
    prune(treeSaved)
    writeStore(sessionStorage, TREE_STORE_KEY, Object.fromEntries(treeSaved))
  }
}

/**
 * 恢复一次阅读快照：正文、大纲、目录树三栏一起。
 *
 * <p>阅读页的正文是挂载后异步拉回来的，代码高亮 / KaTeX / Mermaid / 图片随后还会继续
 * 改变内容高度，「写一次」必然被浏览器夹回 0。这里改成按时间预算逐帧钉住目标位置：
 * 滚不动就等内容撑开，滚到了又被推走就拉回来。读者一旦动手（滚轮 / 触摸 / 按键 /
 * 拖滚动条）立即让位 —— 人优先于记忆。</p>
 *
 * <p>目录树只在整页加载（刷新 / 直接打开）时恢复：应用内切文档时它该由「把当前文档
 * 滚进视野」决定，不该被上一篇记住的位置拽回去。</p>
 */
const RESTORE_BUDGET_MS = 5000
// resize 也在内：像素位置依赖布局，宽窄屏换滚动容器后旧目标值不再成立
const CANCEL_EVENTS = ['wheel', 'touchstart', 'pointerdown', 'keydown', 'resize'] as const

let restoreRun = 0
let restoring = false
let restoringTree = false
/** 恢复超时也没等到内容的那一页：正文到达后再补一次（慢网络、分享口令门） */
let pending: { routePath: string; fullPath: string; initialLoad: boolean; skipMain: boolean } | null = null
/** 让位之后读者是否真的滚过：滚过就不再补恢复，没滚过（只是在输口令）则正文到达后继续 */
let userScrolled = false

function endRestore() {
  restoring = false
  restoringTree = false
  CANCEL_EVENTS.forEach((type) => window.removeEventListener(type, cancelRestore, true))
}

export function cancelRestore() {
  restoreRun++
  endRestore()
}

/** 目录树恢复是否正在进行：正文补跳 / 自动展开要让位，别和逐帧钉住打架 */
export function isRestoringTree(): boolean {
  return restoringTree
}

/** 快照恢复是否在进行：大纲自动跟随要让位，别和逐帧钉住抢同一根滚动条 */
export function isRestoring(): boolean {
  return restoring
}

/**
 * @param skipMain 只恢复两侧栏。带 hash 刷新时正文由锚点决定（读者的显式意图），
 *   大纲与目录树仍该回到原处 —— 不能因为「有一次锚点跳转」就把整份快照丢掉。
 */
export function restoreScroll(
  routePath: string,
  fullPath: string,
  initialLoad: boolean,
  skipMain = false,
) {
  cancelRestore()
  const doc = saved.get(docKey(fullPath)) ?? { main: 0, outline: 0 }
  const treeWant = initialLoad ? (treeSaved.get(routeKey(routePath)) ?? 0) : 0
  const targets: Array<{ pick: () => HTMLElement | null; want: number }> = []
  if (!skipMain) targets.push({ pick: scrollEl, want: doc.main })
  targets.push({ pick: outlineScrollEl, want: doc.outline })
  // 目标为 0 的栏不钉：写 0 会把恢复期间读者的「展开到当前项」等动作拽回去
  if (treeWant > 0) targets.push({ pick: treeScrollEl, want: treeWant })

  if (targets.every((t) => t.want <= 0)) {
    pending = null
    targets.forEach((t) => {
      const el = t.pick()
      if (el) el.scrollTop = 0
    })
    return
  }

  const run = restoreRun
  restoring = true
  restoringTree = treeWant > 0
  pending = { routePath, fullPath, initialLoad, skipMain }
  userScrolled = false
  CANCEL_EVENTS.forEach((type) =>
    window.addEventListener(type, cancelRestore, { capture: true, passive: true }),
  )
  const deadline = performance.now() + RESTORE_BUDGET_MS
  let reached = false
  const step = () => {
    if (run !== restoreRun) return
    let all = true
    for (const { pick, want } of targets) {
      const el = pick()
      if (!el) continue
      if (Math.abs(el.scrollTop - want) > 1) el.scrollTop = want
      if (Math.abs(el.scrollTop - want) > 1) all = false
    }
    if (all) reached = true
    if (performance.now() >= deadline) {
      // 从没滚到过目标才留重试钩子；到过位置就算完成，之后的高度变化不再干预
      if (reached) pending = null
      endRestore()
      return
    }
    requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

/** 带 hash 的刷新 / 打开：正文交给锚点，大纲与目录树仍按快照恢复。 */
export function restoreSides(routePath: string, fullPath: string) {
  restoreScroll(routePath, fullPath, true, true)
}

/** 正文渲染 / 增强完成后调一次：上一轮恢复没等到内容且读者没滚过时，才重新恢复。 */
export function retryRestore(fullPath: string) {
  if (pending && docKey(pending.fullPath) === docKey(fullPath) && !userScrolled) {
    restoreScroll(pending.routePath, pending.fullPath, pending.initialLoad, pending.skipMain)
  }
}

/* ------------------------------------------------------------ 实时落盘 */

let liveRoutePath = ''
let livePath = ''
let trackBound = false
let trackTimer: number | undefined

/** 由 router.afterEach 调用：声明「当前在读哪一页」，并在首次调用时装好全局监听。 */
export function trackLiveScroll(routePath: string, fullPath: string) {
  if (livePath !== fullPath) userScrolled = false
  liveRoutePath = routePath
  livePath = fullPath
  if (trackBound) return
  trackBound = true
  // scroll 不冒泡，但捕获阶段收得到；正文、目录树、大纲三种滚动容器都能覆盖
  document.addEventListener('scroll', onLiveScroll, { capture: true, passive: true })
  // 刷新 / 关闭 / 切后台时节流中的最后一段可能还没落盘，这里兜底补写
  window.addEventListener('pagehide', flushLiveScroll)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushLiveScroll()
  })
}

/** 只认「属于阅读快照」的滚动容器，页面里别处的滚动（弹层等）不算读者在读 */
function isMemoRegion(el: HTMLElement) {
  return (
    el.id === VIEW_ID ||
    el.hasAttribute(SCROLL_ATTR) ||
    el.hasAttribute(TREE_SCROLL_ATTR) ||
    el.hasAttribute(OUTLINE_SCROLL_ATTR)
  )
}

function onLiveScroll(event: Event) {
  const target = event.target
  if (!(target instanceof HTMLElement)) return
  if (!isMemoRegion(target)) return
  if (restoring || trackTimer !== undefined) return
  userScrolled = true
  trackTimer = window.setTimeout(() => {
    trackTimer = undefined
    rememberScroll(liveRoutePath, livePath)
  }, 200)
}

function flushLiveScroll() {
  if (trackTimer !== undefined) {
    clearTimeout(trackTimer)
    trackTimer = undefined
  }
  if (livePath) rememberScroll(liveRoutePath, livePath)
}
