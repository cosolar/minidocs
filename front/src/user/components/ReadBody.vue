<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import DocTree from './DocTree.vue'
import KbGlyph from '@/shared/components/KbGlyph.vue'
import Icon from './Icon.vue'
import OutlineTree from './OutlineTree.vue'
import { enhanceMarkdown } from '@/shared/enhanceMarkdown'
import { appHref, stripAppBase } from '@/shared/appBase'
import { flattenOutline, useScrollSpy } from '@/user/composables/useScrollSpy'
import { isTreeAllExpanded, setTreeExpanded } from '@/user/composables/useTreeExpand'
import ImagePreview from '@/user/components/ImagePreview.vue'
import ReaderPrefsMenu from '@/user/components/ReaderPrefsMenu.vue'
import type { DocNode, OutlineNode, ReadView } from '@/user/api/types'

const props = defineProps<{
  view: ReadView
  switching?: boolean
  /**
   * 左栏要显示的目录树；不给就是整棵树。
   *
   * <p>分享页配了导航菜单时，菜单已经把库切成了几个入口，左栏再铺开全库会让读者找同一件事
   * 分两次，所以由外层按当前菜单项收窄后传进来。门户阅读页没有菜单，照旧走 view.tree。</p>
   */
  tree?: DocNode[]
}>()

/** 左栏实际渲染的树：外层给了就用外层的，否则用视图自带的那棵 */
const treeNodes = computed(() => props.tree ?? props.view.tree)

/** 是不是每一层目录都展开了 —— 决定那颗按钮写「全部折叠」还是「全部展开」 */
const allExpanded = computed(() => isTreeAllExpanded(treeNodes.value))

const router = useRouter()

/**
 * 正文里的跨文档链接由后端渲染成裸 {@code <a href>}（门户是 {@code /kb/...?path=...}，分享页是
 * {@code /share/...}，都带 {@code APP_BASE} 前缀），点它会走浏览器整页跳转——正文、目录、大纲
 * 全被重建一遍。这里把它接回前端路由，和左侧目录点选一样只换正文与大纲。
 *
 * <p>{@code href} 本身**保持原样**（中键 / 新标签页 / 禁用 JS 时仍要能直接请求，所以那一层前缀
 * 不能动），只有交给 router 的那份剥掉：router 的 base 已经含它，带前缀 push 会拼出
 * {@code /minidocs/minidocs/kb/...}。</p>
 */
function onContentClick(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0) return
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const anchor = (event.target as HTMLElement | null)?.closest?.('a[href]') as HTMLAnchorElement | null
  if (!anchor || (anchor.target && anchor.target !== '_self')) return
  const path = stripAppBase(anchor.getAttribute('href') || '')
  if (!/^\/(kb|share)\//.test(path)) return
  event.preventDefault()
  void router.push(path)
}

const markdownEl = ref<HTMLElement | null>(null)
const treeEl = ref<HTMLElement | null>(null)
const keyword = ref('')
const drawer = ref<'tree' | 'outline' | null>(null)

/**
 * 把左栏里的当前文档滚进视野。
 *
 * <p>菜单项是一篇文章时，读者是「跳」进去的，那篇可能在目录深处；左栏若不跟着滚，
 * 看起来就像没切换。block: 'nearest' 只在看不见时才滚，不会无谓地跳动。</p>
 */
function revealActive() {
  treeEl.value
    ?.querySelector<HTMLElement>('.md-tree__link.is-active')
    ?.scrollIntoView({ block: 'nearest' })
}

/** 大纲标题 id 列表，供滚动联动使用 */
const outlineIds = computed(() => flattenOutline(props.view.outline || []).map((node) => node.id))
const { activeId, refresh: refreshSpy } = useScrollSpy(outlineIds)

/**
 * 大纲手风琴：默认只展开「当前正在读的那条标题」这条枝（连同它的各级父标题），其余枝收起，
 * 长文的大纲因此不会一屏铺开几十个标题。
 *
 * <p>读者点箭头展开 / 收起时记进 override，优先级高于滚动联动算出来的结果 —— 点了就该照做，
 * 否则「点收起却没反应」比收错了更让人困惑。换一篇文章时清空，免得上一篇摊开的枝带到下一篇。</p>
 */
const outlineOverrides = reactive(new Map<string, boolean>())

/** 从根到当前标题的 id 链：只有这条链默认是展开的 */
const activeOutlineChain = computed(() => {
  const chain = new Set<string>()
  const walk = (nodes: OutlineNode[], trail: string[]): boolean => {
    for (const node of nodes) {
      const next = [...trail, node.id]
      if (node.id === activeId.value) {
        next.forEach((id) => chain.add(id))
        return true
      }
      if (node.children?.length && walk(node.children, next)) return true
    }
    return false
  }
  walk(props.view.outline || [], [])
  return chain
})

function isOutlineExpanded(id: string) {
  return outlineOverrides.get(id) ?? activeOutlineChain.value.has(id)
}

function toggleOutline(id: string) {
  outlineOverrides.set(id, !isOutlineExpanded(id))
}

watch(() => props.view.outline, () => outlineOverrides.clear())

/** 分享页左栏是紧凑卡片，文案与门户不同；搜索也只在有树可搜时才有意义 */
const isShare = computed(() => props.view.mode === 'share')
const searchPlaceholder = computed(() => (isShare.value ? '搜索文档…' : '搜索本文档…'))

/**
 * 顶栏「返回」。
 *
 * <p>有来路就退一步，没有就回知识库列表。判据是 {@code history.state.back} ——
 * 直接打开（分享链接、新标签页、书签）时它是空的，此时 {@code router.back()} 什么都不会发生，
 * 读者按一下没反应，只会以为按钮坏了。</p>
 *
 * <p>用 {@code push('/')} 而不是 {@code replace}：读者退到列表后按浏览器前进，
 * 应该还能回到刚才读的那篇。</p>
 */
function goBack() {
  if (window.history.state?.back) router.back()
  else void router.push('/')
}

/**
 * 「打开分享页」的地址。
 *
 * <p>只在门户侧给：分享页自己就在分享页上，再放一颗跳自己的按钮是噪音。</p>
 *
 * <p>三个条件缺一不可 —— 是门户、库发布过（后端才回 token）、状态不是未发布。
 * 未发布时后端不给 token，这里自然也是空串，按钮不渲染：给一个点开 404 的入口
 * 比不给入口更糟。</p>
 */
const shareLink = computed(() => {
  const v = props.view
  if (v.mode !== 'portal' || !v.shareToken || v.shareStatus === 'unpublished') return ''
  return `/share/${encodeURIComponent(v.shareToken)}`
})

/**
 * 左栏那颗胶囊答的是「进来要不要输密码」，所以读发布态（{@code shareStatus}），
 * **不是** {@code visibility}。
 *
 * <p>此前这里读的是 visibility，于是门户页左下角写着「私有」，而同一页的列表卡片写着「公开」——
 * 两个都用 globe/lock 图标、两个都叫「公开/私有」，说的却分别是平台内权限和发布后的口令。
 * 读者不关心这个库归谁管，所以改成读发布态，与门户卡片、门户页签同一套口径。</p>
 *
 * <p>分享页整颗隐藏：能走到这一步说明口令已校验通过，再提示「加密」只会让人以为自己还欠一关。</p>
 */
const showAccessPill = computed(() => !isShare.value)
const needsPassword = computed(() => props.view.shareStatus === 'private')
const accessLabel = computed(() => (needsPassword.value ? '加密' : '共享'))
const accessIcon = computed(() => (needsPassword.value ? 'lock' : 'globe'))

/**
 * 当前预览的库内图片；为 null 时正文区照常显示文章。
 *
 * <p>从目录树点图片进来、切走时置空。单篇分享（{@code singleDoc}）下没有目录树，
 * 所以这个状态只可能出现在整库阅读页。</p>
 */
const previewAsset = ref<DocNode | null>(null)

/** 换了文档就退出预览：否则上一篇的图片会盖在新一篇的正文上。 */
watch(() => props.view.currentPath, () => { previewAsset.value = null })

function onTreeSelect(node: DocNode) {
  previewAsset.value = node.type === 'image' ? node : null
}

/**
 * 两侧栏宽可拖拽调节（门户阅读页与分享页共用）：宽度存在根元素 CSS 变量上，样式表只在
 * 桌面断点消费它，手机 / 打印断点自己写死栅格，不会被内联变量盖掉。持久化到 localStorage。
 *
 * <p>正文宽度因此不再由全局「内容宽度」偏好决定 —— 门户阅读页让正文吃满中栏，
 * 想收窄就拖宽两侧栏，宽度完全交给读者。</p>
 */
const ASIDE_KEY = 'md-read-aside-width'
const OUTLINE_KEY = 'md-read-outline-width'
const MIN_W = 180
const MAX_W = 560

function loadWidth(key: string) {
  const n = Number(localStorage.getItem(key))
  return n >= MIN_W && n <= MAX_W ? n : null
}

const asideWidth = ref(loadWidth(ASIDE_KEY))
const outlineWidth = ref(loadWidth(OUTLINE_KEY))
const frameStyle = computed(() => ({
  '--md-aside-w': asideWidth.value ? `${asideWidth.value}px` : undefined,
  '--md-outline-w': outlineWidth.value ? `${outlineWidth.value}px` : undefined,
}))

/** dir=1 拖左栏右边缘（越往右越宽），dir=-1 拖右栏左边缘 */
function startResize(dir: 1 | -1, width: number | null, key: string, event: PointerEvent) {
  event.preventDefault()
  const startX = event.clientX
  const startW = width ?? (dir === 1 ? 260 : 240)
  const move = (e: PointerEvent) => {
    const next = Math.min(MAX_W, Math.max(MIN_W, startW + dir * (e.clientX - startX)))
    if (dir === 1) asideWidth.value = next
    else outlineWidth.value = next
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    document.body.classList.remove('md-resizing')
    localStorage.setItem(key, String(dir === 1 ? asideWidth.value : outlineWidth.value))
  }
  document.body.classList.add('md-resizing')
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

/** 移动端抽屉靠 body 上的状态类控制，与样式表里的媒体查询约定一致 */
watch(drawer, (value) => {
  document.body.classList.toggle('md-main-tree-open', value === 'tree')
  document.body.classList.toggle('md-main-outline-open', value === 'outline')
})

onMounted(() => void decorate())
watch(() => [props.view.html, props.view.currentPath], () => void decorate())

onBeforeUnmount(() => {
  document.body.classList.remove('md-main-tree-open', 'md-main-outline-open')
})

/** 正文挂载后做一次增强：代码高亮 / Mermaid / KaTeX / 灯箱 */
async function decorate() {
  await nextTick()
  revealActive()
  await enhanceMarkdown(markdownEl.value)
  refreshSpy()
}

/**
 * 「编辑」入口的地址。写没写权由后端的 {@code canManage} 说（§2.3 的写轴），这里只把它翻译成路由。
 *
 * <p>组织段取 {@code view.orgSlug} 而不是当前 URL：门户与分享共用这套模板，而库 slug 只在组织内唯一
 * （§4.2），从地址栏反推工作区路由等于把路由形状抄第二份，那边一改这里就静默指错库。</p>
 */
const manageLink = computed(() => {
  const view = props.view
  if (!view.canManage || view.mode !== 'portal' || !view.orgSlug || !view.kbSlug) return ''
  const base = `/console/${encodeURIComponent(view.orgSlug)}/kbs/${encodeURIComponent(view.kbSlug)}`
  return view.currentPath ? `${base}?path=${encodeURIComponent(view.currentPath)}` : base
})

/**
 * 「上一篇 / 下一篇」的地址。
 *
 * <p>后端用 {@code docLinkPrefix} 拼的，那一带上下文路径（要能被浏览器直接请求）；
 * 而这里是 {@code <router-link>}，router 的 base 已经含同一层，不剥就成
 * {@code /minidocs/minidocs/...}。缺地址时兜到 {@code '#'}，与改动前一致。</p>
 */
function routerTo(link?: string) {
  return link ? stripAppBase(link) : '#'
}
</script>

<template>
  <div class="md-reading" :data-mode="view.mode" :data-single="view.singleDoc" :style="frameStyle">
    <!-- 目录树 -->
    <aside class="md-reading__aside">
      <div class="md-aside__card">
        <!--
          分享页左栏顶部：一行「目录 + 全部展开/折叠」。原先这里是一条范围说明
          「全部文档 · 56 篇」，两个字都删掉了：

          <p><b>篇数删掉。</b>数字对读者没有可行动作 —— 不能点、不能筛、也不能拿来判断什么，
          却占了左栏最宝贵的第一行，还和搜索框重复（搜一下就知道有多少篇）。</p>

          <p><b>范围开关也删掉。</b>它和顶栏那颗知识库按钮是同一个开关的两个入口
          （点它回全库），顶栏那颗一直在，左边的这颗是重复的；删掉不丢功能。</p>

          <p><b>换成展开/折叠。</b>目录默认只展开当前文档的祖先链，几百篇的库一屏就那么几条。
           「全部展开 / 全部折叠」是这条目录上唯一缺的动作 —— 想通览全库结构时，一层层点开要点到手酸。</p>
        -->
        <div v-if="isShare" class="md-aside__treebar">
          <span class="md-aside__treebar-title">
            <Icon name="books" :size="14" />
            <span>目录</span>
          </span>
          <!-- 纯图标：文字去掉后靠 title / aria-label 说明含义，否则读者只能靠猜 -->
          <button
            type="button"
            class="md-aside__treebar-btn"
            :title="allExpanded ? '折叠所有目录' : '展开所有目录'"
            :aria-label="allExpanded ? '折叠所有目录' : '展开所有目录'"
            @click="setTreeExpanded(treeNodes, !allExpanded)"
          >
            <Icon :name="allExpanded ? 'collapseAll' : 'expandAll'" :size="16" />
          </button>
        </div>

        <template v-else>
          <!--
            左栏第一屏就是知识库卡片，不再放「返回知识库」。

            <p>那颗按钮原本占着左栏最值钱的第一行，而它给的是「回门户首页」——
            顶栏那颗 MiniDocs 品牌标志是同一个去处，读者认的是 logo 不是这行小字。
            删掉之后上下都不补白：卡片直接贴在左栏内边距上，中间那一大块空档
            原来就是按钮 + 它的下外边距撑出来的。</p>
          -->
          <div class="md-aside__kb">
            <!--
              插画只做装饰垫底（pointer-events 关掉，不挡胶囊点击），右侧渐隐进来。
              图标 + 标题 + 简介压在其上：绝对定位的兄弟元素会盖过普通流内容，
              所以文字区必须带 position:relative 才不会被垫图压住。
            -->
            <div class="md-aside__kb-art" aria-hidden="true" />
            <div class="md-aside__kb-head">
              <span class="md-aside__kb-glyph" aria-hidden="true"><KbGlyph :size="22" /></span>
              <div class="md-aside__kb-text">
                <h2>{{ view.kbName }}</h2>
                <p v-if="view.kbDescription" class="md-aside__desc">{{ view.kbDescription }}</p>
              </div>
            </div>
            <!-- 篇数 / 标签数 / 可见性收成一排小胶囊：比「7 篇文章 · 0 个标签 · 公开」一行灰字好扫 -->
            <div class="md-aside__meta">
              <span class="md-aside__pill">
                <Icon name="file" :size="12" />{{ view.docCount }} 篇
              </span>
              <span class="md-aside__pill">
                <Icon name="list" :size="12" />{{ view.tagCount }} 标签
              </span>
              <span v-if="showAccessPill" class="md-aside__pill" :class="needsPassword ? 'is-private' : 'is-public'">
                <Icon :name="accessIcon" :size="12" />{{ accessLabel }}
              </span>
            </div>
          </div>
        </template>

        <div v-if="view.showTree" class="md-aside__search">
          <Icon name="search" :size="14" />
          <input v-model="keyword" type="search" :placeholder="searchPlaceholder" autocomplete="off">
        </div>
        <nav v-if="view.showTree && treeNodes.length" ref="treeEl" class="md-aside__tree">
          <DocTree
            :nodes="treeNodes"
                      :current-path="view.currentPath"
             :link-prefix="view.docLinkPrefix || ''"
             :keyword="keyword"
             :show-md-suffix="view.showMdSuffix"
            @select="onTreeSelect"
                    />
        </nav>
      </div>
    </aside>

    <!-- 左栏拖宽手柄：桌面布局出现，窄屏由样式表隐藏 -->
    <div
      class="md-reading__resize md-reading__resize--aside"
      @pointerdown="startResize(1, asideWidth, ASIDE_KEY, $event)"
    />

    <!-- 正文 -->
    <!--
      阅读条：**宽屏下横跨正文栏与右侧目录栏**，两栏顶部连成一条（见样式表那条注释）。
      所以它现在是 .md-reading 的直接子元素、而不是 main 的子元素 —— 放在 main 里的话，
      它永远只能铺满正文那一栏，右边一到把手就断掉。
      分享页整个隐藏（.md-reading[data-mode='share'] .md-reading__bar{display:none}），两颗按钮已挪出。
    -->
    <div class="md-reading__bar">
      <button type="button" class="md-icon-btn md-only-mobile" aria-label="目录" @click="drawer = 'tree'">
        <Icon name="books" :size="16" />
      </button>
      <nav class="md-breadcrumb">
        <span>{{ view.kbName }}</span>
        <span>/</span>
        <span>{{ view.currentName }}</span>
      </nav>
      <div class="md-reading__bar-actions">
        <span v-if="switching" class="md-reading__switching" role="status">
          <i class="md-reading__spinner" aria-hidden="true" />载入中…
        </span>
        <!--
          返回：退出这篇文档。分享页不放 —— 访客是从外部链接进来的，
          「返回」退出去就是离开站点，那是浏览器的事，不该由页面替他决定。
        -->
        <button
          v-if="!isShare"
          type="button"
          class="md-icon-btn"
          title="返回"
          aria-label="返回"
          @click="goBack"
        >
          <Icon name="arrowLeft" :size="16" />
        </button>
        <!--
          阅读设置：门户阅读条上的入口。分享页那颗在 ShareTopBar 里，这里不能也放一个 ——
          两个宿主共用 ReadBody，分享页会因此出现两颗设置钮。

          <p>为什么门户原先没有：这个面板早先只挂在分享页顶栏，而「正文字体」写的是
          {@code --md-read-font} 这个**设备偏好**，三个宿主的正文都吃得到。面板却只出现在
          一处，于是读者在门户里换了篇长文想换个顺眼的字体，翻遍顶栏也找不到入口 ——
          偏好在生效，却没有人能改它，比没有这个设置更让人困惑。</p>
        -->
        <ReaderPrefsMenu v-if="!isShare" context="portal" />
        <!--
          编辑：纯图标，与右边「大纲」同一档。带「编辑」两个字时工具条四种形态混排
          （胶囊 / 图标钮 / 文字链），工具栏该是同一类控件的等距排列 ——
          而且两个字不如图标一眼认得出，标题与 aria-label 里都留了「编辑」。
        -->
        <router-link
          v-if="manageLink"
          class="md-icon-btn md-icon-btn--violet"
          :to="manageLink"
          title="编辑"
          aria-label="编辑"
        >
          <Icon name="edit" :size="16" />
        </router-link>
        <!--
          打开分享页：新标签页打开，所以用 <a target="_blank"> 而不是 router-link ——
          读者点它是「另开一个地方看」，不是「离开当前这篇」。
          库没发布过时 shareLink 为空，这颗按钮整个不渲染。
        -->
        <a
          v-if="shareLink"
          class="md-icon-btn md-icon-btn--blue"
          :href="shareLink"
          target="_blank"
          rel="noopener"
          title="打开分享页"
          aria-label="打开分享页"
        >
          <Icon name="externalLink" :size="16" />
        </a>
        <button
          type="button"
          class="md-icon-btn md-icon-btn--teal md-only-outline-mobile"
          aria-label="大纲"
          title="大纲"
          @click="drawer = 'outline'"
        >
          <Icon name="list" :size="16" />
        </button>
      </div>
    </div>

    <!--
      data-md-scroll 把这一栏声明成滚动区（门户阅读页与分享页宽屏都是它自己在滚，
      窄屏时样式表把它退回文档流，scrollRegion 会按计算样式判回来）。
      大纲联动、回到顶部、前进后退的位置恢复都靠它找到该滚谁，不要再写死 #md-view。
    -->
    <main class="md-reading__main" data-md-scroll="" :class="{ 'is-switching': switching }">
      <article v-if="!view.empty" class="md-doc">
        <!--
          正文页不再渲染任何 frontmatter 元信息：标签与分享有效期属于文档属性，不是正文，
          留在正文顶部只会把读者第一眼要看的标题往下压。有效期改由分享页顶栏右侧承载，
          标题与摘要本来就写在正文里（后端只是把它们推导出来），交还给正文自己。
        -->
        <ImagePreview
          v-if="previewAsset"
          :path="previewAsset.path"
          :name="previewAsset.name"
          :asset-prefix="view.assetPrefix || ''"
          @close="previewAsset = null"
        />
        <div v-else ref="markdownEl" class="md-markdown" v-html="view.html" @click="onContentClick" />

        <nav v-if="view.prevPath || view.nextPath" class="md-doc__nav">
          <router-link v-if="view.prevPath" class="md-doc__nav-item" :to="routerTo(view.prevLink)">
            <small>上一篇</small><strong>{{ view.prevName }}</strong>
          </router-link>
          <router-link
            v-if="view.nextPath"
            class="md-doc__nav-item md-doc__nav-item--next"
            :to="routerTo(view.nextLink)"
          >
            <small>下一篇</small><strong>{{ view.nextName }}</strong>
          </router-link>
        </nav>
      </article>

      <div v-else class="md-empty">
        <h2>这个知识库还是空的</h2>
        <!-- 空库对维护者和对读者的下一步完全不同：一个要建文档，一个要么没权限要么得加入组织 -->
        <template v-if="manageLink">
          <p>这个库还没有任何 Markdown 文件，去工作区建第一篇。</p>
          <router-link class="md-btn md-btn--primary" :to="manageLink">
            <Icon name="plus" :size="14" />去工作区新建
          </router-link>
        </template>
        <template v-else>
          <p>登录后在管理后台新建第一篇 Markdown 文档吧。</p>
          <a class="md-btn md-btn--primary" :href="appHref('/console')">
            <Icon name="dashboard" :size="14" />前往管理后台
          </a>
        </template>
      </div>
    </main>

    <!-- 右栏拖宽手柄 -->
    <div
      class="md-reading__resize md-reading__resize--outline"
      @pointerdown="startResize(-1, outlineWidth, OUTLINE_KEY, $event)"
    />

    <!-- 大纲 -->
    <aside class="md-reading__outline">
      <div class="md-outline__card">
        <div v-if="isShare" class="md-outline__head">
          <Icon name="list" :size="15" />
          <span>目录</span>
        </div>
        <h2 v-else class="md-outline__title">文章目录</h2>
        <div v-if="view.outline.length" class="md-outline__body">
          <OutlineTree
            :nodes="view.outline"
            :active-id="activeId"
            :is-expanded="isOutlineExpanded"
            :toggle="toggleOutline"
          />
        </div>
        <p v-else class="md-outline__empty">本文没有标题结构</p>
      </div>
    </aside>

    <div v-if="drawer" class="md-drawer-mask" @click="drawer = null" />
  </div>
</template>