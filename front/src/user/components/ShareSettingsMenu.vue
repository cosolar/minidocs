<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from '@/user/components/Icon.vue'
import { useSettingsStore } from '@/shared/stores/settings'
import { READER_FONTS, applyReaderFont, readReaderFont, readerFontById } from '@/shared/readerFont'
import {
  MAX_READER_WIDTH,
  MIN_READER_WIDTH,
  READER_WIDTH_STEP,
  applyReaderWidth,
  readReaderWidth
} from '@/shared/readerWidth'

/**
 * 分享页顶栏的「阅读设置」：外观（明亮 / 暗黑 / 跟随系统）+ 正文字体。
 *
 * <p>访客没有身份也没有设置中心，而这两件事恰恰是外部读者最常要的 —— 深夜读文档要暗色，
 * 长文要换成自己顺眼的字体。两者都只落在这台浏览器：外观走共享设置 store 的 {@code patchLocal}
 * （不发给服务端），字体走 {@code shared/readerFont}（纯 localStorage）。</p>
 *
 * <p>面板用 fixed 定位、按触发按钮实时算位（与门户顶栏的账号菜单同一手法）：顶栏是 sticky 的，
 * 绝对定位的面板在窄屏会被下方元素压住或溢出视口右沿。</p>
 *
 * <p>不引 Element Plus：规范 F11 要求阅读端首屏 chunk 不含 EP，这里连下拉与分段按钮都自己实现。</p>
 */
type ThemeMode = 'light' | 'dark' | 'system'

const THEMES: { value: ThemeMode; label: string; icon: 'sun' | 'moon' | 'monitor' }[] = [
  { value: 'light', label: '明亮', icon: 'sun' },
  { value: 'dark', label: '暗黑', icon: 'moon' },
  { value: 'system', label: '跟随系统', icon: 'monitor' }
]

const settings = useSettingsStore()

const open = ref(false)
const wrapRef = ref<HTMLElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const triggerRef = ref<HTMLElement | null>(null)

const fontId = ref(readReaderFont())
/** 当前字体的完整信息：面板里用它渲染预览与说明 */
const font = computed(() => readerFontById(fontId.value))

const width = ref(readReaderWidth())
/** 满格即「自适应」：占满中栏，与不设上限等价，没必要让读者看到一个 100% */
const widthLabel = computed(() => (width.value >= MAX_READER_WIDTH ? '自适应' : `${width.value}%`))
/** 滑轨已填充比例：给 CSS 画双色轨道用（原生 range 的已选段各浏览器样式不一） */
const widthFill = computed(
  () => `${((width.value - MIN_READER_WIDTH) / (MAX_READER_WIDTH - MIN_READER_WIDTH)) * 100}%`
)

/** 拖动即生效：正文宽度是「看着调」才顺手的事，不设成松手才生效 */
function setWidth(percent: number) {
  width.value = percent
  applyReaderWidth(percent)
}

/** store 里只有 light / dark 是显式选择，其余（含缺省）都按「跟随系统」显示 */
const theme = computed<ThemeMode>(() => {
  const mode = settings.value.themeMode
  return mode === 'light' || mode === 'dark' ? mode : 'system'
})

function pickTheme(value: ThemeMode) {
  // 访客页面：只写本地，不发网关（服务端没有对应身份）
  settings.patchLocal({ themeMode: value })
}

function pickFont(value: string) {
  fontId.value = value
  applyReaderFont(value)
}

/** select 直接绑 model：选中即生效，面板不关，读者的视线不用来回跳 */
const fontModel = computed({
  get: () => fontId.value,
  set: (value: string) => pickFont(value)
})

/** 面板贴着按钮下沿展开，右缘与按钮对齐，并兜住视口两侧 */
function place() {
  const panel = panelRef.value
  const trigger = triggerRef.value
  if (!panel || !trigger) return
  const rect = trigger.getBoundingClientRect()
  const left = Math.max(8, Math.min(rect.right - panel.offsetWidth, window.innerWidth - panel.offsetWidth - 8))
  panel.style.top = `${rect.bottom + 8}px`
  panel.style.left = `${left}px`
}

/** 打开时挂监听（Esc / 点外部），关闭只走 {@link close}，避免每次打开多挂一份 */
async function toggle() {
  if (open.value) {
    close()
    return
  }
  open.value = true
  document.addEventListener('pointerdown', onDocPointerDown, true)
  document.addEventListener('keydown', onKeydown)
  await nextTick()
  place()
}

/** 关闭只走这一条路径：改状态与摘监听绑在一起，再打开不会挂上第二份监听 */
function close() {
  open.value = false
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onKeydown)
}

function onDocPointerDown(event: PointerEvent) {
  const target = event.target as Node | null
  if (wrapRef.value && target && wrapRef.value.contains(target)) return
  close()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    close()
    triggerRef.value?.focus()
  }
}

const darkQuery = window.matchMedia('(prefers-color-scheme: dark)')

/**
 * 「跟随系统」要跟得上系统实时变化。
 *
 * <p>store 只在 {@code applyToDom} 那一刻解析一次系统偏好，访客在面板里选了「跟随系统」之后
 * 系统若在正文中途切换（macOS 傍晚自动变暗），页面不会跟着变。这里补一次重算，仅限当前是
 * 「跟随系统」时，读者显式选定的明暗不作干扰。</p>
 */
function onSystemThemeChange() {
  if (theme.value === 'system') settings.applyToDom()
}

onMounted(() => {
  darkQuery.addEventListener('change', onSystemThemeChange)
})

onBeforeUnmount(() => {
  darkQuery.removeEventListener('change', onSystemThemeChange)
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="wrapRef" class="md-prefs">
    <button
      ref="triggerRef"
      type="button"
      class="md-share__action"
      :class="{ 'is-on': open }"
      title="阅读设置"
      aria-label="阅读设置"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click="toggle"
    >
      <Icon name="settings" :size="17" />
    </button>

    <div v-if="open" ref="panelRef" class="md-menu md-menu--fixed md-prefs__panel" role="dialog" aria-label="阅读设置">
      <div class="md-menu__head">
        <strong>阅读设置</strong>
        <small>只作用于这份分享，保存于这台浏览器</small>
      </div>

      <div class="md-prefs__row">
        <span class="md-prefs__label">外观</span>
        <div class="md-prefs__seg" role="group">
          <button
            v-for="item in THEMES"
            :key="item.value"
            type="button"
            class="md-prefs__seg-btn"
            :class="{ 'is-on': theme === item.value }"
            :aria-pressed="theme === item.value"
            @click="pickTheme(item.value)"
          >
            <Icon :name="item.icon" :size="14" />
            <span>{{ item.label }}</span>
          </button>
        </div>
      </div>

      <div class="md-prefs__row">
        <span class="md-prefs__label">正文字体</span>
        <div class="md-prefs__select">
          <!-- option 上带字体栈：选了之后回到这一页，选项本身就是这块字体的样张 -->
          <select v-model="fontModel" aria-label="正文字体">
            <option v-for="item in READER_FONTS" :key="item.id" :value="item.id" :style="{ fontFamily: item.stack }">
              {{ item.label }}
            </option>
          </select>
          <Icon name="chevronDown" :size="14" />
        </div>
      </div>

      <div class="md-prefs__row">
        <span class="md-prefs__label">
          正文宽度
          <b class="md-prefs__value">{{ widthLabel }}</b>
        </span>
        <div class="md-prefs__range" :style="{ '--md-prefs-fill': widthFill }">
          <input
            type="range"
            :min="MIN_READER_WIDTH"
            :max="MAX_READER_WIDTH"
            :step="READER_WIDTH_STEP"
            :value="width"
            aria-label="正文宽度百分比"
            @input="setWidth(Number(($event.target as HTMLInputElement).value))"
          >
          <button
            type="button"
            class="md-prefs__reset"
            :disabled="width >= MAX_READER_WIDTH"
            @click="setWidth(MAX_READER_WIDTH)"
          >
            自适应
          </button>
        </div>
      </div>

      <p class="md-prefs__hint">{{ font.note }}</p>
    </div>
  </div>
</template>