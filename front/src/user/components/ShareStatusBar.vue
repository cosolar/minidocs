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
      <!--
        两个时间必须带文字标签。
        <p>原先这里只有日历图标 + 时间、时钟图标 + 时间。13px 的日历与时钟形状几乎
        一致，读者只能靠猜——而「这是发布时间还是最后修改时间」猜错的代价很大：把修改
        时间当成发布时间，会以为文章是那时候才写的。</p>
        <p>而「发布于 / 更新于」四个字是直接说出来的，不依赖读者认出图标。</p>
      -->
      <span v-if="publishedText" class="md-share__stat">
        <Icon name="calendar" :size="13" />发布于 {{ publishedText }}
      </span>
      <span v-if="updatedText" class="md-share__stat">
        <Icon name="refresh" :size="13" />更新于 {{ updatedText }}
      </span>
    </div>

    <button type="button" class="md-share__totop" @click="toTop">
      <Icon name="arrowUp" :size="14" />回到顶部
    </button>
  </footer>
</template>