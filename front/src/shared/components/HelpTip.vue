<script setup lang="ts">
/**
 * 说明文字的收纳位：一个「?」圆标，悬停或键盘聚焦才出气泡。
 *
 * <p>不用 {@code el-tooltip}：阅读端首屏不加载 Element Plus（规范 §3.4 的硬边界），而这段说明
 * 在门户、阅读页与后台要长得一模一样，所以气泡自己实现。走 {@code Teleport} 挂到 body 是必须的 ——
 * 后台的对话框与抽屉都自带 overflow 滚动容器，就地渲染的气泡会被裁掉。</p>
 *
 * <p>位置是量出来的（fixed），所以一滚动、一缩放就直接收起：宁可让用户再悬停一次，
 * 也不要一个飘在屏幕中部的说明。</p>
 */
import { onBeforeUnmount, ref, useId } from 'vue'

const props = withDefaults(defineProps<{
  /** 说明正文；用了默认插槽时忽略 */
  text?: string
  /** 气泡最大宽度，像素 */
  width?: number
}>(), { text: '', width: 320 })

const bubbleId = useId()
const anchor = ref<HTMLButtonElement | null>(null)
const bubble = ref<Record<string, string>>({})
const open = ref(false)

function place() {
  const el = anchor.value
  if (!el) {
    return
  }
  const rect = el.getBoundingClientRect()
  // 下限 200：视口比这更窄时（或嵌入态量到 0）不让宽度算成负数，那会得到一条没有内容的气泡
  const width = Math.max(200, Math.min(props.width, window.innerWidth - 24))
  const left = Math.max(12, Math.min(rect.left + rect.width / 2 - width / 2, window.innerWidth - width - 12))
  const below = window.innerHeight - rect.bottom
  // 下方放不下且上方更空就翻到上面；两边走不开就贴顶，靠 max-height 兜
  const flip = below < 140 && rect.top > below
  bubble.value = {
    left: `${left}px`,
    width: `${width}px`,
    ...(flip ? { bottom: `${window.innerHeight - rect.top + 6}px` } : { top: `${rect.bottom + 6}px` })
  }
}

function show() {
  place()
  open.value = true
  window.addEventListener('scroll', hide, true)
  window.addEventListener('resize', hide)
}

function hide() {
  open.value = false
  window.removeEventListener('scroll', hide, true)
  window.removeEventListener('resize', hide)
}

onBeforeUnmount(hide)
</script>

<template>
  <button
    ref="anchor"
    type="button"
    class="md-help"
    :aria-expanded="open"
    :aria-describedby="open ? bubbleId : undefined"
    aria-label="查看说明"
    @mouseenter="show"
    @mouseleave="hide"
    @focus="show"
    @blur="hide"
    @keydown.esc="hide"
    @click.prevent="open ? hide() : show()"
  >?</button>
  <Teleport to="body">
    <span v-if="open" :id="bubbleId" class="md-help-bubble" role="tooltip" :style="bubble"><slot>{{ text }}</slot></span>
  </Teleport>
</template>

<style scoped>
.md-help {
  flex-shrink: 0;
  display: inline-grid;
  place-items: center;
  width: 15px;
  height: 15px;
  margin-left: 5px;
  /* middle 把圆心对到父字体的 x-height/2，比汉字光心低约 1.5~1.8px；2px 是实测归零值。
     只在行内语境生效，flex 宿主（面板头、动作条）由 align-items 居中，这条被忽略。 */
  vertical-align: 2px;
  padding: 0;
  cursor: help;
  color: var(--md-text-3);
  border: 1px solid var(--md-border-strong);
  border-radius: 50%;
  background: transparent;
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}
.md-help:hover,
.md-help:focus-visible {
  color: var(--md-primary);
  border-color: var(--md-primary);
}
.md-help:focus-visible {
  outline: 2px solid var(--md-primary-soft);
  outline-offset: 1px;
}
</style>

<style>
/* 气泡不 scoped：它被 Teleport 到 body，落在组件的 DOM 之外 */
.md-help-bubble {
  position: fixed;
  z-index: 2600;
  max-height: 60vh;
  padding: 8px 10px;
  color: var(--md-text-2);
  border: 1px solid var(--md-border);
  border-radius: var(--md-radius-sm);
  background: var(--md-surface);
  box-shadow: var(--md-shadow);
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-line;
  pointer-events: none;
}
</style>
