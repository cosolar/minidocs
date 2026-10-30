import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { splitRow } from './overflowSplit'

/**
 * 「一行放不下就收进『更多』」的通用测量逻辑。
 *
 * <p>为什么不靠 CSS 换行或横向滚动：这一行挤在 226px 的侧栏里。<b>换行</b>要多占一整行
 * 高度，而目录树正是这一栏里最该省高度的地方；<b>横向滚动</b>则把「有动作被藏住了」
 * 这件事彻底隐形 —— 用户看不到滚动条（滚动条通常还被刻意隐藏），只会以为按钮没了。
 * 收进「更多」是唯一能把「还有几个动作」说清楚的做法。</p>
 *
 * <p>为什么不按固定宽度硬算：图标钮在窄屏（≤900px）会从 28px 长到 34px，
 * 按钮内边距、间距也可能随断点/主题变。所以这里不猜宽度，而是让调用方额外渲染一份
 * <b>绝对定位、不可见</b>的镜像，用同一套 class 逐项量出天然宽度 —— 量到多少就是多少，
 * 将来给某个按钮加上文字也不会算错。</p>
 *
 * <p>量宽都发生在 <b>DOM 更新之后、浏览器绘制之前</b>（ResizeObserver 回调、以及
 * {@code flush: 'post'} 的 watcher），所以不会出现「先铺满、再收起来」的闪动。</p>
 *
 * <p>「怎么切」在 {@link ./overflowSplit.ts}，是不碰 DOM 的纯函数。</p>
 */

export interface OverflowRowOptions {
  /** 行容器：它的 clientWidth 就是可用宽度，需要 overflow:hidden 且不换行 */
  rowRef: Ref<HTMLElement | null>
  /**
   * 镜像容器：绝对定位、不可见，只用来量宽。
   *
   * <p><b>子元素个数必须是 {@code count() + 1}</b>：前 {@code count()} 个与行内
   * 逐项同宽，最后一个固定是「更多」按钮。</p>
   */
  mirrorRef: Ref<HTMLElement | null>
  /** 当前项数（随状态变化：进出多选、是否拿到写权限），<b>不含</b>「更多」按钮 */
  count: () => number
  /** 项间距，必须与 CSS 里的 gap 一致，否则算出来的位置会差几个像素 */
  gap: number
}

export function useOverflowRow({ rowRef, mirrorRef, count, gap }: OverflowRowOptions) {
  /**
   * 未测量前先当作「全部放得下」。
   *
   * <p>首帧由 onMounted 同步量完（那时 DOM 已插入、浏览器还没绘制），所以这个初值
   * 用户看不到。反过来若初值取 0，就会先只画一颗「更多」再弹开，反而更容易被察觉。</p>
   */
  const visibleCount = ref(Number.POSITIVE_INFINITY)
  let observer: ResizeObserver | null = null

  function measure() {
    const row = rowRef.value
    const mirror = mirrorRef.value
    if (!row || !mirror) return

    const widths = Array.from(mirror.children, (el) => (el as HTMLElement).offsetWidth)
    // 镜像的约定：前 count() 个是动作项，最后一个是「更多」按钮
    if (widths.length !== count() + 1) return

    const available = row.clientWidth
    // 抽屉未展开 / display:none 时宽度为 0，此时任何结论都是错的
    if (available <= 0) return

    // 「更多」按钮的宽度也量，不写死：窄屏（≤900px）图标钮会从 28px 长到 34px，
    // 写死就少预留 6px —— 而这一行是 justify-content:flex-end，溢出从左端裁，
    // 结果是左边那颗按钮被切掉一截。既然镜像已经在量了，顺手把它也量了。
    const moreWidth = widths[widths.length - 1]
    visibleCount.value = splitRow(widths.slice(0, -1), available, moreWidth, gap)
  }

  onMounted(() => {
    // 同步量一次：读 offsetWidth 只会触发一次强制同步布局，且发生在首次绘制之前
    measure()
    observer = new ResizeObserver(measure)
    if (rowRef.value) observer.observe(rowRef.value)
  })

  // 项数变了要重量：镜像跟着同一个 v-for 更新，得等 DOM 落地再量
  watch(count, () => void nextTick(measure), { flush: 'post' })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  /** 有动作被收进「更多」时为真 —— 调用方用它决定那颗按钮画不画 */
  const overflowing = computed(() => count() > visibleCount.value)

  return { visibleCount, overflowing, measure }
}
