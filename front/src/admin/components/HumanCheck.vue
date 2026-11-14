<script setup lang="ts">
/**
 * 登录人机验证：按提示依次点选三个图形。
 *
 * <p>交互在一个卡片内完成：默认只占一行「点击完成人机验证」，点开后原地向下展开
 * 一张水彩图，不再像滑块那样在屏幕中央浮一层遮罩 —— 登录卡片只有四百来像素宽，
 * 浮层无论怎么放都会盖住输入框。</p>
 *
 * <p>几个刻意的取舍：</p>
 * <ul>
 *   <li><b>答案不下发</b>：接口只给「要点哪三个形状、什么颜色」，坐标留在服务端，
 *       每点一下提交一次由后端判定。前端若拿得到坐标，脚本读一下就能直接点中；</li>
 *   <li><b>点错即换图</b>：后端点错就作废这张图，前端在 1 秒后自动换一张，
 *       与滑块「滑歪重来」的规则一致；</li>
 *   <li><b>通过后收成一行</b>：不再是一整条绿色横幅，只留一句「已完成人机验证」，
 *       不至于在登录按钮上方抢走注意力。</li>
 * </ul>
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import MdIcon from './MdIcon.vue'
import { captchaApi } from '@/admin/api'
import { toApiErrorInfo } from '@/shared/api/http'
import type { CaptchaChallengeVO } from '@/shared/api/types'

const emit = defineEmits<{ (e: 'passed', ticket: string): void }>()

type Phase = 'collapsed' | 'open' | 'passed' | 'error'

/** 与后端 GraphCaptchaArt.Shape 一一对应，24x24 视框里画满。 */
const SHAPE_PATHS: Record<string, string> = {
  circle: 'M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18Z',
  square: 'M7 4.5h10A2.5 2.5 0 0 1 19.5 7v10a2.5 2.5 0 0 1-2.5 2.5H7A2.5 2.5 0 0 1 4.5 17V7A2.5 2.5 0 0 1 7 4.5Z',
  triangle: 'M12 3.6 21.4 20H2.6Z',
  diamond: 'M12 3 21 12l-9 9-9-9Z',
  star: 'M12 3 14.41 8.68 20.56 9.22 15.9 13.27 17.29 19.28 12 16.1 6.71 19.28 8.1 13.27 3.44 9.22 9.59 8.68Z',
  hexagon: 'M12 3.5 19.36 7.75v8.5L12 20.5 4.64 16.25v-8.5Z'
}

const phase = ref<Phase>('collapsed')
const data = ref<CaptchaChallengeVO | null>(null)
const progress = ref(0)
const errorText = ref('')
const loaded = ref(false)
const busy = ref(false)
/** 已点中的图形位置，画成带勾的选中环。 */
const hits = ref<{ x: number; y: number; key: number }[]>([])
/** 点击处的涟漪，只是手感反馈，不承载信息。 */
const ripple = ref<{ x: number; y: number; key: number } | null>(null)
const canvasEl = ref<HTMLElement | null>(null)

let reloadTimer: number | null = null
let rippleTimer: number | null = null

const promptText = computed(() => {
  if (phase.value === 'error') {
    return errorText.value
  }
  return busy.value ? '核对中…' : '请依次点击'
})

function shapePath(shape: string) {
  return SHAPE_PATHS[shape] || SHAPE_PATHS.circle
}

/** 原图坐标转百分比：图片按容器宽度缩放，用百分比定位就不必关心实际像素。 */
function percent(value: number, total: number) {
  return `${(value / total) * 100}%`
}

function open() {
  phase.value = 'open'
  void load()
}

async function load() {
  phase.value = 'open'
  errorText.value = ''
  progress.value = 0
  hits.value = []
  ripple.value = null
  loaded.value = false
  try {
    data.value = await captchaApi.challenge()
  } catch (error) {
    phase.value = 'error'
    errorText.value = toApiErrorInfo(error).message || '验证图加载失败，点右上角换一张'
  }
}

async function onPick(event: MouseEvent) {
  if (phase.value !== 'open' || !data.value || busy.value) {
    return
  }
  const image = canvasEl.value?.querySelector('img')
  if (!image) {
    return
  }
  const rect = image.getBoundingClientRect()
  if (rect.width <= 0) {
    return
  }
  const shownX = event.clientX - rect.left
  const shownY = event.clientY - rect.top

  ripple.value = { x: shownX, y: shownY, key: Date.now() }
  if (rippleTimer) {
    window.clearTimeout(rippleTimer)
  }
  rippleTimer = window.setTimeout(() => {
    ripple.value = null
    rippleTimer = null
  }, 460)

  // 提交前换算回原图坐标：后端只在 600x250 这套坐标系里判定
  const scale = data.value.width / rect.width
  busy.value = true
  try {
    const result = await captchaApi.pick({
      id: data.value.id,
      x: Math.round(shownX * scale),
      y: Math.round(shownY * scale)
    })
    progress.value = result.progress
    if (result.hit) {
      hits.value = [...hits.value, { x: result.hit.x, y: result.hit.y, key: Date.now() }]
    }
    if (result.ticket) {
      phase.value = 'passed'
      emit('passed', result.ticket.ticket)
    }
  } catch (error) {
    phase.value = 'error'
    errorText.value = toApiErrorInfo(error).message || '没点中，换一张再来'
    // 这张图已经作废，停一下让人看清提示再换，否则图一闪而过像是点了没反应
    reloadTimer = window.setTimeout(() => {
      reloadTimer = null
      void load()
    }, 1100)
  } finally {
    busy.value = false
  }
}

/**
 * 父组件在登录失败后调用：票据已被消费，必须重新验证一次。
 *
 * <p>这里不收回折叠态 —— 用户刚点完三下，再让他「点开 → 点三下」等于把成本翻倍。
 * 保持展开、换一张图，接着点就是。</p>
 */
function reset() {
  if (reloadTimer) {
    window.clearTimeout(reloadTimer)
    reloadTimer = null
  }
  if (phase.value === 'collapsed') {
    return
  }
  progress.value = 0
  hits.value = []
  ripple.value = null
  void load()
}

defineExpose({ reset })

onBeforeUnmount(() => {
  if (reloadTimer) {
    window.clearTimeout(reloadTimer)
  }
  if (rippleTimer) {
    window.clearTimeout(rippleTimer)
  }
})
</script>

<template>
  <div class="md-check" :class="`is-${phase}`">
    <!-- 收起态：和输入框同一视觉重量的一行，点开才开始验证 -->
    <button v-if="phase === 'collapsed'" class="md-check__enter" type="button" @click="open">
      <span class="md-check__enter-icon"><MdIcon name="lock" :size="14" /></span>
      <span class="md-check__enter-text">点击完成人机验证</span>
      <span class="md-check__enter-go">开始<MdIcon name="chevron-right" :size="13" /></span>
    </button>

    <transition v-else name="md-check-swap" mode="out-in">
      <div v-if="phase === 'passed'" key="done" class="md-check__done">
        <span class="md-check__done-icon"><MdIcon name="check" :size="12" /></span>
        <span>已完成人机验证</span>
      </div>

      <div v-else key="stage" class="md-check__stage" role="group" aria-label="人机验证">
        <div class="md-check__prompt">
          <span class="md-check__prompt-text">{{ promptText }}</span>
          <span class="md-check__steps">
            <span
              v-for="(target, index) in data?.targets || []"
              :key="index"
              class="md-check__step"
              :class="{ 'is-done': index < progress, 'is-next': index === progress }"
            >
              <!-- 点过的直接换成勾：只变灰看不出「已经完成」，勾是唯一不会有歧义的信号 -->
              <MdIcon v-if="index < progress" name="check" :size="13" />
              <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                <path :d="shapePath(target.shape)" :fill="target.color" />
              </svg>
            </span>
          </span>
          <button class="md-check__refresh" type="button" title="换一张" aria-label="换一张验证图" @click.stop="load">
            <MdIcon name="refresh" :size="13" />
          </button>
        </div>

        <div ref="canvasEl" class="md-check__canvas" :class="{ 'is-ready': loaded }">
          <img
            v-if="data"
            :src="data.image"
            alt="人机验证图"
            draggable="false"
            @load="loaded = true"
            @click="onPick"
          />
          <span v-if="!loaded" class="md-check__loading">正在准备验证…</span>
          <span
            v-for="hit in hits"
            :key="hit.key"
            class="md-check__hit"
            :style="{ left: percent(hit.x, data?.width || 600), top: percent(hit.y, data?.height || 250) }"
          >
            <MdIcon name="check" :size="15" />
          </span>
          <span
            v-if="ripple"
            :key="`ripple-${ripple.key}`"
            class="md-check__ripple"
            :style="{ left: `${ripple.x}px`, top: `${ripple.y}px` }"
          />
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.md-check {
  width: 100%;
  user-select: none;
}

/* ---------------- 收起态 ---------------- */
.md-check__enter {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 44px;
  padding: 0 12px 0 13px;
  border: 1px solid var(--md-border);
  border-radius: 12px;
  background: color-mix(in srgb, var(--md-primary) 4%, var(--md-surface-2));
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease, color 0.18s ease;
}

.md-check__enter:hover {
  border-color: color-mix(in srgb, var(--md-primary) 38%, var(--md-border));
  background: color-mix(in srgb, var(--md-primary) 7%, var(--md-surface-2));
}

.md-check__enter-icon {
  display: inline-flex;
  color: var(--md-primary);
  opacity: 0.72;
  transition: opacity 0.18s ease;
}

.md-check__enter:hover .md-check__enter-icon {
  opacity: 1;
}

.md-check__enter-text {
  flex: 1;
  text-align: left;
  letter-spacing: 0.01em;
  color: var(--md-text-3);
}

.md-check__enter:hover .md-check__enter-text {
  color: var(--md-text-2, var(--md-text-3));
}

.md-check__enter-go {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  font-size: 12px;
  color: var(--md-primary);
  opacity: 0.85;
}

/* ---------------- 展开态 ---------------- */
.md-check__stage {
  border: 1px solid color-mix(in srgb, var(--md-primary) 22%, var(--md-border));
  border-radius: 14px;
  overflow: hidden;
  background: var(--md-surface-2);
  box-shadow: 0 10px 26px -20px rgba(18, 58, 52, 0.5);
  animation: md-check-rise 0.22s cubic-bezier(0.22, 0.8, 0.24, 1);
}

.md-check__prompt {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 7px 0 12px;
  background: color-mix(in srgb, var(--md-primary) 6%, var(--md-surface));
  border-bottom: 1px solid color-mix(in srgb, var(--md-primary) 10%, transparent);
}

.md-check__prompt-text {
  flex: 1;
  font-size: 12px;
  letter-spacing: 0.01em;
  color: var(--md-text-3);
  transition: color 0.16s ease;
}

.md-check__steps {
  display: inline-flex;
  gap: 6px;
}

.md-check__step {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--md-surface) 80%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--md-border) 75%, transparent);
  transition: opacity 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;
}

.md-check__step svg {
  display: block;
  width: 15px;
  height: 15px;
}

.md-check__step.is-done {
  color: var(--md-success);
  background: color-mix(in srgb, var(--md-success) 12%, var(--md-surface));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--md-success) 26%, transparent);
}

/* 当前该点的那个用一圈描边挑出来；点过的换成绿勾（见模板） */

.md-check__step.is-next {
  transform: scale(1.08);
  box-shadow: inset 0 0 0 1.5px color-mix(in srgb, var(--md-primary) 55%, transparent);
}

.md-check__refresh {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--md-text-3);
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.md-check__refresh:hover {
  background: color-mix(in srgb, var(--md-primary) 10%, transparent);
  color: var(--md-primary);
}

.md-check__canvas {
  position: relative;
  aspect-ratio: 600 / 250;
  overflow: hidden;
  cursor: crosshair;
  background: color-mix(in srgb, var(--md-primary) 6%, var(--md-surface-2));
  touch-action: manipulation;
}

.md-check__canvas img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.22s ease;
}

.md-check__canvas.is-ready img {
  opacity: 1;
}

.md-check__loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--md-text-3);
}

/* 选中标记：一圈细环 + 勾，不做实心圆盘 —— 那会把这个图形本身盖掉，
   用户就再也确认不了「我点的到底是哪个」 */
.md-check__hit {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 7%;
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  background: color-mix(in srgb, #fff 18%, transparent);
  box-shadow: inset 0 0 0 2px rgba(255, 255, 255, 0.92), 0 2px 6px -1px rgba(18, 58, 52, 0.35);
  color: #fff;
  pointer-events: none;
  animation: md-check-pop 0.26s cubic-bezier(0.2, 1.4, 0.4, 1);
}

.md-check__hit :deep(svg) {
  filter: drop-shadow(0 1px 2px rgba(15, 47, 41, 0.5));
}

.md-check__ripple {
  position: absolute;
  width: 16px;
  height: 16px;
  margin: -8px 0 0 -8px;
  border-radius: 50%;
  background: color-mix(in srgb, #fff 55%, transparent);
  box-shadow: 0 0 0 1.5px rgba(255, 255, 255, 0.85);
  pointer-events: none;
  animation: md-check-ripple 0.46s ease-out forwards;
}

/* ---------------- 通过态 ---------------- */
.md-check__done {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 44px;
  padding: 0 13px;
  border: 1px solid color-mix(in srgb, var(--md-success) 28%, var(--md-border));
  border-radius: 12px;
  background: color-mix(in srgb, var(--md-success) 7%, var(--md-surface-2));
  font-size: 13px;
  letter-spacing: 0.01em;
  color: color-mix(in srgb, var(--md-success) 82%, var(--md-text, #123));
  animation: md-check-rise 0.24s ease;
}

.md-check__done-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 19px;
  height: 19px;
  border-radius: 50%;
  background: var(--md-success);
  color: #fff;
}

/* ---------------- 状态 ---------------- */
.md-check.is-error .md-check__stage {
  border-color: color-mix(in srgb, var(--md-danger) 45%, var(--md-border));
  animation: md-check-shake 0.4s ease;
}

.md-check.is-error .md-check__prompt-text {
  color: var(--md-danger);
}

/* 切换通过/展开用的是 out-in，淡出淡入比位移更安静 */
.md-check-swap-enter-active,
.md-check-swap-leave-active {
  transition: opacity 0.16s ease;
}

.md-check-swap-enter-from,
.md-check-swap-leave-to {
  opacity: 0;
}

@keyframes md-check-rise {
  from { opacity: 0; transform: translateY(5px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes md-check-pop {
  from { opacity: 0; transform: translate(-50%, -50%) scale(0.72); }
  to { opacity: 1; transform: translate(-50%, -50%) scale(1); }
}

@keyframes md-check-ripple {
  from { opacity: 0.9; transform: scale(0.6); }
  to { opacity: 0; transform: scale(2.6); }
}

@keyframes md-check-shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-4px); }
  40% { transform: translateX(3px); }
  60% { transform: translateX(-2px); }
  80% { transform: translateX(1px); }
}

@media (prefers-reduced-motion: reduce) {
  .md-check__stage,
  .md-check__done,
  .md-check__hit,
  .md-check__ripple,
  .md-check.is-error .md-check__stage {
    animation: none;
  }
  .md-check-swap-enter-active,
  .md-check-swap-leave-active {
    transition: none;
  }
}
</style>
