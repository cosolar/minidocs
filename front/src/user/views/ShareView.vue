<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ReadBody from '@/user/components/ReadBody.vue'
import ShareStatusBar from '@/user/components/ShareStatusBar.vue'
import ShareTopBar from '@/user/components/ShareTopBar.vue'
import StatusView from '@/user/views/StatusView.vue'
import LockView from '@/user/views/LockView.vue'
import { shareApi } from '@/user/api'
import { toApiErrorInfo } from '@/shared/api/http'
import { useBodyClasses } from '@/user/composables/useBodyClasses'
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
 * 左栏这一栏的标题：全库时说「全部文档」，收窄时说清是哪个菜单项的范围。
 *
 * <p>收窄后的目录凭空少了大半篇数，不说清范围，读者会以为这个库就这么大。</p>
 */
const scopeLabel = computed(() => {
  if (allMode.value) return `全部文档 · ${view.value?.docCount ?? 0} 篇`
  const active = menu.value.find((item) => item.path === activeMenuPath.value)
  return active ? active.name || active.path : '全部文档'
})

/** 顶栏知识库按钮：全库 ↔ 栏目范围来回切 */
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
    <div class="md-share">
      <ShareTopBar
        :kb-name="view.kbName"
        :cover="view.kbCoverSrc"
        :site-base="view.siteBase"
        :doc-count="view.docCount"
        :updated-text="view.kbUpdatedText"
        :all-mode="allMode"
        :views="view.views"
        :expires="view.expiresText"
        :menu="menu"
        :active-path="activeMenuPath"
        @pick="pickMenu"
        @all="toggleAll"
      />
      <ReadBody
        :view="view"
        :switching="switching"
        :tree="asideTree"
        :scope-label="scopeLabel"
        :scope-all="allMode"
        @scope-all="allTree = true"
      />
      <ShareStatusBar
        :kb-name="view.kbName"
        :doc-name="view.currentName"
        :word-count="view.wordCount"
        :line-count="view.lineCount"
        :author="view.author"
        :published-text="view.publishedText"
        :updated-text="view.updatedText"
      />
    </div>
  </template>
</template>
