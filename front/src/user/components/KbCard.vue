<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from '@/user/components/Icon.vue'
import { useSessionStore } from '@/user/stores/session'
import { portalKbUrl } from '@/shared/portal'
import { appHref } from '@/shared/appBase'
import type { KbVO } from '@/shared/api/types'

const props = defineProps<{ kb: KbVO }>()

const session = useSessionStore()

/**
 * 无封面时按 slug 稳定取一组浅色水彩底。
 *
 * <p>参考稿的封面是插画而不是纯色块，所以这里只要一层「天空色」，
 * 山、雾、小舟由模板里的 SVG 用白色半透明叠出来；六组色相让同屏卡片有区分，
 * 明度都压在浅档，顶部药丸与标题仍然最好读。</p>
 */
const PALETTES = [
  ['#d3ece5', '#6bb6a6'],
  ['#d6eaf6', '#78afce'],
  ['#dcefd7', '#88bd88'],
  ['#f2e8d3', '#c9b184'],
  ['#dde6f0', '#8ba3ba'],
  ['#eae1f3', '#a996c6']
] as const

const palette = computed(() => {
  const seed = props.kb.slug || props.kb.name || ''
  let hash = 0
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  return PALETTES[hash % PALETTES.length]
})

/** 封面地址失效（被删、换域名）时退回渐变占位，不能把浏览器的破图图标摆在卡片头上 */
const coverFailed = ref(false)
watch(() => props.kb.coverSrc, () => (coverFailed.value = false))
const showCover = computed(() => !!props.kb.coverSrc && !coverFailed.value)
/**
 * 徽标按「分享是否加密」两态：共享 = 分享未加密，加密 = 分享已加密（需口令）。
 *
 * <p>门户只出已发布的库，所以这里读 {@code shareStatus} 而不是 {@code visibility} ——
 * 可见范围（所有登录用户 / 本组织成员 / 仅维护名单）决定的是「平台内谁能读」，
 * 与「陌生人进来要不要输口令」不是一回事。</p>
 *
 * <p><b>文案刻意不叫「公开 / 私有」</b>：后台那边的「私有」是权限概念，这里若也叫私有，
 * 两处同名会被当成同一件事，而它们其实正交。「共享 / 加密」既短又直接对应
 * 「这条分享链有没有设访问密码」。</p>
 */
const shareStatus = computed(() => (props.kb.shareStatus === 'private' ? 'private' : 'public'))
const statusClass = computed(() => `is-${shareStatus.value}`)
const shareIcon = computed(() => (shareStatus.value === 'private' ? 'lock' : 'globe'))
const shareLabel = computed(() => (shareStatus.value === 'private' ? '加密' : '共享'))
const isMine = computed(() => !!session.user && session.user.id === props.kb.ownerId)
const link = computed(() => portalKbUrl(props.kb.tenantSlug, props.kb.slug))
/** 管理入口落在工作区：单 SPA 后组织段照样带上，缺组织信息时退回 /console 让落地页去解析 */
const manageUrl = computed(() => appHref(props.kb.tenantSlug
  ? `/console/${encodeURIComponent(props.kb.tenantSlug)}/kbs/${encodeURIComponent(props.kb.slug)}`
  : '/console'))
/** 门户是跨组织的（规范 F10「列表标注所属组织」）；组织名拿不到时退回 slug，至少能区分 */
const orgLabel = computed(() => props.kb.tenantName || props.kb.tenantSlug || '未归属组织')
/** 标题下只留「谁 · 哪个组织」的归属信息；创建时间收进悬停提示，底栏放最近更新 */
const ownerLabel = computed(() => props.kb.ownerName || '未知用户')

const copied = ref(false)
let copyTimer: number | undefined

onBeforeUnmount(() => window.clearTimeout(copyTimer))

/**
 * 「复制链接」给的是<b>分享短链</b>（{@code /share/{token}}），不是卡片的跳转地址。
 *
 * <p>差在三处，而且都指向门户以外的读者：短链不把组织名和库名一起抖出去；
 * 库改名后短链照样有效；以及最要紧的一条 —— 短链可以在「分享」面板里撤销，
 * 而 {@code /kb/{org}/{slug}} 抄出去就再也收不回来了。</p>
 *
 * <p>token 理论上不会缺（门户只列已发布的库，必有整库分享），仍然留了降级：
 * 缺 token 时给门户地址，好过复制出一段 {@code /share/undefined}。</p>
 */
const shareLink = computed(() => (props.kb.shareToken
  ? appHref(`/share/${encodeURIComponent(props.kb.shareToken)}`)
  : link.value))

async function copyLink(event: MouseEvent) {
  const trigger = event.currentTarget as HTMLElement | null
  trigger?.blur()
  menuOpen.value = false
  try {
    await navigator.clipboard.writeText(`${window.location.origin}${shareLink.value}`)
    copied.value = true
    window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => (copied.value = false), 1600)
  } catch {
    // http 非 localhost 等非安全上下文下剪贴板不可用，静默失败
  }
}

/**
 * 「更多」菜单是点开的，不是悬停出来的：原来靠 :focus-within，鼠标点那个圆钮既不会聚焦也没有
 * 状态，菜单永远不出现；而卡片要裁圆角就得 overflow:hidden，绝对定位的菜单还会被裁掉。
 * 所以这里改成显式开合 + 点外面/Esc 关，菜单本身 Teleport 到 body 再用 fixed 定位（见 portal.css）：
 * 留在卡片里的话，悬停那层 transform 会让卡片变成 fixed 的包含块，JS 按视口算出来的坐标就全错位了。
 */
const menuOpen = ref(false)
const moreRef = ref<HTMLElement | null>(null)
const menuRef = ref<HTMLElement | null>(null)

function onDocPointerDown(event: PointerEvent) {
  const target = event.target as Node | null
  if ((moreRef.value && target && moreRef.value.contains(target)) || (menuRef.value && target && menuRef.value.contains(target))) {
    return
  }
  menuOpen.value = false
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    menuOpen.value = false
    moreRef.value?.querySelector<HTMLElement>('.md-more')?.focus()
  }
}

/**
 * 打开时挂监听并把菜单贴到按钮下方（下方放不下就朝上翻），关闭时全部摘掉。
 * 菜单是 fixed 定位，一旦触发点被滚走或被挤到别处（换视图），悬在原地的菜单比收起更让人困惑，
 * 所以滚动与窗口尺寸变化都直接关掉它。
 */
watch(menuOpen, async (open) => {
  if (!open) {
    document.removeEventListener('pointerdown', onDocPointerDown, true)
    document.removeEventListener('keydown', onKeydown)
    window.removeEventListener('scroll', closeMenu, true)
    window.removeEventListener('resize', closeMenu)
    return
  }
  document.addEventListener('pointerdown', onDocPointerDown, true)
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('scroll', closeMenu, true)
  window.addEventListener('resize', closeMenu)
  await nextTick()
  placeMenu()
})

function closeMenu() {
  menuOpen.value = false
}

function placeMenu() {
  const trigger = moreRef.value?.querySelector<HTMLElement>('.md-more')
  const menu = menuRef.value
  if (!trigger || !menu) {
    return
  }
  const rect = trigger.getBoundingClientRect()
  const height = menu.offsetHeight
  const width = menu.offsetWidth
  // 卡片区不再自己滚动，菜单的可放区域就是视口
  const low = window.innerHeight - 8
  const below = rect.bottom + 6
  // 下方放不下就朝上翻，并保证菜单两头都不超出视口
  const y = below + height <= low ? below : Math.max(8, Math.min(rect.top - height - 6, low - height))
  menu.style.top = `${y}px`
  menu.style.left = `${Math.max(8, Math.min(rect.right - width, window.innerWidth - width - 8))}px`
}

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', closeMenu, true)
  window.removeEventListener('resize', closeMenu)
})
</script>

<template>
  <article class="md-card" :style="{ '--cover-a': palette[0], '--cover-b': palette[1] }">
    <!-- 竖版卡片：顶部一条水彩封面，正文在下，右下一颗圆形箭头 -->
    <router-link
      class="md-card__cover"
      :to="link"
    >
      <img v-if="showCover" class="md-card__cover-img" :src="kb.coverSrc" alt="" loading="lazy" @error="coverFailed = true">
      <span v-else class="md-card__cover-art" aria-hidden="true">
        <svg viewBox="0 0 400 170" preserveAspectRatio="xMidYMid slice">
          <circle class="md-art__soft" cx="316" cy="40" r="20" fill="rgba(255,255,255,.7)" />
          <path class="md-art__soft" d="M0 116Q38 82 78 96Q118 110 154 90Q190 74 228 94Q266 114 304 96Q340 80 372 94Q386 100 400 96V170H0Z" fill="rgba(20,84,72,.16)" />
          <path class="md-art__soft" d="M0 140Q46 114 90 128Q134 142 174 120Q214 100 256 126Q298 150 338 130Q368 116 400 128V170H0Z" fill="rgba(255,255,255,.52)" />
          <path class="md-art__soft" d="M0 156Q74 142 146 152Q218 162 288 150Q344 140 400 150V170H0Z" fill="rgba(255,255,255,.74)" />
          <path d="M0 163Q100 155 200 161Q300 167 400 159V170H0Z" fill="rgba(26,76,68,.12)" />
          <path d="M286 132q26 3 44 0M300 140q18 2 30 0" stroke="rgba(24,74,66,.22)" stroke-width="1.4" stroke-linecap="round" fill="none" />
          <g fill="rgba(26,76,68,.5)">
            <path d="M198 141c10-6 22-6 32 0-10 6-22 6-32 0Z" />
            <rect x="213.2" y="124" width="1.6" height="15" rx=".8" />
            <path d="M215 127l11 7h-11Z" fill="rgba(252,254,253,.86)" />
          </g>
          <path d="M104 62q5-5 10 0M118 57q4-4 8 0" stroke="rgba(255,255,255,.55)" stroke-width="1.4" stroke-linecap="round" fill="none" />
        </svg>
      </span>
      <span class="md-card__badge" :class="statusClass" :title="shareStatus === 'private' ? '分享已加密，需要访问密码' : '分享未加密'">
        <Icon :name="shareIcon" :size="11" />{{ shareLabel }}
      </span>
    </router-link>

    <div class="md-card__body">
      <div class="md-card__head">
        <span class="md-card__mark"><Icon name="books" :size="14" /></span>
        <h3 class="md-card__title">
          <router-link :to="link">{{ kb.name }}</router-link>
        </h3>
      </div>

      <p class="md-card__desc">{{ kb.description || '暂无描述' }}</p>

      <div class="md-card__foot">
        <span class="md-card__meta-item">
          <Icon name="file" :size="13" />{{ kb.docCount }} 篇
        </span>
        <span class="md-card__meta-item md-card__meta-item--org" :title="`${orgLabel} · ${ownerLabel}`">
          <Icon name="building" :size="13" /><span class="md-card__meta-text">{{ orgLabel }}</span>
        </span>
        <router-link class="md-card__go" :to="link" :aria-label="`阅读《${kb.name}》`">
          <Icon name="arrowRight" :size="15" />
        </router-link>
      </div>
    </div>

    <!-- 「更多」浮在封面右上角：必须是 cover 的兄弟节点，落在 router-link 里点一下会顺带跳转 -->
    <div ref="moreRef" class="md-card__more">
      <button
        type="button"
        class="md-more"
        :class="{ 'is-open': menuOpen }"
        :title="copied ? '已复制链接' : '更多操作'"
        aria-label="更多操作"
        aria-haspopup="true"
        :aria-expanded="menuOpen"
        @click="menuOpen = !menuOpen"
      >
        <Icon :name="copied ? 'check' : 'more'" :size="15" />
      </button>
      <Teleport to="body">
        <div v-if="menuOpen" ref="menuRef" class="md-card__menu" role="menu">
          <button type="button" class="md-card__menu-item" role="menuitem" @click="copyLink">
            <Icon name="link" :size="14" />复制分享链接
          </button>
          <a v-if="isMine" class="md-card__menu-item" role="menuitem" :href="manageUrl">
            <Icon name="dashboard" :size="14" />管理此库
          </a>
        </div>
      </Teleport>
    </div>
  </article>
</template>
