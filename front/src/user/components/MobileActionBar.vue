<script setup lang="ts">
import Icon from '@/user/components/Icon.vue'
import { scrollEl } from '@/shared/scrollRegion'

/**
 * 手机端底部操作条：把文档操作从顶部条挪到拇指够得到的地方。
 *
 * <p>为什么要有这一条：手机宽度下顶部那条工具条里塞着返回 / 设置 / 编辑 / 分享 / 大纲
 * 五颗按钮，面包屑被挤成一小截，「我在读哪篇」反而最先看不见；而底部原先只放了一行
 * 元信息（发布于 / 字数 / 行数），既点不动也不常看。把按钮下沉、元信息收进正文末尾，
 * 一屏的上下两端就各自只做一件事。</p>
 *
 * <p>共用的只有外壳与「回到顶部」这一格：门户阅读页和分享页能做的事不一样（分享页没有
 * 返回和编辑，多一个复制链接），动作由宿主通过默认插槽塞进来，各自保持简单。</p>
 *
 * <p>{@code position: fixed} 而不是排进 {@code .md-read} 的 flex 列：分享页的骨架是
 * 一整页在滚，阅读页是正文栏自己在滚，两种滚动区没法共用同一个「钉底」算法；
 * fixed 对两者都成立，代价是宿主得给滚动内容补一条底部留白（见样式表）。</p>
 */
defineProps<{
  /** 无障碍名称。这条栏里全是图标加两字标签，读屏需要一个整体说明 */
  label?: string
}>()

function toTop() {
  scrollEl()?.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<template>
  <nav class="md-actionbar" :aria-label="label || '文档操作'">
    <slot />
    <button type="button" class="md-actionbar__item" title="回到顶部" @click="toTop">
      <Icon name="arrowUp" :size="19" />
      <span class="md-actionbar__label">回到顶部</span>
    </button>
  </nav>
</template>
