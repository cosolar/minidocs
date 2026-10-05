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

  const update = () => {
    let current = ''
    for (const id of ids.value) {
      const el = document.getElementById(id)
      if (!el) continue
      if (el.getBoundingClientRect().top - offset <= 0) {
        current = id
      } else {
        break
      }
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
    scrollers = [...new Set([viewEl(), scrollEl()].filter((el): el is HTMLElement => el !== null))]
    scrollers.forEach((el) => el.addEventListener('scroll', onScroll, { passive: true }))
    update()
  })

  onBeforeUnmount(() => scrollers.forEach((el) => el.removeEventListener('scroll', onScroll)))

  return { activeId, refresh: update }
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
