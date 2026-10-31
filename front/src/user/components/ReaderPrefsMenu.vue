<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from '@/user/components/Icon.vue'
import { useSettingsStore } from '@/shared/stores/settings'
import { READER_FONTS, applyReaderFont, readReaderFont, readerFontById } from '@/shared/readerFont'
import {
  DEFAULT_READER_SIZE,
  MAX_READER_SIZE,
  MIN_READER_SIZE,
  READER_SIZE_STEP,
  applyReaderSize,
  readReaderSize
} from '@/shared/readerSize'
import {
  applyReaderTheme,
  currentThemeId,
  readerThemeById,
  themePacks,
  themeSwatch
} from '@/shared/readerTheme'
import {
  MAX_READER_WIDTH,
  MIN_READER_WIDTH,
  READER_WIDTH_STEP,
  applyReaderWidth,
  readReaderWidth
} from '@/shared/readerWidth'

/**
 * 「阅读设置」面板：主题 + 外观（明亮 / 暗黑 / 跟随系统）+ 整体字号 + 正文字体，
 * 分享页再带一项正文宽度。
 *
 * <p>两个宿主共用这一个组件：分享页顶栏（{@code ShareTopBar.vue}）与门户阅读条
 * （{@code ReadBody.vue}）。读者在这两处读的是同一篇正文，要调的也是同一组偏好 ——
 * 分成两个组件，迟早一边加了另一边忘了。</p>
 *
 * <p>访客没有设置中心，而这几件事恰恰是外部读者最常要的 —— 深夜读文档要暗色，
 * 长文要换成自己顺眼的字体、大一号的字。它们都只落在这台浏览器：外观走共享设置 store 的
 * {@code patchLocal}（不发给服务端），字体 / 字号 / 主题走 {@code shared/reader*}（纯 localStorage）。</p>
 *
 * <p><b>「主题」与「外观」是两件事。</b>主题决定这套版面用什么颜色（纸色、强调色、标题装饰），
 * 外观决定取主题的哪一档（每套主题都备了明亮与暗黑两套令牌）。因此换主题不必重选明暗，
 * 切明暗也不会把主题改回默认。</p>
 *
 * <p><b>为什么正文宽度只在分享页出现。</b>门户阅读页的中栏宽度由两侧拖拽手柄决定
 * （{@code --md-doc-width} 那份偏好与「按百分比收窄并居中」根本不是一回事），那边的读者
 * 也没有这个入口。同一个词在两处指的是两样东西，别因为长得像就顺手一起铺开。</p>
 *
 * <p>面板 {@link Teleport} 到 {@code body}：触发按钮所在的顶栏 / 阅读条都是 sticky，
 * 且都带了 {@code backdrop-filter}。按规范它会成为 fixed 后代的包含块，
 * 于是按视口坐标算出来的 {@code left/top} 会跟着偏移。分享页那条恰好通栏宽又吸附在视口顶端，
 * 两个方向的偏移都是 0，看着像没问题；门户阅读条只是中间一栏，同样的算法直接错位。
 * 挂到 body 上就绕开了包含块，两个宿主用的是同一套坐标。</p>
 *
 * <p>不引 Element Plus：规范 F11 要求阅读端首屏 chunk 不含 EP，这里连下拉与分段按钮都自己实现。</p>
 */
type ThemeMode = 'light' | 'dark' | 'system'
type Host = 'share' | 'portal'

const props = withDefaults(defineProps<{
  /**
   * 宿主。分享页多一项「正文宽度」；门户阅读条的栏宽归拖拽手柄管，这一项在那儿无从解释。
   */
  context?: Host
}>(), { context: 'share' })

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

/**
 * 触发按钮跟宿主走：分享页的动作区是一圈描边药丸，门户阅读条上是无底描边的图标钮。
 * 各按所在工具条的口径来，面板挪过去才不会像一个贴上去的补丁。
 *
 * <p>{@code md-icon-btn--blue} 只加在门户那颗上：三条正文工具条（设置 / 编辑 / 大纲）
 * 各占一个色相，同栏并列时靠颜色就能分辨，不必每颗都悬停看提示。色相令牌见
 * {@code variables.css} 的 {@code --md-ink-*}（亮暗两档都备好了）。</p>
 */
const triggerClass = computed(() =>
  props.context === 'portal' ? 'md-icon-btn md-icon-btn--blue' : 'md-share__action'
)

const hint = computed(() =>
  props.context === 'portal'
    ? '只保存在这台浏览器，读哪个知识库都照这个来'
    : '只作用于这份分享，保存于这台浏览器'
)

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

/* ------------------------------------------------------------------ 主题 */

const activeTheme = computed(() => readerThemeById(currentThemeId.value))

/** 卡片上的明暗两格小样：底色 + 主强调 + 次强调，直接取主题包自己的令牌 */
const themeCards = computed(() =>
  themePacks.value.map((pack) => ({
    pack,
    light: themeSwatch(pack, 'light'),
    dark: themeSwatch(pack, 'dark')
  }))
)

function pickPack(id: string) {
  applyReaderTheme(id)
}

/* ------------------------------------------------------------------ 字号 */

const size = ref(readReaderSize())

const sizeLabel = computed(() => `${size.value}px`)
const sizeFill = computed(
  () => `${((size.value - MIN_READER_SIZE) / (MAX_READER_SIZE - MIN_READER_SIZE)) * 100}%`
)

function setSize(value: number) {
  size.value = value
  applyReaderSize(value)
}

/** 选中主题的一句话说明；默认主题也有一段，避免面板在这里忽然空一块 */
const themeNote = computed(() => activeTheme.value.description || '')

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

/** 面板贴着按钮下沿展开，右缘与按钮对齐，并兜住视口两侧与底边（主题多了以后面板更高） */
function place() {
  const panel = panelRef.value
  const trigger = triggerRef.value
  if (!panel || !trigger) return
  const rect = trigger.getBoundingClientRect()
  const left = Math.max(8, Math.min(rect.right - panel.offsetWidth, window.innerWidth - panel.offsetWidth - 8))
  const top = Math.max(8, Math.min(rect.bottom + 8, window.innerHeight - panel.offsetHeight - 8))
  panel.style.top = `${top}px`
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
  if (!target) return
  if (wrapRef.value?.contains(target)) return
  // 面板 Teleport 到了 body，不在 wrapRef 里面：只判按钮会把「点面板」当成点外部，
  // 面板一开就立刻收回，字体永远选不中。
  if (panelRef.value?.contains(target)) return
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
      :class="[triggerClass, { 'is-on': open }]"
      title="阅读设置"
      aria-label="阅读设置"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click="toggle"
    >
      <Icon name="settings" :size="17" />
    </button>

    <Teleport to="body">
      <div v-if="open" ref="panelRef" class="md-menu md-menu--fixed md-prefs__panel" role="dialog" aria-label="阅读设置">
        <div class="md-menu__head">
          <strong>阅读设置</strong>
          <small>{{ hint }}</small>
        </div>

        <div class="md-prefs__row">
          <span class="md-prefs__label">主题</span>
          <div class="md-prefs__themes" role="group" aria-label="阅读主题">
            <button
              v-for="card in themeCards"
              :key="card.pack.id"
              type="button"
              class="md-prefs__theme"
              :class="{ 'is-on': currentThemeId === card.pack.id }"
              :aria-pressed="currentThemeId === card.pack.id"
              :title="card.pack.description || card.pack.name"
              @click="pickPack(card.pack.id)"
            >
              <span class="md-prefs__theme-swatch" aria-hidden="true">
                <span class="md-prefs__theme-half" :style="{ background: card.light.bg }">
                  <i class="md-prefs__theme-bar" :style="{ background: card.light.primary }"></i>
                  <i class="md-prefs__theme-bar is-thin" :style="{ background: card.light.accent }"></i>
                </span>
                <span class="md-prefs__theme-half is-dark" :style="{ background: card.dark.bg }">
                  <i class="md-prefs__theme-bar" :style="{ background: card.dark.primary }"></i>
                  <i class="md-prefs__theme-bar is-thin" :style="{ background: card.dark.accent }"></i>
                </span>
              </span>
              <span class="md-prefs__theme-name">{{ card.pack.name }}</span>
              <Icon
                v-if="currentThemeId === card.pack.id"
                name="check"
                :size="12"
                class="md-prefs__theme-check"
              />
            </button>
          </div>
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

        <!-- 字号跟正文走，不跟界面走：三个宿主的正文一起缩放，顶栏与侧栏保持原尺寸 -->
        <div class="md-prefs__row">
          <span class="md-prefs__label">
            字号
            <b class="md-prefs__value">{{ sizeLabel }}</b>
          </span>
          <div class="md-prefs__range" :style="{ '--md-prefs-fill': sizeFill }">
            <input
              type="range"
              :min="MIN_READER_SIZE"
              :max="MAX_READER_SIZE"
              :step="READER_SIZE_STEP"
              :value="size"
              aria-label="正文字号"
              @input="setSize(Number(($event.target as HTMLInputElement).value))"
            >
            <button
              type="button"
              class="md-prefs__reset"
              :disabled="size === DEFAULT_READER_SIZE"
              @click="setSize(DEFAULT_READER_SIZE)"
            >
              默认
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

        <!--
          正文宽度：只给分享页。门户阅读页的栏宽由两侧拖拽手柄决定，那一栏没有「按百分比
          收窄并居中」这回事，滑轨摆在那儿读者也说不清在调什么。
        -->
        <div v-if="context === 'share'" class="md-prefs__row">
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

        <p v-if="themeNote" class="md-prefs__hint">{{ themeNote }}</p>
        <p class="md-prefs__hint md-prefs__hint--follow">{{ font.note }}</p>
      </div>
    </Teleport>
  </div>
</template>
