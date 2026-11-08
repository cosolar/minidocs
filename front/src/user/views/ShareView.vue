<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/user/components/Icon.vue'
import MobileActionBar from '@/user/components/MobileActionBar.vue'
import ReadBody from '@/user/components/ReadBody.vue'
import ReaderPrefsMenu from '@/user/components/ReaderPrefsMenu.vue'
import ShareMenu from '@/user/components/ShareMenu.vue'
import ShareStatusBar from '@/user/components/ShareStatusBar.vue'
import ShareTopBar from '@/user/components/ShareTopBar.vue'
import StatusView from '@/user/views/StatusView.vue'
import LockView from '@/user/views/LockView.vue'
import { scrollEl, viewEl } from '@/shared/scrollRegion'
import { shareApi } from '@/user/api'
import { toApiErrorInfo } from '@/shared/api/http'
import { useBodyClasses } from '@/user/composables/useBodyClasses'
import { useShareLink } from '@/user/composables/useShareLink'
import type { DocNode, NavMenuItem, ReadView } from '@/user/api/types'

type State = 'loading' | 'password' | 'ok' | 'notfound' | 'expired'

const route = useRoute()
const router = useRouter()

const state = ref<State>('loading')
/** 已有正文、正在切换文档：保留整页骨架，只在正文区做轻量提示 */
const switching = ref(false)
const view = ref<ReadView | null>(null)
const kbName = ref('')
const errorMessage = ref('')
const verifyError = ref('')
const submitting = ref(false)
const lockRef = ref<InstanceType<typeof LockView> | null>(null)

const token = computed(() => String(route.params.token || ''))
const docPath = computed(() => (typeof route.query.path === 'string' ? route.query.path : undefined))

// 阅读态走阅读页样式，其余态居中展示错误 / 口令卡片
useBodyClasses(
  computed(() =>
    state.value === 'ok' ? ['md-page--read', 'md-page--share'] : ['md-page--error']
  )
)

watch(
  () => [token.value, docPath.value],
  () => void load(),
  { immediate: true }
)

async function load() {
  /*
   * 已经在阅读态时切换文档，不再退回整页骨架：换的只是正文与大纲，分享顶栏、左侧目录、
   * 右侧大纲、底部状态栏都留在原地，避免整屏闪「加载中…」的整页刷新观感。
   */
  if (state.value === 'ok' && view.value !== null) {
    switching.value = true
  } else {
    state.value = 'loading'
  }
  verifyError.value = ''
  try {
    const data = await shareApi.read(token.value, docPath.value)
    kbName.value = data.kbName || ''
    if (data.state === 'password') {
      state.value = 'password'
      await nextTick()
      lockRef.value?.reset()
      return
    }
    view.value = data.view ?? null
    state.value = 'ok'
    /*
     * 配了菜单但地址里没指定文档时落到第一项：否则读者落在「全库第一篇」，而它可能不属于
     * 任何菜单项，导航条一个都不亮，看起来就像一排坏掉的装饰。
     */
    if (!docPath.value && view.value?.menu?.length) {
      const target = targetOf(view.value.menu[0])
      if (target && target !== view.value.currentPath) {
        openDoc(target)
        return
      }
    }
  } catch (error) {
    const info = toApiErrorInfo(error)
    errorMessage.value = info.message || '链接无效、已被撤销，或关联内容已被删除。'
    state.value = info.status === 410 ? 'expired' : 'notfound'
  } finally {
    switching.value = false
  }
}

async function verify(password: string) {
  submitting.value = true
  verifyError.value = ''
  try {
    await shareApi.verify(token.value, password)
    await load()
  } catch (error) {
    verifyError.value = toApiErrorInfo(error).message || '密码错误，请重试'
    lockRef.value?.reset()
  } finally {
    submitting.value = false
  }
}

/* --------------------------------------------------------------- 导航菜单 */

const menu = computed<NavMenuItem[]>(() => view.value?.menu || [])

/**
 * 读者是否把左栏切成了全库目录（点顶栏知识库名切换）。
 *
 * <p>没配导航菜单时左栏本来就是整棵树，这个开关没有意义，{@link allMode} 直接把它算成真，
 * 顶栏那颗按钮也会退化成静态标识。</p>
 */
const allTree = ref(false)

/** 左栏当前的实际范围：显式切到全库，或压根没有菜单可收窄 */
const allMode = computed(() => allTree.value || menu.value.length === 0)

/** 树里按路径找节点：菜单项的落地文档、左栏收窄的范围都要靠它定位 */
function findNode(nodes: DocNode[], path: string): DocNode | null {
  for (const node of nodes) {
    if (node.path === path) return node
    const hit = node.children?.length ? findNode(node.children, path) : null
    if (hit) return hit
  }
  return null
}

/** 目录菜单项落到哪一篇：按树序取第一棵文档，空目录返回 null */
function firstDocOf(node: DocNode | null): string | null {
  if (!node) return null
  if (node.type === 'doc') return node.path
  for (const child of node.children || []) {
    const hit = firstDocOf(child)
    if (hit) return hit
  }
  return null
}

/** 菜单项对应的落地文档路径 */
function targetOf(item: NavMenuItem): string | null {
  if (item.type === 'doc') return item.path
  return firstDocOf(findNode(view.value?.tree || [], item.path))
}

/**
 * 当前高亮的菜单项。
 *
 * <p>文档项精确命中优先于目录项包含关系：一篇文档既可能是某个目录菜单项的内容，也可能被
 * 单独拎成菜单项，这时该亮的是更具体的那一个。多个目录同时包含时取路径最长的（最深的）。</p>
 *
 * <p>读者把左栏切成全库后，菜单项一个都不亮：此刻左栏不属于任何菜单项的范围，亮一个会让
 * 「这一栏只属于这个入口」的印象继续误导人。改回某个菜单项时 {@link pickMenu} 会恢复高亮。</p>
 */
const activeMenuPath = computed<string | null>(() => {
  if (allMode.value) return null
  const current = view.value?.currentPath
  const items = menu.value
  if (!current || !items.length) return null
  const docHit = items.find((item) => item.type === 'doc' && item.path === current)
  if (docHit) return docHit.path
  let best: NavMenuItem | null = null
  for (const item of items) {
    if (item.type !== 'dir') continue
    if (current === item.path || current.startsWith(item.path + '/')) {
      if (!best || item.path.length > best.path.length) best = item
    }
  }
  return best ? best.path : null
})

/**
 * 左栏目录树的显示范围 —— 规则一句话：**默认显示当前菜单项的「所在范围」，点知识库名切成全库**。
 *
 * <p>目录项：显示该目录的子树（目录本身不出现，它的子项就是这一栏的根）。<br>
 * 文档项：显示该文档所在目录的兄弟文档，当前这篇会被高亮。文档在库根时它的「所在目录」
 * 就是根，于是铺开整棵树 —— 这不是特例，是同一个规则推到根上的结果。<br>
 * 没有菜单、或当前文档不落在任何菜单项里：整棵树。<br>
 * {@code allTree} 为真（读者点了顶栏的知识库名）：整棵树。</p>
 *
 * <p>为什么不让左栏永远铺全库：菜单已经把库切成几个入口，左栏再铺开全库，读者就得分两次
 * 找同一件事。收窄到当前入口，左栏才和菜单说同一句话；而顶栏那个知识库按钮就是唯一的
 * 「我要看全库」出口，读者不必回到菜单里找。</p>
 */
const asideTree = computed<DocNode[]>(() => {
  const full = view.value?.tree || []
  if (allMode.value) return full
  const active = menu.value.find((item) => item.path === activeMenuPath.value)
  if (!active) return full
  const node = findNode(full, active.path)
  if (!node) return full
  if (active.type === 'dir') return node.children || []
  const parent = active.path.includes('/') ? active.path.slice(0, active.path.lastIndexOf('/')) : ''
  if (!parent) return full
  return findNode(full, parent)?.children || full
})

/**
 * 全库 ↔ 栏目范围来回切。
 *
 * <p>这个开关现在只有顶栏那颗知识库按钮一个入口了：左栏顶部原先还有一条
 * 「全部文档 · N 篇」的范围说明，点它也能回全库，但读者在两处对着同一个开关，
 * 而那一行最值钱的位置只用来显示一个点不得的篇数。已删，见 ReadBody 的左栏注释。</p>
 */
function toggleAll() {
  if (!menu.value.length) return
  allTree.value = !allTree.value
}

function openDoc(path: string) {
  void router.push({ name: 'share', params: { token: token.value }, query: { path } })
}

function pickMenu(item: NavMenuItem) {
  // 选菜单项意味着「回到这个入口的范围」，与顶栏的全库状态互斥
  allTree.value = false
  const target = targetOf(item)
  if (!target || target === view.value?.currentPath) return
  openDoc(target)
}

/* ------------------------------------------------------- 窄屏阅读辅助（抽屉 / 进度 / 回顶） */

/** 分享页把阅读工具条隐藏了，抽屉入口改由顶栏两颗按钮调 ReadBody 暴露出来的开合 */
const readBody = ref<InstanceType<typeof ReadBody> | null>(null)
function openDrawer(type: 'tree' | 'outline') {
  readBody.value?.openDrawer(type)
}

/** 视口底沿的进度线；比值而不是像素，换字体、换文章都不用改 */
const progress = ref(0)
/** 滚过约一屏才给回到顶部，刚打开时别在正文上压一颗没用的按钮 */
const showTotop = ref(false)
let scrollBound: HTMLElement[] = []

function measure() {
  const el = scrollEl()
  if (!el) return
  const max = el.scrollHeight - el.clientHeight
  const top = el.scrollTop
  progress.value = max > 0 ? Math.min(100, Math.max(0, (top / max) * 100)) : 0
  showTotop.value = top > 480
}

/**
 * 滚动区在宽窄屏之间会换人（宽屏是正文栏，窄屏退回 #md-view）。两个都挂监听，
 * 读数时按 scrollEl() 的计算样式判定取当前生效的那个，拉窗口不用重新拆装也能算对。
 */
function bindScroll() {
  scrollBound.forEach((el) => el.removeEventListener('scroll', measure))
  scrollBound = [viewEl(), document.querySelector<HTMLElement>('[data-md-scroll]')].filter(
    (el): el is HTMLElement => !!el,
  )
  scrollBound.forEach((el) => el.addEventListener('scroll', measure, { passive: true }))
  measure()
}

onMounted(() => {
  bindScroll()
  window.addEventListener('resize', bindScroll)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', bindScroll)
  scrollBound.forEach((el) => el.removeEventListener('scroll', measure))
})
// 切文档 / 从加载态进阅读态时正文高度变了，进度和容器都要重算
watch(
  () => [view.value?.currentPath, state.value],
  () => void nextTick(() => { bindScroll(); measure() }),
)

function toTop() {
  scrollEl()?.scrollTo({ top: 0, behavior: 'smooth' })
}

/**
 * 手机底栏那颗「分享」。
 *
 * <p>与顶栏同一份实现（{@link useShareLink}）：手机上顶栏的动作区让位给底栏，
 * 分享是访客最可能做的事，不能因为腾地方而消失。</p>
 */
const shareUrl = computed(
  () => `${view.value?.siteBase || window.location.origin}${route.fullPath}`,
)
const { copied: shareCopied, share } = useShareLink(
  () => shareUrl.value,
  () => view.value?.kbName || '',
)
</script>

<template>
  <p v-if="state === 'loading'" class="md-empty-hint" style="padding-top: 80px;">加载中…</p>

  <LockView
    v-else-if="state === 'password'"
    ref="lockRef"
    :kb-name="kbName"
    :error="verifyError"
    :submitting="submitting"
    @submit="verify"
  />

  <StatusView
    v-else-if="state === 'expired'"
    code="410"
    title="分享已过期"
    message="该分享链接的有效期已结束，请联系分享者重新生成。"
  />

  <StatusView
    v-else-if="state === 'notfound' || !view"
    code="404"
    title="分享不存在"
    :message="errorMessage || '链接无效、已被撤销，或关联内容已被删除。'"
  />

  <template v-else>
    <!--
      分享页自带顶栏与状态栏（门户顶栏里是搜索与身份，这里两样都没有），
      所以整页的骨架由这里拼，不再复用阅读页那条面包屑工具条。
      知识库名与导航菜单都交给顶栏渲染，紧挨品牌右侧，不再另起一行；
      顶栏那颗知识库按钮是「左栏看全库」的唯一开关（见 asideTree）。
    -->
    <div class="md-share" :style="{ '--md-read-progress': `${progress}%` }">
      <ShareTopBar
        :kb-name="view.kbName"
        :site-base="view.siteBase"
        :doc-count="view.docCount"
        :updated-text="view.kbUpdatedText"
        :all-mode="allMode"
        :views="view.views"
        :expires="view.expiresText"
        :repo-url="view.repoUrl"
        :menu="menu"
        :active-path="activeMenuPath"
        @pick="pickMenu"
        @all="toggleAll"
        @tree="openDrawer('tree')"
        @outline="openDrawer('outline')"
      />
      <ReadBody
        ref="readBody"
        :view="view"
        :switching="switching"
        :tree="asideTree"
      >
        <!-- 顶栏菜单坞在手机上让位给目录按钮，这份住进抽屉头，全库 / 栏目切换不丢 -->
        <template #drawer-head>
          <ShareMenu
            v-if="menu.length"
            :items="menu"
            :active-path="activeMenuPath"
            @pick="pickMenu"
          />
        </template>
      </ReadBody>
      <ShareStatusBar
        :kb-name="view.kbName"
        :doc-name="view.currentName"
        :word-count="view.wordCount"
        :line-count="view.lineCount"
        :author="view.author"
        :published-text="view.publishedText"
        :updated-text="view.updatedText"
      />

      <!--
        阅读进度：只在手机出现，一条 2px 渐变细线压在底部动作栏上沿。
        宽屏正文栏独立滚动，进度线没有存在的必要，直接不渲染出来。
      -->
      <div
        class="md-share__progress"
        :class="{ 'is-idle': progress <= 0 }"
        aria-hidden="true"
      >
        <span class="md-share__progress-fill" />
      </div>

      <!--
        手机底栏（≤620 才出现）：顶栏此时只剩品牌与库名，目录 / 大纲 / 设置 / 分享
        下沉到这里。访客没有「返回」与「编辑」—— 退出去是浏览器的事，编辑要登录。
      -->
      <MobileActionBar label="文档操作">
        <button
          v-if="view.showTree"
          type="button"
          class="md-actionbar__item"
          @click="openDrawer('tree')"
        >
          <Icon name="folder" :size="19" />
          <span class="md-actionbar__label">目录</span>
        </button>
        <button
          v-if="view.outline.length"
          type="button"
          class="md-actionbar__item"
          @click="openDrawer('outline')"
        >
          <Icon name="list" :size="19" />
          <span class="md-actionbar__label">大纲</span>
        </button>
        <ReaderPrefsMenu variant="segment" context="share" />
        <button
          type="button"
          class="md-actionbar__item"
          :title="shareCopied ? '链接已复制' : '分享'"
          @click="share"
        >
          <Icon :name="shareCopied ? 'check' : 'share'" :size="19" />
          <span class="md-actionbar__label">{{ shareCopied ? '已复制' : '分享' }}</span>
        </button>
      </MobileActionBar>

      <!--
        回到顶部改成「滚过一屏才出现」的悬浮按钮：常驻状态栏里那颗既占高度又压住正文末行，
        手机上更顺手的是右下角拇指够得着的位置。
      -->
      <button
        v-if="showTotop"
        type="button"
        class="md-share__totop-fab"
        aria-label="回到顶部"
        title="回到顶部"
        @click="toTop"
      >
        <Icon name="arrowUp" :size="18" />
      </button>
    </div>
  </template>
</template>
