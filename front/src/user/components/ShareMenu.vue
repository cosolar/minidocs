<script setup lang="ts">
/**
 * 分享页顶栏里的导航菜单。
 *
 * <p>菜单项由分享者在后台从知识库目录树里挑出并排序，因此这里只负责「渲染 + 报告点击」，
 * 「点下去落到哪一篇」由 {@code ShareView} 按树算：目录项要取该目录下的第一篇文档，
 * 这个判断需要整棵树，放在这一层会逼组件再持有一份树。</p>
 *
 * <p>它被放进 {@code ShareTopBar} 的品牌右侧、与顶栏同处一行，因此自身不做 sticky、
 * 不铺满整宽：外壳是一条紧贴内容宽度的「悬浮胶囊坞」（{@code width: max-content}），
 * 项少的时候不再拖着一大片空轨道；项多超过可用宽度时才在内部横向滚动。</p>
 *
 * <p>选中态用一颗独立的渐变滑块（thumb）表达，而不是给按钮自己换底色：高亮从一项
 * 「滑」到下一项，比每颗按钮各自闪一下更像一个整体。滑块位置按激活按钮的实测
 * 几何位置摆放，字体换载、窗口缩放、菜单变化都靠 ResizeObserver 重算。</p>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from './Icon.vue'
import type { NavMenuItem } from '@/shared/api/types'

const props = defineProps<{
  items: NavMenuItem[]
  /** 当前高亮的菜单项路径；不在任何菜单项范围内时为 null */
  activePath?: string | null
}>()

const emit = defineEmits<{ pick: [item: NavMenuItem] }>()

const trackRef = ref<HTMLElement | null>(null)
/** 滑块几何位置（相对轨道内容原点）与可见性 */
const thumb = ref({ x: 0, w: 0, ready: false })

const activeIndex = computed(() =>
  props.items.findIndex((item) => item.path === props.activePath),
)

const reduceMotion =
  typeof window !== 'undefined' &&
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

/**
 * 把滑块摆到激活按钮上。
 *
 * <p>按钮元素随菜单数据异步渲染（挂载时 items 还是空的），v-for 函数 ref 在这个
 * 时序上不保证收集得到，因此直接在轨道里按索引实时查询按钮，永远拿到的是当前 DOM。</p>
 *
 * <p>轨道是可横向滚动容器，{@code getBoundingClientRect} 拿到的是随滚动变化的
 * 视口坐标，而滑块的 containing block 是不随滚动移动的 padding box，所以还要把
 * {@code scrollLeft} 加回去，换算成内容坐标。</p>
 */
function placeThumb() {
  const track = trackRef.value
  const index = activeIndex.value
  const item =
    track && index >= 0
      ? (track.querySelectorAll<HTMLElement>('.md-share__menu-item')[index] ?? null)
      : null
  if (!track || !item) {
    // 一个都不亮（读者切到了全库目录）时滑块隐去，别替读者做选择
    thumb.value = { ...thumb.value, ready: false }
    return
  }
  const trackRect = track.getBoundingClientRect()
  const itemRect = item.getBoundingClientRect()
  thumb.value = {
    x: itemRect.left - trackRect.left - track.clientLeft + track.scrollLeft,
    w: itemRect.width,
    ready: true,
  }
  // 激活项被滚到轨道外时把它带回视野（手动算，避免 scrollIntoView 顺带竖滚整页）
  const edge = 8
  const from = track.scrollLeft
  const to =
    item.offsetLeft - edge < from
      ? item.offsetLeft - edge
      : item.offsetLeft + item.offsetWidth + edge > from + track.clientWidth
        ? item.offsetLeft + item.offsetWidth + edge - track.clientWidth
        : null
  if (to !== null && to !== from) {
    track.scrollTo({ left: to, behavior: reduceMotion ? 'auto' : 'smooth' })
  }
}

let resizeObserver: ResizeObserver | undefined

onMounted(async () => {
  await nextTick()
  placeThumb()
  if (trackRef.value && typeof ResizeObserver !== 'undefined') {
    // 轨道宽度跟随内容（max-content），改名/字体换载/窗口缩放都会让它变，一次观测全覆盖
    resizeObserver = new ResizeObserver(() => placeThumb())
    resizeObserver.observe(trackRef.value)
  }
  window.addEventListener('resize', placeThumb)
  // 中文字体晚到时按钮宽度会变，等字体就绪再修一次
  document.fonts?.ready.then(() => placeThumb()).catch(() => {})
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', placeThumb)
})

watch(
  () => [props.activePath, props.items],
  async () => {
    await nextTick()
    placeThumb()
  },
)
</script>

<template>
  <nav ref="trackRef" class="md-share__menu" aria-label="导航菜单">
    <span
      class="md-share__menu-thumb"
      :class="{ 'is-ready': thumb.ready }"
      :style="{ width: `${thumb.w}px`, transform: `translateX(${thumb.x}px)` }"
      aria-hidden="true"
    ></span>
    <button
      v-for="item in items"
      :key="`${item.type}:${item.path}`"
      type="button"
      class="md-share__menu-item"
      :class="{ 'is-active': item.path === activePath }"
      :title="item.path"
      @click="emit('pick', item)"
    >
      <Icon :name="item.type === 'dir' ? 'books' : 'file'" :size="14" />
      <span>{{ item.name || item.path }}</span>
    </button>
  </nav>
</template>
