<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from '@/user/components/Icon.vue'
import { useSessionStore } from '@/user/stores/session'
import { portalKbUrl } from '@/shared/portal'
import { appHref } from '@/shared/appBase'
import KbGlyph from '@/shared/components/KbGlyph.vue'
import type { KbVO } from '@/shared/api/types'

const props = defineProps<{ kb: KbVO }>()

const session = useSessionStore()

/** 无封面时按 slug 稳定取一组渐变色，让默认封面彼此有区分度 */
const PALETTES = [
  ['#3b82f6', '#22d3ee'],
  ['#8b5cf6', '#6366f1'],
  ['#10b981', '#34d399'],
  ['#f59e0b', '#fbbf24'],
  ['#ec4899', '#a855f7'],
  ['#0ea5e9', '#14b8a6'],
  ['#f43f5e', '#fb923c'],
  ['#6366f1', '#0ea5e9']
] as const

const palette = computed(() => {
  const seed = props.kb.slug || props.kb.name || ''
  let hash = 0
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  return PALETTES[hash % PALETTES.length]
})

const initial = computed(() => (props.kb.name || '?').trim().charAt(0).toUpperCase())
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
const badgeClass = computed(() => `is-${shareStatus.value}`)
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
/** 创建者与创建时间提到标题下，作为「这是谁的库、建了多久」的归属线索 */
const ownerLabel = computed(() => props.kb.ownerName || '未知用户')
const createdText = computed(() => {
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(props.kb.createdAt || '')
  return matched ? `${matched[1]}-${matched[2]}-${matched[3]}` : ''
})
/** 标签按文案哈希取色：同一标签在任何卡片上颜色一致，一排标签又不至于糊成同色 */
function tagTone(tag: string) {
  let hash = 0
  for (let i = 0; i < tag.length; i++) hash = (hash * 31 + tag.charCodeAt(i)) >>> 0
  return `is-tone-${hash % 5}`
}

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
  // 门户整页不滚、只有卡片区滚，所以「下方放不下就朝上翻」看这片区域的底沿，不是视口底沿
  const box = trigger.closest('.md-cards')?.getBoundingClientRect()
  const high = box?.top ?? 0
  const low = Math.min(box?.bottom ?? window.innerHeight, window.innerHeight) - 4
  const below = rect.bottom + 6
  // 下方放不下就朝上翻，并保证菜单两头都不超出卡片区
  const y = below + height <= low ? below : Math.max(high, Math.min(rect.top - height - 6, low - height))
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
  <article class="md-card">
    <router-link
      class="md-card__cover"
      :to="link"
      :style="{ '--cover-a': palette[0], '--cover-b': palette[1] }"
    >
      <img v-if="kb.coverSrc" class="md-card__cover-img" :src="kb.coverSrc" alt="" loading="lazy">
      <span v-else class="md-card__cover-art" />
      <span v-if="!kb.coverSrc" class="md-card__mark">{{ initial }}</span>
      <span class="md-card__badge" :class="badgeClass">
        <Icon :name="shareIcon" :size="12" />{{ shareLabel }}
      </span>
    </router-link>

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
        <Icon :name="copied ? 'check' : 'more'" :size="16" />
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

    <div class="md-card__body">
      <div class="md-card__head">
        <span class="md-card__glyph" :style="{ '--cover-a': palette[0], '--cover-b': palette[1] }">
          <KbGlyph :size="19" />
        </span>
        <h3 class="md-card__title">
          <router-link :to="link">{{ kb.name }}</router-link>
        </h3>
      </div>

      <p class="md-card__byline" :title="`${ownerLabel} · ${orgLabel}${createdText ? ` · 创建于 ${createdText}` : ''}`">
        <Icon name="user" :size="12" />
        <span class="md-card__byline-name">{{ ownerLabel }} · {{ orgLabel }}</span>
        <span v-if="createdText" class="md-card__byline-date">· 创建于 {{ createdText }}</span>
      </p>

      <p class="md-card__desc">{{ kb.description || '暂无描述' }}</p>

      <div v-if="kb.tags.length" class="md-card__tags">
        <span v-for="tag in kb.tags.slice(0, 4)" :key="tag" class="md-chip" :class="tagTone(tag)">{{ tag }}</span>
      </div>
    </div>

    <div class="md-card__foot">
      <!--
        「进入」在前、元信息在后：.md-card__meta 上有 margin-left:auto，会把自己推到右端，
        两个子元素的顺序因此不能反 —— 反了就变成元信息贴左、进入悬在最右边（曾经就是这样）。
      -->
      <router-link class="md-enter" :to="link">
        进入<Icon name="arrowRight" :size="13" />
      </router-link>

      <div class="md-card__meta">
        <span class="md-card__meta-item">
          <Icon name="file" :size="13" />{{ kb.docCount }} 篇文档
        </span>
        <template v-if="kb.updatedText">
          <span class="md-card__dot" />
          <span class="md-card__meta-item">
            <Icon name="clock" :size="13" />最近更新 {{ kb.updatedText }}
          </span>
        </template>
      </div>
    </div>
  </article>
</template>
