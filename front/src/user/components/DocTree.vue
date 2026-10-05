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

/** 折叠状态按路径记住，节点重新渲染时不丢 */
const collapsed = reactive<Record<string, boolean>>({})

function toggle(path: string) {
  collapsed[path] = !collapsed[path]
}

/**
 * 自动展开当前文档的祖先目录。
 *
 * <p>菜单项是一篇文章时，它是被「跳」进来的，左栏被收窄到它所在的那一层；读者先前若把某个
 * 祖先目录收起了，点菜单就会落到一篇看不见的文档上。这里按 currentPath 的前缀把沿途目录一律
 * 展开 —— 每层实例只认自己直接渲染的那一节，逐层跑下来整条链就都开了。</p>
 */
watch(
  () => [props.currentPath, props.nodes] as const,
  () => {
    const current = props.currentPath
    if (!current) return
    for (const node of props.nodes) {
      if (node.type === 'dir' && current.startsWith(`${node.path}/`)) collapsed[node.path] = false
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
      :class="[node.type === 'dir' ? 'is-dir' : 'is-doc', { 'is-collapsed': collapsed[node.path], 'is-hidden': isHidden(node) }]"
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
          :aria-expanded="!collapsed[node.path]"
          @click="toggle(node.path)"
        >
          <Icon class="md-tree__chevron" name="chevronDown" :size="14" />
          <span class="md-tree__name">{{ node.name }}</span>
          <em class="md-tree__count">{{ node.children.length }}</em>
        </button>
        <router-link
          v-else
          class="md-tree__link"
          :class="{ 'is-active': node.path === currentPath }"
          :to="linkTo(node)"
        >
          <span class="md-tree__dot" />
          <span class="md-tree__name">{{ node.name }}</span>
        </router-link>
      </div>
      <DocTree
        v-if="node.type === 'dir' && node.children.length"
        :nodes="node.children"
        :current-path="currentPath"
        :link-prefix="linkPrefix"
        :keyword="keyword"
      />
    </li>
  </ul>
</template>
