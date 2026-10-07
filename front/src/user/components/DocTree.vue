<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import Icon from './Icon.vue'
import { stripAppBase } from '@/shared/appBase'
import type { DocNode } from '@/shared/api/types'

const props = defineProps<{
  nodes: DocNode[]
  /** 当前打开文档的路径，用于高亮 */
  currentPath?: string
  /**
   * 文档链接前缀，形如 /minidocs/kb/{org}/{slug}?path= 或 /minidocs/share/{token}?path=
   *
   * <p>后端给的这一串**含**上下文路径（它要写进裸 {@code <a href>}），所以 {@link linkTo}
   * 交给 router 前会先剥掉 {@code APP_BASE}。</p>
   */
  linkPrefix: string
  /** 目录过滤关键字 */
  keyword?: string
}>()

/** 图片节点点开：交给外层切到预览。目录仍是目录，展开行为不变。 */
const emit = defineEmits<{
  (e: 'select', node: DocNode): void
}>()

/**
 * 展开状态按路径记住，节点重新渲染时不丢。
 *
 * <p><b>默认折叠</b>：目录一多（动辄几百篇文档），全展开会把左栏拉成一条看不到尽头的长尾，
 * 而读者点进来时通常只关心「当前这篇」和它沿途那一层。这里的默认值是「收起」，
 * 展开沿途目录交给 {@link watch} 自动做。</p>
 *
 * <p>用「展开」而不是「折叠」记录状态，是因为默认值必须是「假」：
 * 若沿用 {@code collapsed}，未出现过的键是 {@code undefined}（falsy），
 * 天然等于「展开」，要改成默认收起就得在每个读取处补一层 {@code !== false} 判断 ——
 * 反过来让「缺省即收起」，新增判断点时不会漏。</p>
 */
const expanded = reactive<Record<string, boolean>>({})

function toggle(path: string) {
  expanded[path] = !expanded[path]
}

/**
 * 展开本层的全部目录。
 *
 * <p>这里只遍历一层，不递归调用自己：{@code DocTree} 是递归组件，每个实例只持有自己直接
 * 渲染的那一节，祖先链上的每一节各自跑一遍这个 watch，整棵树自然就都开了 ——
 * 和下面自动展开 currentPath 祖先用的是同一套机制。</p>
 */
function expandAll(nodes: DocNode[]) {
  for (const node of nodes) {
    if (node.type === 'dir') expanded[node.path] = true
  }
}

/**
 * 自动展开当前文档的祖先目录。
 *
 * <p>菜单项是一篇文章时，它是被「跳」进来的，左栏被收窄到它所在的那一层；读者先前若把某个
 * 祖先目录收起了，点菜单就会落到一篇看不见的文档上。这里按 currentPath 的前缀把沿途目录一律
 * 展开 —— 每层实例只认自己直接渲染的那一节，逐层跑下来整条链就都开了。</p>
 *
 * <p>搜索时改为全展开：命中项可能落在任意目录里，父目录收着的话搜索结果就等于没搜到。</p>
 */
watch(
  () => [props.currentPath, props.nodes, props.keyword] as const,
  () => {
    if ((props.keyword || '').trim()) {
      expandAll(props.nodes)
      return
    }
    const current = props.currentPath
    if (!current) return
    for (const node of props.nodes) {
      if (node.type === 'dir' && current.startsWith(`${node.path}/`)) expanded[node.path] = true
    }
  },
  { immediate: true }
)

const normalizedKeyword = computed(() => (props.keyword || '').trim().toLowerCase())

/** 过滤只作用于文档；目录始终保留，否则命中项会失去层级上下文 */
function isHidden(node: DocNode) {
  if (node.type !== 'doc' || !normalizedKeyword.value) return false
  return !node.name.toLowerCase().includes(normalizedKeyword.value)
}

/**
 * 目录右侧的数字：整棵子树里的文档篇数。
 *
 * <p>不是直接子项数 —— 直接子项数在设计稿那个例子上会给出「docs: 7」（7 个子目录）
 * 而实际有 17 篇，读者会以为这个目录比看起来小。图片不计入：它们不是文章，
 * 混进篇数里会让人对不上「全库 174 篇」这个总数。</p>
 */
function countOf(node: DocNode): number {
  if (node.type !== 'dir') return 0
  let total = 0
  for (const child of node.children || []) {
    total += child.type === 'doc' ? 1 : countOf(child)
  }
  return total
}

/**
 * 目录项的站内地址。
 *
 * <p>后端的 {@code docLinkPrefix} 已经带上了上下文路径（{@code AppPaths.of("/share/{token}?path=")}
 * → {@code /minidocs/share/{token}?path=}），因为它同时要写进渲染后的裸 {@code <a href>}。
 * 但这一份是喂给 {@code <router-link>} 的，router 的 base 已经含同一层前缀，不剥掉就会
 * 拼出 {@code /minidocs/minidocs/share/...} —— 整页 404，且现象很怪：地址栏多一层前缀，
 * 像是部署配置错了一档，其实只是同一个前缀被加了两遍。</p>
 */
function linkTo(node: DocNode) {
  return stripAppBase(`${props.linkPrefix}${node.encodedPath}`)
}
</script>

<template>
  <ul class="md-tree">
    <li
      v-for="node in nodes"
      :key="node.path"
      class="md-tree__item"
      :class="[node.type === 'dir' ? 'is-dir' : 'is-doc', { 'is-collapsed': !expanded[node.path], 'is-hidden': isHidden(node) }]"
    >
      <div class="md-tree__row">
        <!--
          目录整行可点：箭头只管展开/收起，不再是「只有那个 18px 的小三角能点」。
          之前箭头与目录名各挂一个字符图标（▾ / ▸），同一行两个三角，看着就是乱的。
        -->
        <button
          v-if="node.type === 'dir'"
          type="button"
          class="md-tree__label"
          :aria-expanded="!!expanded[node.path]"
          @click="toggle(node.path)"
        >
          <Icon class="md-tree__chevron" name="chevronDown" :size="14" />
          <Icon class="md-tree__type" name="folder" :size="15" />
          <span class="md-tree__name">{{ node.name }}</span>
          <em class="md-tree__count">{{ countOf(node) }}</em>
        </button>
        <!--
          图片节点：不能跳路由（没有对应页面），改为上抛给外层切预览。
          用 button 而不是 router-link 的另一个理由是它不该出现在 Tab 顺序的
          文档跳转序列里 —— 它不是一个「页面」。
        -->
        <button
          v-else-if="node.type === 'image'"
          type="button"
          class="md-tree__link md-tree__link--asset"
          :title="`预览图片：${node.name}`"
          @click="emit('select', node)"
        >
          <span class="md-tree__chevron md-tree__chevron--spacer" />
          <Icon class="md-tree__type" name="image" :size="15" />
          <span class="md-tree__name">{{ node.name }}</span>
        </button>
        <router-link
          v-else
          class="md-tree__link"
          :class="{ 'is-active': node.path === currentPath }"
          :to="linkTo(node)"
        >
          <!--
            箭头槽留空：目录行前面有一个 14px 的箭头，文档行补一个同宽的空槽，
            两种节点的名称才会落在同一条竖线上。原先文档行只放一个 6px 的圆点，
            于是目录名比文档名靠右一格，整棵树看上去是歪的。
          -->
          <span class="md-tree__chevron md-tree__chevron--spacer" />
          <Icon class="md-tree__type" name="file" :size="15" />
          <span class="md-tree__name">{{ node.name }}</span>
        </router-link>
      </div>
      <!--
        收起时直接不渲染子树，而不是靠 CSS 的 display:none 藏起来：
        后者只是看不见，几百个节点的 DOM 照样在内存里、也要参与 diff，
        目录一多，开合一次就要重算整棵看不见的树。少渲染一层是一层。
      -->
      <DocTree
        v-if="node.type === 'dir' && node.children.length && expanded[node.path]"
        :nodes="node.children"
        :current-path="currentPath"
        :link-prefix="linkPrefix"
        :keyword="keyword"
        @select="(n) => emit('select', n)"
      />
    </li>
  </ul>
</template>
