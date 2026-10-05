import { onBeforeUnmount, ref } from 'vue'

/**
 * 管理端「当前是否窄屏」的单一判据。
 *
 * <p>表格在手机上即便能横向滚，也被固定的操作列挤得只剩一条缝；所以核心列表页在窄屏下
 * 换成卡片流。断点取 768px（手机 / 竖屏平板），与 admin.css 里 `.md-ad-only-sm` 那组
 * 媒体查询严格一致 —— 两边不一致就会出现「JS 认为该用卡片、CSS 还按表格排」的错位。</p>
 *
 * <p>用 matchMedia 而不是监听 resize：前者只在跨断点时触发一次，后者每次拖动都会跑。</p>
 */
export function useIsMobile(query = '(max-width: 768px)') {
  const mq = window.matchMedia(query)
  const isMobile = ref(mq.matches)
  const sync = (event: MediaQueryList | MediaQueryListEvent) => {
    isMobile.value = event.matches
  }
  mq.addEventListener('change', sync)
  onBeforeUnmount(() => mq.removeEventListener('change', sync))
  return isMobile
}
