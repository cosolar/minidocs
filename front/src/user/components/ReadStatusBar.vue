<script setup lang="ts">
import Icon from '@/user/components/Icon.vue'
import { scrollEl } from '@/shared/scrollRegion'

/**
 * 门户阅读页底部常驻状态栏：当前文章的时间与体量，右侧是回到顶部。
 *
 * <p>与分享页的 {@code ShareStatusBar} 是两条并行的常驻栏：那边要交代「这是谁分享的哪一篇」
 * （库名 / 篇名 / 作者），这边读者本来就知道自己在哪个库，只留文章自身的信息。
 * 两个组件刻意不合并 —— 硬凑成一个会到处是 {@code v-if="mode === 'share'"}。</p>
 *
 * <p>阅读页的滚动发生在正文栏内部（页面本身不滚），所以回到顶部要滚的是 {@link scrollEl}
 * 认出来的那个容器，不能写死 window。</p>
 */
defineProps<{
  wordCount: number
  lineCount: number
  readingMinutes: number
  publishedText?: string
  updatedText?: string
}>()

function toTop() {
  scrollEl()?.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<template>
  <footer class="md-read-status">
    <div class="md-read-status__main">
      <span v-if="publishedText" class="md-read-status__stat">
        <Icon name="calendar" :size="13" />发布于 {{ publishedText }}
      </span>
      <span class="md-read-status__stat">{{ wordCount }} 字</span>
      <span class="md-read-status__stat">{{ lineCount }} 行</span>
      <span class="md-read-status__stat">约 {{ readingMinutes }} 分钟</span>
      <span v-if="updatedText" class="md-read-status__stat">
        <Icon name="refresh" :size="13" />更新于 {{ updatedText }}
      </span>
    </div>

    <button type="button" class="md-read-status__totop" title="回到顶部" aria-label="回到顶部" @click="toTop">
      <Icon name="arrowUp" :size="15" />
    </button>
  </footer>
</template>