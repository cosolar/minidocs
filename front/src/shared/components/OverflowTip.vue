<script setup lang="ts">
/**
 * 截断文本的完整内容气泡。
 *
 * <p>浏览器原生的 {@code title} 提示框样式由系统决定、延迟长、移动端根本不出现；
 * 卡片里被省略号截掉的标题与描述需要一处稳定可读的补全。这里只在文字真的溢出时挂气泡，
 * 没截断的卡片悬停不会弹一句重复的话。</p>
 *
 * <p>布局与卡片菜单同一个约束：卡片为了裁圆角自带 {@code overflow: hidden}，就地渲染的
 * 气泡会被裁掉，所以 Teleport 到 body、用 fixed 坐标。位置按触发的实际矩形居中，
 * 上方放不下就翻到下方，左右贴边时把箭头跟过去，避免箭头脱落。</p>
 *
 * <p>触屏没有悬停：轻点这段文字（手指没有明显移动）也能看全文，点别处或滚动即收起。
 * 标题本身是链接，触屏轻点应当跳转而不是弹气泡，调用方传 {@code :touch="false"}。</p>
 */
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

const props = withDefaults(defineProps<{
  /** 完整文本；用了默认插槽时仍以本属性作为气泡内容 */
  text?: string
  /** 触发元素标签，例如 h3 / p / span */
  as?: string
  /** 气泡最大宽度，像素 */
  maxWidth?: number
  /** 触屏轻点是否弹出气泡 */
  touch?: boolean
}>(), { text: '', as: 'span', maxWidth: 360, touch: true })

// 根元素与气泡是两个节点：class / id 等外部属性必须手动落到触发元素上
defineOptions({ inheritAttrs: false })

const tipId = useId()
const rootRef = ref<HTMLElement | null>(null)
const tipRef = ref<HTMLElement | null>(null)
const cut = ref(false)
const hovering = ref(false)
const focused = ref(false)
const tapped = ref(false)
const open = computed(() => cut.value && (hovering.value || focused.value || tapped.value))

const placed = ref(false)
const placement = ref<'top' | 'bottom'>('top')
const tipStyle = ref<Record<string, string>>({})

/*
 * 用文本自身的排版矩形判断是否被裁：-webkit-line-clamp 是「裁剪」而不是「可滚动溢出」，
 * 单行省略时 scrollWidth 常常等于 clientWidth，量不出来。Range 反映文字实际占了多少。
 * 卡片宽度会随「网格 / 列表」切换而变，交给 ResizeObserver 重算。
 */
function measure() {
  const el = rootRef.value
  if (!el) {
    cut.value = false
    return
  }
  const range = document.createRange()
  range.selectNodeContents(el)
  const rect = range.getBoundingClientRect()
  cut.value = rect.width > el.clientWidth + 1 || rect.height > el.clientHeight + 1
}

let observer: ResizeObserver | null = null
function attach() {
  measure()
  if (typeof ResizeObserver !== 'undefined' && rootRef.value) {
    observer = new ResizeObserver(measure)
    observer.observe(rootRef.value)
  }
}
watch(rootRef, (el) => {
  if (!el) {
    return
  }
  attach()
})
watch(() => props.text, () => nextTick(measure), { flush: 'post' })

function place() {
  const el = rootRef.value
  const tip = tipRef.value
  if (!el || !tip) {
    return
  }
  const rect = el.getBoundingClientRect()
  const vw = window.innerWidth
  const vh = window.innerHeight
  const width = tip.offsetWidth
  const height = tip.offsetHeight
  const gap = 9

  let top = rect.top - height - gap
  placement.value = 'top'
  if (top < 8) {
    placement.value = 'bottom'
    top = rect.bottom + gap
  }
  top = Math.max(8, Math.min(top, vh - height - 8))
  const left = Math.max(8, Math.min(rect.left + rect.width / 2 - width / 2, vw - width - 8))
  // 箭头钉在触发元素中心，但不能贴到气泡圆角外
  const arrowX = Math.max(14, Math.min(rect.left + rect.width / 2 - left, width - 14))
  tipStyle.value = {
    top: `${top}px`,
    left: `${left}px`,
    maxWidth: `min(${props.maxWidth}px, calc(100vw - 24px))`,
    '--tip-arrow-x': `${arrowX}px`
  }
  placed.value = true
}

let closeTimer: number | undefined

/** 移出触发文字后留一点宽限：气泡在文字上方，跨过那条缝时不该立刻消失 */
function scheduleHide() {
  window.clearTimeout(closeTimer)
  closeTimer = window.setTimeout(hide, 140)
}

function cancelHide() {
  window.clearTimeout(closeTimer)
}

function hide() {
  window.clearTimeout(closeTimer)
  hovering.value = false
  focused.value = false
  tapped.value = false
}

/** 气泡自己有滚动条时，滚它的内容不算「页面滚走了」 */
function onScroll(event: Event) {
  const target = event.target as Node | null
  if (target && tipRef.value?.contains(target)) {
    return
  }
  hide()
}

function onDocPointerDown(event: PointerEvent) {
  const target = event.target as Node | null
  if (target && (rootRef.value?.contains(target) || tipRef.value?.contains(target))) {
    return
  }
  tapped.value = false
}

watch(open, async (value) => {
  if (!value) {
    placed.value = false
    window.clearTimeout(closeTimer)
    window.removeEventListener('scroll', onScroll, true)
    window.removeEventListener('resize', hide)
    document.removeEventListener('pointerdown', onDocPointerDown, true)
    return
  }
  placed.value = false
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', hide)
  document.addEventListener('pointerdown', onDocPointerDown, true)
  await nextTick()
  place()
})

let touchStart: { x: number; y: number } | null = null
function onPointerDown(event: PointerEvent) {
  if (event.pointerType !== 'touch' || !props.touch) {
    return
  }
  touchStart = { x: event.clientX, y: event.clientY }
}
function onPointerUp(event: PointerEvent) {
  if (event.pointerType !== 'touch' || !props.touch || !touchStart) {
    return
  }
  const moved = Math.abs(event.clientX - touchStart.x) + Math.abs(event.clientY - touchStart.y)
  touchStart = null
  if (moved < 8 && cut.value) {
    tapped.value = !tapped.value
  }
}

onBeforeUnmount(() => {
  observer?.disconnect()
  window.clearTimeout(closeTimer)
  window.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', hide)
  document.removeEventListener('pointerdown', onDocPointerDown, true)
})
</script>

<template>
  <component
    :is="as"
    ref="rootRef"
    v-bind="$attrs"
    @mouseenter="cancelHide(); hovering = true"
    @mouseleave="scheduleHide"
    @focusin="focused = true"
    @focusout="focused = false"
    @pointerdown="onPointerDown"
    @pointerup="onPointerUp"
  >
    <slot>{{ text }}</slot>
  </component>
  <Teleport to="body">
    <Transition name="md-tip">
      <div
        v-if="open"
        :id="tipId"
        ref="tipRef"
        class="md-overflow-tip"
        :class="`is-${placement}`"
        :style="[tipStyle, { visibility: placed ? 'visible' : 'hidden' }]"
        role="tooltip"
        @mouseenter="cancelHide"
        @mouseleave="scheduleHide"
      >
        <div class="md-overflow-tip__body">{{ text }}</div>
        <i class="md-overflow-tip__arrow" aria-hidden="true" />
      </div>
    </Transition>
  </Teleport>
</template>

<style>
/*
 * 气泡被 Teleport 到 body，组件 scoped 样式覆盖不到它，所以这段不 scoped。
 * 颜色全部走主题令牌：浅色是一张带薄荷边的纸，暗色自动换成深色卡片。
 */
.md-overflow-tip {
  position: fixed;
  z-index: 80;
  /* 默认宽度约束必须在首次渲染就生效：量高度发生在内联样式之前，
     没有它时文字铺成一行、把气泡算矮，定位就会压住触发文字 */
  max-width: min(360px, calc(100vw - 24px));
  color: var(--md-text);
  border: 1px solid color-mix(in srgb, var(--md-primary) 24%, var(--md-border));
  border-radius: 10px;
  background: color-mix(in srgb, var(--md-surface) 94%, var(--md-primary) 6%);
  box-shadow:
    0 2px 6px color-mix(in srgb, var(--md-text) 8%, transparent),
    0 14px 32px -12px color-mix(in srgb, var(--md-text) 34%, transparent);
  font-size: 12.5px;
  font-weight: 400;
  line-height: 1.7;
  letter-spacing: .01em;
  text-align: left;
  /* 允许把鼠标移进气泡：长文本要在里面滚动，离开触发文字就消失会滚不到底 */
  pointer-events: auto;
}
/*
 * 只有正文是滚动容器：箭头绝对定位在它外面。
 * 箭头若和文字同处一个 overflow:auto 的盒子里，bottom:-5px 会撑出几个像素的
 * 可滚动高度，短文本也会冒出一条没有意义的滚动条。
 * 超过 320px（窄屏 50vh）才滚动，普通的一两行描述不出现滚动条。
 */
.md-overflow-tip__body {
  max-height: min(320px, 50vh);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 9px 12px 10px;
  border-radius: inherit;
  white-space: pre-line;
  overflow-wrap: anywhere;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--md-text-3) 45%, transparent) transparent;
}
.md-overflow-tip__body::-webkit-scrollbar { width: 6px; }
.md-overflow-tip__body::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: color-mix(in srgb, var(--md-text-3) 45%, transparent);
}
.md-overflow-tip__arrow {
  position: absolute;
  width: 8px;
  height: 8px;
  border: 1px solid color-mix(in srgb, var(--md-primary) 24%, var(--md-border));
  background: color-mix(in srgb, var(--md-surface) 94%, var(--md-primary) 6%);
  transform: rotate(45deg);
}
.md-overflow-tip.is-top .md-overflow-tip__arrow {
  left: calc(var(--tip-arrow-x, 50%) - 4px);
  bottom: -5px;
  border-top-color: transparent;
  border-left-color: transparent;
}
.md-overflow-tip.is-bottom .md-overflow-tip__arrow {
  left: calc(var(--tip-arrow-x, 50%) - 4px);
  top: -5px;
  border-right-color: transparent;
  border-bottom-color: transparent;
}

.md-tip-enter-active,
.md-tip-leave-active {
  transition: opacity .14s ease, transform .14s ease;
}
.md-tip-enter-from,
.md-tip-leave-to {
  opacity: 0;
  transform: translateY(3px) scale(.985);
}
@media (prefers-reduced-motion: reduce) {
  .md-tip-enter-active,
  .md-tip-leave-active { transition: none; }
}
</style>
