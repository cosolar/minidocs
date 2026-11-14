<script setup lang="ts">
/**
 * 登录滑块验证码。
 *
 * <p>交互：按住右侧滑块横向拖动，拼图块跟着走；松手即提交轨迹，后端比对缺口位置
 * 与滑动轨迹是否符合人手特征。通过后换一张一次性票据，由父组件随登录请求带上。</p>
 *
 * <p>为什么自己做 UI 而不用库的前端 SDK：SDK 的样式是它自己的视觉体系，
 * 塞进青绿主题的登录卡片里会很违和；而协议只有「取图 / 交轨迹」两个接口，
 * 自己画一层反而更容易和站点对齐。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import MdIcon from './MdIcon.vue'
import { captchaApi } from '@/admin/api'
import { toApiErrorInfo } from '@/shared/api/http'
import type { SliderCaptchaVO } from '@/shared/api/types'

const emit = defineEmits<{ (e: 'passed', ticket: string): void }>()

type Phase = 'loading' | 'idle' | 'dragging' | 'checking' | 'passed' | 'error'

/** 滑块宽度与内边距，界面上和样式表里的数值必须一致，否则拖动范围的换算会差几个像素。 */
const HANDLE_W = 44
const HANDLE_INSET = 4

const phase = ref<Phase>('loading')
const data = ref<SliderCaptchaVO | null>(null)
const offset = ref(0)
const errorText = ref('')
const trackWidth = ref(320)
const trackEl = ref<HTMLElement | null>(null)

let observer: ResizeObserver | null = null
let resetTimer: number | null = null

/** 显示宽 / 原图宽：轨迹要按原图坐标提交，界面按缩放后的像素绘制。 */
const scale = computed(() => {
  const natural = data.value?.backgroundWidth || 600
  return trackWidth.value / natural
})
const panelHeight = computed(() => Math.round((data.value?.backgroundHeight || 360) * scale.value))
const pieceWidth = computed(() => Math.round((data.value?.templateWidth || 60) * scale.value))
const pieceTop = computed(() => {
  const y = Number((data.value?.extra as { y?: number } | null | undefined)?.y ?? 0)
  return Math.round(y * scale.value)
})
/** 可拖动的最大位移：拼图块右缘正好贴住图片右缘。 */
const maxOffset = computed(() => Math.max(0, trackWidth.value - pieceWidth.value - HANDLE_INSET))

const panelVisible = computed(() => phase.value === 'dragging' || phase.value === 'checking')

const hint = computed(() => {
  switch (phase.value) {
    case 'loading': return '正在准备验证…'
    case 'checking': return '正在核对轨迹…'
    case 'passed': return '验证通过'
    case 'error': return errorText.value
    case 'dragging': return '松手完成验证'
    default: return '拖动滑块完成拼图'
  }
})

const handleIcon = computed(() => (phase.value === 'passed' ? 'check' : 'chevron-right'))

async function load() {
  phase.value = 'loading'
  offset.value = 0
  errorText.value = ''
  try {
    data.value = await captchaApi.slider()
    phase.value = 'idle'
  } catch (error) {
    phase.value = 'error'
    errorText.value = toApiErrorInfo(error).message || '验证码加载失败'
  }
}

let startX = 0
let startOffset = 0
let startedAt = 0
let dragging = false
const points: { x: number; y: number; t: number; type: string }[] = []

function onDown(event: PointerEvent) {
  if (phase.value !== 'idle' && phase.value !== 'error') {
    return
  }
  if (phase.value === 'error') {
    // 上一张图已经作废（票据消费或轨迹不符），点一下重新取
    load()
    return
  }
  event.preventDefault()
  dragging = true
  phase.value = 'dragging'
  startX = event.clientX
  startOffset = offset.value
  startedAt = Date.now()
  points.length = 0
  points.push({ x: round(offset.value / scale.value), y: pieceTop.value / scale.value, t: 0, type: 'down' })
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onMove(event: PointerEvent) {
  if (!dragging) {
    return
  }
  const next = clamp(startOffset + (event.clientX - startX), 0, maxOffset.value)
  offset.value = next
  points.push({
    x: round(next / scale.value),
    y: round(pieceTop.value / scale.value),
    t: Date.now() - startedAt,
    type: 'move'
  })
}

async function onUp() {
  if (!dragging) {
    return
  }
  dragging = false
  if (!data.value) {
    return
  }
  points.push({
    x: round(offset.value / scale.value),
    y: round(pieceTop.value / scale.value),
    t: Date.now() - startedAt,
    type: 'up'
  })
  phase.value = 'checking'
  try {
    const result = await captchaApi.check({
      id: data.value.id,
      data: {
        bgImageWidth: data.value.backgroundWidth,
        bgImageHeight: data.value.backgroundHeight,
        templateImageWidth: data.value.templateWidth,
        templateImageHeight: data.value.templateHeight,
        startTime: startedAt,
        stopTime: Date.now(),
        trackList: points
      }
    })
    phase.value = 'passed'
    // 通过后把滑块推到最右：一来不压住居中的「验证通过」，二来「推到底 = 完成」
    // 比停在半路更符合直觉
    offset.value = maxOffset.value
    emit('passed', result.ticket)
  } catch (error) {
    phase.value = 'error'
    errorText.value = toApiErrorInfo(error).message || '拼图没对上，再来一次'
    // 图已失效，停一下让人看清提示，再换一张
    resetTimer = window.setTimeout(() => load(), 1200)
  }
}

/** 父组件在登录失败后调用：票据已被消费，必须重新滑一次。 */
function reset() {
  if (resetTimer) {
    window.clearTimeout(resetTimer)
    resetTimer = null
  }
  startOffset = 0
  load()
}

defineExpose({ reset })

onMounted(() => {
  // 兜底：万一 setPointerCapture 没生效（合成事件、个别触屏浏览器），
  // 松手会落到遮罩上，handle 收不到 pointerup —— 拖到一半卡住比验证失败更让人困惑
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
  if (trackEl.value) {
    observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width
      if (width && width > 0) {
        trackWidth.value = width
      }
    })
    observer.observe(trackEl.value)
  }
  load()
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerup', onUp)
  window.removeEventListener('pointercancel', onUp)
  observer?.disconnect()
  if (resetTimer) {
    window.clearTimeout(resetTimer)
  }
})

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), Math.max(min, max))
}

function round(value: number) {
  return Math.round(value * 100) / 100
}
</script>

<template>
  <div class="md-slider" :class="`is-${phase}`">
    <!--
      拼图面板走 Teleport + 居中悬浮：登录卡片右栏只有四百来像素宽，
      面板若贴着滑块往上弹，会正好盖住邮箱与密码两个输入框和标题，看起来很挤。
      居中悬浮一层半透明遮罩，视觉焦点干净，图片也能给到足够大的尺寸。
    -->
    <Teleport to="body">
      <transition name="md-slider-pop">
        <div v-if="panelVisible && data" class="md-slider__mask">
          <div class="md-slider__panel" :style="{ width: trackWidth + 'px' }">
            <div class="md-slider__canvas" :style="{ height: panelHeight + 'px' }">
              <img class="md-slider__bg" :src="data.backgroundImage" alt="" draggable="false" />
              <img
                class="md-slider__piece"
                :src="data.templateImage"
                alt=""
                draggable="false"
                :style="{ width: pieceWidth + 'px', transform: `translate3d(${offset}px, ${pieceTop}px, 0)` }"
              />
            </div>
            <p class="md-slider__tip">把拼图块拖到缺口处</p>
          </div>
        </div>
      </transition>
    </Teleport>

    <div ref="trackEl" class="md-slider__track">
      <div class="md-slider__fill" :style="{ width: offset + HANDLE_W + HANDLE_INSET + 'px' }" />
      <span class="md-slider__hint">{{ hint }}</span>
      <span
        class="md-slider__handle"
        :style="{ transform: `translate3d(${offset}px, 0, 0)` }"
        role="slider"
        :aria-disabled="phase === 'checking' || phase === 'passed'"
        :aria-valuenow="Math.round(offset)"
        aria-valuemin="0"
        :aria-valuemax="Math.round(maxOffset)"
        aria-label="拖动完成滑块验证"
        @pointerdown="onDown"
        @pointermove="onMove"
        @pointerup="onUp"
        @pointercancel="onUp"
      >
        <MdIcon :name="handleIcon" :size="16" />
      </span>
    </div>
  </div>
</template>

<style scoped>
.md-slider {
  position: relative;
  /* 限宽并与表单居中对齐：宽度决定拼图面板的尺寸，太宽会让面板在窄栏里显得笨重 */
  width: min(100%, 340px);
  margin: 0 auto;
  user-select: none;
}

.md-slider__track {
  position: relative;
  display: flex;
  align-items: center;
  height: 44px;
  padding: 0 var(--md-slider-inset, 4px);
  box-sizing: border-box;
  border: 1px solid var(--md-border);
  border-radius: 12px;
  background: color-mix(in srgb, var(--md-primary) 5%, var(--md-surface-2));
  overflow: hidden;
  transition: border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
}

/* 拖动时从左往右渗开的一层薄荷色，做「进度」的暗示而不是读秒 */
.md-slider__fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: 12px;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--md-primary) 22%, transparent),
    color-mix(in srgb, var(--md-primary) 6%, transparent)
  );
  transition: width 0.06s linear;
  pointer-events: none;
}

.md-slider__hint {
  position: relative;
  flex: 1;
  text-align: center;
  font-size: 13px;
  line-height: 1;
  letter-spacing: 0.01em;
  color: var(--md-text-3);
  transition: color 0.18s ease, opacity 0.18s ease;
}

.md-slider__handle {
  position: absolute;
  top: 4px;
  left: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 36px;
  border-radius: 9px;
  background: #fff;
  color: var(--md-primary);
  box-shadow: 0 1px 2px rgba(24, 74, 66, 0.12), 0 4px 10px -4px rgba(24, 74, 66, 0.28);
  cursor: grab;
  touch-action: none;
  transition: box-shadow 0.18s ease, transform 0.06s linear, background 0.18s ease, color 0.18s ease;
}

.md-slider__handle:hover {
  box-shadow: 0 2px 4px rgba(24, 74, 66, 0.14), 0 8px 18px -6px rgba(24, 74, 66, 0.34);
}

.md-slider__handle:active {
  cursor: grabbing;
  box-shadow: 0 1px 2px rgba(24, 74, 66, 0.18), 0 6px 14px -6px rgba(24, 74, 66, 0.34);
}

/* ---------------- 拼图面板（Teleport 到 body，居中悬浮） ---------------- */
.md-slider__mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: color-mix(in srgb, #0f2f29 9%, transparent);
  backdrop-filter: blur(3px) saturate(1.06);
}

.md-slider__panel {
  border-radius: 18px;
  overflow: hidden;
  background: var(--md-surface);
  box-shadow: 0 30px 64px -26px rgba(18, 58, 52, 0.55), 0 6px 18px rgba(18, 58, 52, 0.16);
  transition: transform 0.2s ease;
}

.md-slider__canvas {
  position: relative;
  width: 100%;
  overflow: hidden;
}

.md-slider__bg {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  pointer-events: none;
}

/* 拼图块：加投影让它从底图上「浮起来」，缺口边缘才看得清 */
.md-slider__piece {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
  filter: drop-shadow(0 3px 6px rgba(18, 58, 52, 0.42));
  will-change: transform;
}

.md-slider__tip {
  margin: 0;
  padding: 10px 14px 12px;
  font-size: 12px;
  line-height: 1;
  color: var(--md-text-3);
  text-align: center;
}

.md-slider-pop-enter-active,
.md-slider-pop-leave-active {
  transition: opacity 0.2s ease;
}

.md-slider-pop-enter-from,
.md-slider-pop-leave-to {
  opacity: 0;
}

.md-slider-pop-enter-from .md-slider__panel,
.md-slider-pop-leave-to .md-slider__panel {
  transform: scale(0.96);
}

/* ---------------- 状态 ---------------- */
.md-slider.is-dragging .md-slider__track,
.md-slider.is-checking .md-slider__track {
  border-color: color-mix(in srgb, var(--md-primary) 45%, var(--md-border));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--md-primary) 12%, transparent);
}

.md-slider.is-dragging .md-slider__hint,
.md-slider.is-checking .md-slider__hint {
  color: var(--md-primary);
}

.md-slider.is-passed .md-slider__track {
  border-color: color-mix(in srgb, var(--md-success) 42%, var(--md-border));
  background: color-mix(in srgb, var(--md-success) 10%, var(--md-surface-2));
}

.md-slider.is-passed .md-slider__fill {
  background: color-mix(in srgb, var(--md-success) 18%, transparent);
}

.md-slider.is-passed .md-slider__hint {
  color: var(--md-success);
  font-weight: 600;
}

.md-slider.is-passed .md-slider__handle {
  background: var(--md-success);
  color: #fff;
  cursor: default;
  /* 通过后滑块要「滑到右端」，这一段用长一点的缓动，别像瞬移 */
  transition: transform 0.26s cubic-bezier(0.22, 0.8, 0.24, 1), background 0.2s ease, color 0.2s ease;
}

.md-slider.is-error .md-slider__track {
  border-color: color-mix(in srgb, var(--md-danger) 48%, var(--md-border));
  animation: md-slider-shake 0.42s ease;
}

.md-slider.is-error .md-slider__hint {
  color: var(--md-danger);
}

.md-slider.is-error .md-slider__handle {
  color: var(--md-danger);
}

.md-slider.is-checking .md-slider__handle {
  cursor: progress;
}

@keyframes md-slider-shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-5px); }
  40% { transform: translateX(4px); }
  60% { transform: translateX(-3px); }
  80% { transform: translateX(2px); }
}

@media (prefers-reduced-motion: reduce) {
  .md-slider.is-error .md-slider__track {
    animation: none;
  }
  .md-slider-pop-enter-active,
  .md-slider-pop-leave-active,
  .md-slider__panel {
    transition: none;
  }
}

:global(body[data-theme='dark']) .md-slider__handle,
:global(html.dark) .md-slider__handle {
  background: color-mix(in srgb, var(--md-surface) 92%, #fff);
}
</style>
