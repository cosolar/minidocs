<script setup lang="ts">
import Icon from './Icon.vue'
import type { OutlineNode } from '@/shared/api/types'

defineProps<{
  nodes: OutlineNode[]
  activeId?: string
  /** 该标题的子标题当前是否展开；手风琴状态由页面统一持有，递归时原样透传 */
  isExpanded: (id: string) => boolean
  /** 手动展开 / 收起某一枝 */
  toggle: (id: string) => void
}>()

function jump(id: string) {
  const el = document.getElementById(id)
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  history.replaceState(null, '', `#${id}`)
}
</script>

<template>
  <ul class="md-outline__list">
    <li v-for="node in nodes" :key="node.id" class="md-outline__item">
      <div class="md-outline__row" :class="{ 'is-active': node.id === activeId }">
        <a class="md-outline__link" :href="`#${node.id}`" @click.prevent="jump(node.id)">{{ node.text }}</a>
        <!-- 有子标题才配箭头；点标题本身是跳转，点箭头才是展开/收起 -->
        <button
          v-if="node.children?.length"
          type="button"
          class="md-outline__toggle"
          :aria-expanded="isExpanded(node.id)"
          :title="isExpanded(node.id) ? '收起' : '展开'"
          @click="toggle(node.id)"
        >
          <Icon class="md-outline__chevron" name="chevronDown" :size="13" />
        </button>
      </div>
      <OutlineTree
        v-if="node.children?.length && isExpanded(node.id)"
        :nodes="node.children"
        :active-id="activeId"
        :is-expanded="isExpanded"
        :toggle="toggle"
      />
    </li>
  </ul>
</template>
