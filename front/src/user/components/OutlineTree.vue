<script setup lang="ts">
import Icon from './Icon.vue'
import { jumpToElement } from '@/shared/scrollRegion'
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
  jumpToElement(el)
  history.replaceState(null, '', `#${id}`)
}
</script>

<template>
  <ul class="md-outline__list">
    <li v-for="node in nodes" :key="node.id" class="md-outline__item">
      <div class="md-outline__row" :data-md-outline-id="node.id" :class="{ 'is-active': node.id === activeId }">
        <!--
          箭头放在行首、正对子级那条引导线的轴心：展开/收起和「谁挂在谁下面」读的是同一条竖线。
          没有子标题的行留一个同宽空槽，标题文字才不会因为「这一条能不能展开」而左右参差。
        -->
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
        <span v-else class="md-outline__toggle-spacer" aria-hidden="true" />
        <a class="md-outline__link" :href="`#${node.id}`" @click.prevent="jump(node.id)">{{ node.text }}</a>
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
