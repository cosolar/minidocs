import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { scrollEl, viewEl } from '@/shared/scrollRegion'

/**
 * 大纲联动：滚动时高亮当前所在章节。
 *
 * <p>以「元素顶边越过 offset 线」为判据，比 IntersectionObserver 更贴合长文阅读的直觉
 * （读者视线在标题下方时，该标题才应当高亮）。</p>
 *
 * <p>页面本身不滚，window 上永远收不到 scroll 事件，接错的结果就是大纲整栏不跟随、看着像功能坏了。
 * 但「谁在滚」并不固定：默认是 {@code #md-view}，分享页宽屏时是正文那一栏自己滚，而这两者由
 * CSS 媒体查询切换，JS 跟着判断等于把那段断点抄第二份。判据用的是
 * {@code getBoundingClientRect()}，与谁在滚无关，所以两个都挂上监听即可。</p>
 */
export function useScrollSpy(ids: Ref<string[]>, offset = 100) {
  const activeId = ref('')

  let ticking = false
  let scrollers: HTMLElement[] = []
  /** 缓存标题元素：正文切换后 DOM 会重建，由 {@link refresh} 重新解析 */
  let elements: HTMLElement[] = []
  /** 顶边是否随文档顺序单调递增；被收起的 {@code <details>} 里的标题会打破它，届时退回逐条扫描 */
  let sorted = true

  const resolve = () => {
    elements = ids.value
      .map((id) => document.getElementById(id))
      .filter((el): el is HTMLElement => el !== null)
    sorted = true
    let prev = Number.NEGATIVE_INFINITY
    for (const el of elements) {
      const top = el.getBoundingClientRect().top
      if (top < prev - 1) {
        sorted = false
        break
      }
      prev = top
    }
  }

  const update = () => {
    /*
     * 判定线要相对「滚动容器顶部」而不是视口顶部：阅读页的正文列从顶栏下方开始
     * （容器 top 约 108px），标题滚到列顶时仍在视口 108px 处。若继续拿视口 100px 当线，
     * 点击目录跳到某节后，高亮会停留在上一节 —— 看起来像「点 1.5 定位到了 1.4」。
     */
    const box = scrollEl()
    const origin = box?.getBoundingClientRect().top ?? 0
    const crossed = (el: HTMLElement) => el.getBoundingClientRect().top - origin - offset <= 0
    let current = ''
    if (sorted) {
      /*
       * 标题按文档顺序排列，顶边也随之递增：二分找「最后一条越过判定线」的标题即可。
       * 长文动辄几百个标题，逐条读 rect 会让每一帧滚动都排一次几百次的布局查询。
       */
      let lo = 0
      let hi = elements.length
      while (lo < hi) {
        const mid = (lo + hi) >> 1
        if (crossed(elements[mid])) lo = mid + 1
        else hi = mid
      }
      current = elements[lo - 1]?.id || ''
    } else {
      for (const el of elements) {
        if (crossed(el)) current = el.id
        else break
      }
    }
    /*
     * 文末的最后一节没有「下文」可以继续滚，它的顶边永远越不过判定线：
     * 滚到底、或点最后一条大纲时，高亮会一直卡在上一节，看着像「这一条点不动」。
     * 真正滚到底就把最后一节认作当前章节 —— 那时读者眼前的内容正是它。
     */
    if (
      box &&
      box.scrollHeight > box.clientHeight + 2 &&
      box.scrollHeight - box.clientHeight - box.scrollTop <= 2 &&
      elements.length
    ) {
      current = elements[elements.length - 1].id
    }
    activeId.value = current || ids.value[0] || ''
  }

  const onScroll = () => {
    if (ticking) return
    ticking = true
    requestAnimationFrame(() => {
      update()
      ticking = false
    })
  }

  onMounted(() => {
    resolve()
    scrollers = [...new Set([viewEl(), scrollEl()].filter((el): el is HTMLElement => el !== null))]
    scrollers.forEach((el) => el.addEventListener('scroll', onScroll, { passive: true }))
    update()
  })

  onBeforeUnmount(() => scrollers.forEach((el) => el.removeEventListener('scroll', onScroll)))

  /** 正文渲染 / 增强后调用：DOM 可能已重建，先重新解析标题元素再重算高亮 */
  const refresh = () => {
    resolve()
    update()
  }

  return { activeId, refresh }
}

/** 把后端返回的大纲树按文档顺序拍平，便于做滚动联动 */
export function flattenOutline<T extends { id: string; children?: T[] }>(nodes: T[]): T[] {
  const list: T[] = []
  const walk = (items: T[]) => {
    items.forEach((item) => {
      list.push(item)
      if (item.children?.length) walk(item.children)
    })
  }
  walk(nodes)
  return list
}
