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

/** 按路由 fullPath 记滚动位置。模块级即可：它只需要活过一次前进/后退，不需要跨会话。 */
const saved = new Map<string, number>()

export function rememberScroll(fullPath: string) {
  const el = scrollEl()
  if (el) saved.set(fullPath, el.scrollTop)
}

/**
 * 恢复某个路由的滚动位置，滚不动就等下一帧再试。
 *
 * <p>阅读页的正文是挂载后异步拉回来的，恢复时机比目标位置更难猜：内容还没撑开时
 * {@code scrollTop} 会被浏览器夹到 0，写一次就永久停在顶部。所以逐帧重试，
 * 直到「可滚高度」真的容纳得下目标值，或次数用尽（那时按顶部呈现，比卡在半路好读）。</p>
 */
export function restoreScroll(fullPath: string, attempts = 30) {
  const target = saved.get(fullPath) ?? 0
  if (target <= 0) {
    const el = scrollEl()
    if (el) el.scrollTop = 0
    return
  }
  let left = attempts
  const step = () => {
    const el = scrollEl()
    if (!el) return
    el.scrollTop = target
    if (Math.abs(el.scrollTop - target) <= 1 || --left <= 0) return
    requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}
