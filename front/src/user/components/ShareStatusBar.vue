<script setup lang="ts">
import Icon from '@/user/components/Icon.vue'
import { scrollEl } from '@/shared/scrollRegion'

/**
 * 分享页底部状态栏：左边是「哪一篇 + 多长 + 谁写的 + 什么时候」，右边是回到顶部。
 *
 * <p>这些元信息原本挤在正文标题下方，长文一滚就再也看不见；挪到常驻状态栏后，
 * 读者在任何位置都能确认自己在读什么、是谁的稿子。</p>
 */
defineProps<{
  kbName: string
  docName?: string
  wordCount: number
  lineCount: number
  author?: string
  publishedText?: string
  updatedText?: string
}>()

/** 页面本身不滚，回到顶部要滚的是当前生效的那个滚动区（宽屏是正文栏，窄屏退回 #md-view） */
function toTop() {
  scrollEl()?.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<template>
  <footer class="md-share__status">
    <div class="md-share__status-main">
      <span class="md-share__crumb">
        <span>{{ kbName }}</span>
        <span class="md-share__slash">/</span>
        <strong>{{ docName }}</strong>
      </span>
      <span class="md-share__stat">{{ wordCount }} 字</span>
      <span class="md-share__stat">{{ lineCount }} 行</span>
      <span v-if="author" class="md-share__stat">
        <Icon name="user" :size="13" />{{ author }}
      </span>
      <span v-if="publishedText" class="md-share__stat">
        <Icon name="calendar" :size="13" />{{ publishedText }}
      </span>
      <span v-if="updatedText" class="md-share__stat">
        <Icon name="clock" :size="13" />{{ updatedText }}
      </span>
    </div>

    <button type="button" class="md-share__totop" @click="toTop">
      <Icon name="arrowUp" :size="14" />回到顶部
    </button>
  </footer>
</template>